package moe.lovefirefly.bzk.sougouext;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * ③ 把框架的 subtype 变化翻译成搜狗内部的语言切换。
 *
 * <h3>为什么需要这一层</h3>
 * 实测（本机 搜狗 OEM 29496052 / Android 16）：框架切 subtype 时搜狗会收到
 * {@code onCurrentInputMethodSubtypeChanged}，但内部状态一个字段都不动 —— 它不认 subtype。
 * 所以要由本模块替它执行一次"切换语言"。
 *
 * <h3>两种执行方式（优先第一种）</h3>
 * <ol>
 *   <li><b>直接执行搜狗自己的命令（实际修改）</b>：搜狗内部把键盘动作建模成命令，注册表在
 *       {@code WO.e}（类 {@code eP}）的 {@code SparseArray b} 里。硬键盘那几条命令 id 为负，
 *       语言切换是成对的两个：
 *       <ul>
 *         <li>{@code cta} = /hardKeyboardMode/SwitchChineseEnglishWuBi（中 → 英）</li>
 *         <li>{@code dta} = /hardKeyboardMode/SwitchEnglishChinese（英 → 中）</li>
 *       </ul>
 *       执行链：{@code eP.a(id) : hP} → {@code hP.a(WO, Bundle)}。命令 id 不写死：
 *       启动时枚举注册表、按类名匹配，匹配不到就退回第二种方式。</li>
 *   <li><b>合成 Shift 单击（兜底）</b>：搜狗的中英快捷键是 Shift 单击。合成它有三个必要细节：
 *       必须主线程（内部用 LiveData，后台线程抛 IllegalStateException）；down/up 共享同一
 *       downTime；真实 deviceId + scanCode=42 + source=SOURCE_KEYBOARD。</li>
 * </ol>
 *
 * <h3>锁步模型</h3>
 * 框架 subtype 变 → 执行一次切换。搜狗自己的界面/物理 Shift 切语言时不会回写 subtype，
 * 若用屏幕上的中/英键切过可能错位一次；由快捷键驱动时始终同步。
 */
public final class SogouTranslator {

    private static final String TAG = "SogouOemBridge";

    /** 硬键盘语言命令的类名（枚举注册表时按此匹配，避免写死 id）。 */
    private static final String CMD_CLASS_ZH_TO_EN = "cta";   // SwitchChineseEnglishWuBi
    private static final String CMD_CLASS_EN_TO_ZH = "dta";   // SwitchEnglishChinese

    private static volatile XposedModule sModule;
    private static volatile Object sService;
    private static volatile Object sWo;        // coa.b : WO
    private static volatile Object sEp;        // WO.e  : eP（命令注册表）
    private static volatile int sCmdZhToEn = Integer.MIN_VALUE;
    private static volatile int sCmdEnToZh = Integer.MIN_VALUE;

    /** 命令实例与其执行方法缓存（守卫和自发起执行都走同一份 Method）。 */
    private static volatile Object sCmdZhToEnObj;
    private static volatile Object sCmdEnToZhObj;

    /** 注册表里所有命令：类 simpleName → 实例（观察/拦截其它语言入口用）。 */
    private static final java.util.Map<String, Object> sCommands = new java.util.HashMap<>();
    private static volatile Method sRunZhToEn;
    private static volatile Method sRunEnToZh;

    /**
     * 严格模式：检测到 BZK 时开启。
     *
     * <p>此时搜狗自己的语言切换入口被守卫拦掉，语言只能由框架 subtype 驱动
     * （"只接受 subtype 的信号"）。BZK 不在时不拦，普通用户照旧用搜狗自己的快捷键。
     */
    private static volatile boolean sStrict;

    /** 功能 S：引号/括号自动关闭（默认开）。hook 可能在任何线程被调用，故单独存字段。 */
    private static volatile boolean sAutoPair = true;

    /** 功能 S/9 共用的配对表：开字符 → 闭字符（空表 = 用输入法默认匹配规则）。 */
    private static volatile java.util.Map<Character, Character> sPairMap =
            java.util.Collections.emptyMap();

    /** 功能 9：物理键盘自动补全的功能开关（UI）。 */
    private static volatile boolean sPhysComplete = true;

    /** 功能 9 的状态位（由 Ctrl+Shift+9 临时切换并持久化）。 */
    private static volatile Boolean sPhysState;

    /** 标记"这次语言命令是本模块发起的"，守卫据此放行。 */
    private static final ThreadLocal<Boolean> sOurs = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return Boolean.FALSE; }
    };

    private static volatile boolean sViewReady;
    private static volatile int sLastSubtypeHash;
    private static volatile long sReadyAt;

    /** 框架最后一次告知的当前 subtype（写回/探针要用）。 */
    private static volatile InputMethodSubtype sCurrentSubtype;

    /** 语言顺序配置（分隔线上方参与轮转、下方只能手动切）。 */
    private static volatile LangConfig sConfig;

    /** 当前中文方案：PINYIN / WUBI / null（未知）。靠观测搜狗自己的方案命令维护。 */
    private static volatile String sScheme;

    /** 推 marker 期间屏蔽自己的语言动作（marker 动了但语言不该跟着动）。 */
    private static volatile boolean sSuppress;

    private static volatile boolean sKeyGuardsInstalled;
    private static volatile boolean sPunctHooked;

    /** 最近一次按下的 / 或 \ （搜狗都产 、，反向映射靠它消歧）。 */
    private static volatile char sLastSlashKey;

    /** 最近一次提交出去的最后一个字符（智能编号要判断"前一个是数字"）。 */
    private static volatile char sLastCommittedChar;

    /** 本次 Shift 是否被用于输入大写字母（用于吞掉随后那次 Shift 抬起）。 */
    private static volatile boolean sShiftUsedForLetter;

    /**
     * 每个字母键的"大小写意图"（U=大写 l=小写），用于把拼音串/提交内容的大小写还原。
     *
     * <p>因为要让搜狗把大写字母当拼音收进去，我们必须把 Shift 剥掉（否则它直接上屏），
     * 于是它的拼音串会变成小写 —— 这里按用户实际按键把它还原。
     */
    private static final StringBuilder sCaseMask = new StringBuilder();

    private static boolean isLetterKey(int kc) {
        return kc >= KeyEvent.KEYCODE_A && kc <= KeyEvent.KEYCODE_Z;
    }

    private static boolean looksLikeLetters(CharSequence t) {
        if (t == null || t.length() == 0) return false;
        for (int i = 0; i < t.length(); i++) {
            final char c = t.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return false;
        }
        return true;
    }

    /**
     * 按记录的按键意图还原大小写；无需改动则返回 {@code null}。
     *
     * <p>要求记录长度与字母数<b>完全相等</b>：对不上说明记录不是这一段的
     * （上一段残留），此时一个字都不动 —— 宁可不大写，也不能把无关内容改错。
     */
    private static String fixCase(CharSequence t) {
        final int n = t.length();
        if (sCaseMask.length() != n) return null;
        boolean changed = false;
        final StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            final boolean upper = sCaseMask.charAt(i) == 'U';
            if (upper != Character.isUpperCase(c)) {
                c = upper ? Character.toUpperCase(c) : Character.toLowerCase(c);
                changed = true;
            }
            sb.append(c);
        }
        return changed ? sb.toString() : null;
    }

    /** 复制一个 KeyEvent，但抹掉 Shift 修饰（让搜狗把它当小写字母进拼音串）。 */
    private static KeyEvent withoutShift(final KeyEvent src) {
        final int meta = src.getMetaState()
                & ~(KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON
                    | KeyEvent.META_SHIFT_RIGHT_ON);
        final KeyEvent mod = new KeyEvent(src.getDownTime(), src.getEventTime(), src.getAction(),
                src.getKeyCode(), src.getRepeatCount(), meta, src.getDeviceId(),
                src.getScanCode(), src.getFlags());
        try {
            final java.lang.reflect.Field f = KeyEvent.class.getDeclaredField("mSource");
            f.setAccessible(true);
            f.setInt(mod, src.getSource());
        } catch (Throwable ignored) {
        }
        return mod;
    }

    /** 上一次从 provider 读到的原始配置串（用于日志）。 */
    private static volatile String sLastRaw;

    /**
     * 两个"模式"（全半角 / 中英标点）的状态。
     *
     * <p>它们不占 App 界面，只由快捷键切换，所以存在**模块自己**的 SharedPreferences 里
     * （搜狗进程的 files 目录，天然持久化）；App 的 ContentProvider 只管功能开关。
     * 默认：半角、中文标点。
     */
    private static final String STATE_PREFS = "sougouext_state";
    private static final String KEY_FULL = "fullwidth";
    private static final String KEY_MODE_EN = "enPunct";

    private static volatile Boolean sFull;
    private static volatile Boolean sModeEn;

    private static android.content.SharedPreferences statePrefs() {
        try {
            final Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                return ((android.content.Context) app)
                        .getSharedPreferences(STATE_PREFS, android.content.Context.MODE_PRIVATE);
            }
        } catch (Throwable err) {
            Log.d(TAG, "statePrefs unavailable: " + err);
        }
        return null;
    }

    static boolean currentFullWidth() {
        final LangConfig cfg = sConfig;
        if (cfg == null || !cfg.fullwidth) return false;   // 功能开关关 → 恒半角
        Boolean v = sFull;
        if (v == null) {
            final android.content.SharedPreferences sp = statePrefs();
            v = sp != null && sp.getBoolean(KEY_FULL, false);
            sFull = v;
        }
        return v;
    }

    static boolean currentEnPunct() {
        final LangConfig cfg = sConfig;
        if (cfg == null || !cfg.enPunct) return false;     // 功能开关关 → 恒中文标点
        Boolean v = sModeEn;
        if (v == null) {
            final android.content.SharedPreferences sp = statePrefs();
            v = sp != null && sp.getBoolean(KEY_MODE_EN, false);
            sModeEn = v;
        }
        return v;
    }

    /** marker 推进单飞：setInputView 与 onStartInputView 会各触发一次，避免两个线程互抢。 */
    private static volatile boolean sRepositioning;

    private static volatile Context sCtx;
    private static volatile String sPkg;

    /** 搜狗界面切换方案用的 keyboardEventId。 */
    private static final int EVENT_PINYIN = 1002;
    private static final int EVENT_WUBI = 1005;

    /** 语言状态读取口：LUa 单例 + F():int（0=中文，1=英文）。 */
    private static volatile Object sLuaInstance;
    private static volatile Method sLuaGetLanguage;

    private SogouTranslator() {}

    private static volatile boolean sCommandTrace;

    /**
     * 这一段组合结束（上屏了）：清空按键意图，下一段重新记录。
     *
     * <p>必须"先用掉记录再清" —— 顺序反了就是刚记完就删，上屏永远小写（踩过）。
     */
    static void onCompositionEnded() {
        sCaseMask.setLength(0);
    }

    /** 当前 IME 服务对象（探针用）。 */
    static Object service() {
        return sService;
    }

    /** 框架最后一次下发的 subtype；还没收到回调时为 null。 */
    static InputMethodSubtype currentSubtype() {
        return sCurrentSubtype;
    }

    /** 开发期：打印每次 subtype 回调的 hash（排查多余回调时打开）。 */
    private static final boolean DEV_CB_TRACE = false;

    /** 探针用：把"这次是我们发起的"标记起来，绕过严格守卫。 */
    static void beginOurs() {
        sOurs.set(Boolean.TRUE);
    }

    static void endOurs() {
        sOurs.set(Boolean.FALSE);
    }

    /** 由 {@link BridgeHook} 在检测到 BZK 后调用，开启严格模式。 */
    public static void setStrict(boolean strict) {
        sStrict = strict;
    }

    /** 功能 S 的开关（{@link AutoPairHook} 读）。 */
    static boolean autoPairEnabled() {
        return sAutoPair;
    }

    /** 功能 S/9 的配对表（{@link AutoPairHook} 读）。 */
    static java.util.Map<Character, Character> autoPairMap() {
        return sPairMap;
    }

    /**
     * 功能 9 当前是否生效 = 功能开关开 && 状态位为真。
     *
     * <p>状态位不在 UI 上，由 Ctrl+Shift+9 临时切换并持久化，与全角/中英标点同一套机制。
     */
    static boolean physCompleteActive() {
        if (!sPhysComplete) return false;          // 功能开关关 → 忽略状态位
        Boolean v = sPhysState;
        if (v == null) {
            final android.content.SharedPreferences sp = statePrefs();
            v = sp == null || sp.getBoolean("physComplete", true);   // 默认开：开箱即用
            sPhysState = v;
        }
        return v;
    }

    /** 开发期：记录搜狗自己请求的命令 id。 */
    public static void setCommandTrace(boolean trace) {
        sCommandTrace = trace;
    }

    public static void install(XposedModule module, ClassLoader cl, Context ctx, String pkg) {
        sModule = module;
        sCtx = ctx;
        sPkg = pkg;
        try {
            final Class<?> svc = Class.forName(
                    "android.inputmethodservice.InputMethodService", false, cl);
            for (Method m : svc.getDeclaredMethods()) {
                final String name = m.getName();
                if (name.equals("setInputView")) {
                    m.setAccessible(true);
                    module.hook(m).intercept(chain -> {
                        if (sService == null) {
                            sService = chain.getThisObject();
                            Log.i(TAG, "service captured: " + sService.getClass().getName());
                            bindCommandRegistry();
                            installKeyGuards();
                            installPunctuationRewrite();
                            if (BridgeHook.DEV_PUNCT_PROBE) {
                                SogouPunctProbe.install(sModule, SogouTranslator.class.getClassLoader(),
                                        sService);
                            }
                            startConfigWatch();
                        }
                        sViewReady = true;
                        sReadyAt = SystemClock.uptimeMillis();
                        // 必须等键盘视图真正起来之后再动 subtype / marker，
                        // 否则框架会在 IME 初始化中途重建窗口（表现为"工具栏重启"）
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            reloadConfig();
                            syncMarker();
                        }, 500);
                        return chain.proceed();
                    });
                } else if (name.equals("onStartInputView") || name.equals("onStartInput")) {
                    // 每次键盘/输入会话开始都重读配置：改完设置不用重启进程，
                    // 下一次弹出键盘就会生效（setInputView 只在视图重建时才会来）
                    m.setAccessible(true);
                    module.hook(m).intercept(chain -> {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            reloadConfig();
                            syncMarker();
                        }, 300);
                        return chain.proceed();
                    });
                } else if (name.equals("onCurrentInputMethodSubtypeChanged")
                        || name.equals("changeInputMethodSubtype")) {
                    m.setAccessible(true);
                    module.hook(m).intercept(chain -> {
                        onSubtypeChanged(chain.getArg(0));
                        return chain.proceed();
                    });
                }
            }
            Log.i(TAG, "translator installed");
        } catch (Throwable err) {
            Log.w(TAG, "translator install failed: " + err);
        }
    }

    /**
     * 标点管线钩子：{@code {开关3;开关1（3 优先）} -> 开关2}。
     *
     * <p>搜狗的中文标点映射是硬编码的、发生在我们之前，所以：
     * 开关1 开 = 归一到中文标点（保持搜狗行为）；开关3 开 = 反向还原成键盘标点（ASCII）；
     * 最后再按开关2 决定全角/半角（只动 ASCII 与 FF01–FF5E 这一段，不碰中文标点）。
     */
    private static void installPunctuationRewrite() {
        final Object svc = sService;
        final XposedModule module = sModule;
        if (svc == null || module == null || sPunctHooked) return;
        sPunctHooked = true;
        try {
            final Object ic = svc.getClass().getMethod("getCurrentInputConnection").invoke(svc);
            if (ic == null) {
                Log.w(TAG, "punct: no input connection at install time");
                return;
            }
            Log.i(TAG, "punct: connection = " + ic.getClass().getName());
            Log.i(TAG, "punct: table self-check " + PunctPipeline.selfCheck());
            final java.util.Set<Method> hooked = new java.util.HashSet<>();
            for (Class<?> c = ic.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    final String n = m.getName();
                    if (!n.equals("commitText") && !n.equals("setComposingText")) continue;
                    if (m.getParameterCount() != 2) continue;
                    m.setAccessible(true);
                    if (!hooked.add(m)) continue;
                    try {
                        module.hook(m).intercept(chain -> {
                            final LangConfig cfg = sConfig;
                            if (cfg == null) return chain.proceed();
                            final Object a0 = chain.getArg(0);
                            if (!(a0 instanceof CharSequence)) return chain.proceed();
                            // 我们自己注入的提交（物理补全的闭字符）原样放行，
                            // 不让标点管线改写它 —— 否则补出来的字符会被二次变换
                            if (AutoPairHook.isInjecting()) return chain.proceed();
                            final String src = a0.toString();
                            final boolean commit = n.equals("commitText");
                            if (BridgeHook.DEV_KEY_LOG) {
                                Log.i(TAG, "IC " + n + " " + src + " mask=" + sCaseMask);
                            }
                            String out = src;
                            // 先把按键意图用掉，再清 —— 顺序反了就是"刚记录完就删掉"，
                            // 表现是上屏永远小写（踩过）。
                            if (looksLikeLetters(out)) {
                                final String fc = fixCase(out);
                                if (fc != null && !fc.equals(out)) {
                                    Log.i(TAG, "case: commit " + out + " -> " + fc);
                                    out = fc;
                                }
                            }
                            // 只有上屏才结束这一段；空格等不能中途清记录，
                            // 否则 "dance hello" 里前面那段的大写意图会被吃掉。
                            if (commit) onCompositionEnded();
                            // 语义层（顺序：斜杠键 → 英/中文标点）
                            // 搜狗把 / 和 \ 都出成 、；这里按"上一个物理按键"区分，二选一原样输出：
                            //   选 \ → 按 \ 出 \，按 / 出 、
                            //   选 /  → 按 / 出 /，按 \ 出 、
                            //   关    → 两个都出 、（英文标点模式下再由下面的分支转成 ASCII）
                            boolean slashHandled = false;
                            if (cfg.slashMode != 0 && out.indexOf('、') >= 0) {
                                final char want = (cfg.slashMode == 1) ? '/' : '\\';
                                slashHandled = true;
                                if (sLastSlashKey == want) {
                                    out = out.replace('、', want);
                                }
                                // 另一个键：保持 、
                            }
                            // 英文态一律英文标点；中文态下开关3 优先于开关1
                            final boolean english = rawLanguageState() == 1;
                            if (!slashHandled) {
                                if (currentEnPunct() || english) {
                                    final String r = PunctPipeline.toAsciiPunct(out, sLastSlashKey);
                                    if (r != null) out = r;
                                } else if (cfg.smartPunct) {
                                    final String r = PunctPipeline.toChinesePunct(out);
                                    if (r != null) out = r;
                                }
                            }
                            // 智能编号：数字后面的 。/） 用半角（1. 2) 这类编号）
                            if (cfg.smartNumbering
                                    && sLastCommittedChar >= '0' && sLastCommittedChar <= '9') {
                                if ("。".equals(out)) out = ".";
                                else if ("）".equals(out)) out = ")";
                            }
                            // 形式层：全角 / 半角
                            final boolean full = currentFullWidth();
                            final String w = full
                                    ? PunctPipeline.toFullWidth(out)
                                    : PunctPipeline.toHalfWidth(out);
                            if (w != null) out = w;
                            sLastCommittedChar = out.isEmpty() ? 0
                                    : out.charAt(out.length() - 1);
                            final Object result;
                            if (out.equals(src)) {
                                result = chain.proceed();
                            } else {
                                final Object[] args = chain.getArgs().toArray();
                                args[0] = out;
                                Log.i(TAG, "punct: " + src + " -> " + out
                                        + (full ? " [full]" : " [half]")
                                        + (slashHandled ? " [slash=" + cfg.slashMode + "]"
                                           : currentEnPunct() || english ? " [en]"
                                           : cfg.smartPunct ? " [cn]" : " [raw]"));
                                result = chain.proceed(args);
                            }
                            // 物理键盘补全：开字符上屏后，紧接着注入闭字符（功能 9）
                            if (commit) {
                                AutoPairHook.maybeInjectPair(chain.getThisObject(), out);
                            }
                            return result;
                        });
                        Log.i(TAG, "punct hooked " + c.getSimpleName() + "#" + n);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable err) {
            Log.w(TAG, "punct install failed: " + err);
        }
    }

    /**
     * 吞掉搜狗原生的"切语言"组合键（Ctrl+Space / Ctrl+Shift）。
     *
     * <p>为什么必须做到按键这一层：实测搜狗的 Ctrl+Space **不走** `cta/dta` 命令
     * （既没有 eP.a(int) 追踪行，也没有命令级守卫的 blocked 行）——它在内部直接切语言
     * 并回写 subtype。命令级守卫拦不到，只能在这里吞键。
     *
     * <p>不影响 BZK：BZK 是 system_server 里的 input filter，先于 IME 拿到事件，
     * 它照旧按框架 subtype 切换；我们只是让搜狗**看不到**这两个组合键。
     */
    private static void installKeyGuards() {
        final Object svc = sService;
        final XposedModule module = sModule;
        if (svc == null || module == null) return;
        if (sKeyGuardsInstalled) return;
        sKeyGuardsInstalled = true;
        // 引擎类（LUU / Yja）要等搜狗自己开始输入才加载完，且只有输入法服务自己的
        // ClassLoader 看得到，所以在这里懒安装。装不上也不影响其它功能。
        AutoPairHook.install(module, svc.getClass().getClassLoader());
        final java.util.Set<Method> hooked = new java.util.HashSet<>();
        for (Class<?> c = svc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                final String n = m.getName();
                if (!n.equals("onKeyDown") && !n.equals("onKeyUp")) continue;
                if (m.getParameterCount() != 2 || m.getReturnType() != boolean.class) continue;
                m.setAccessible(true);
                if (!hooked.add(m)) continue;
                try {
                    module.hook(m).intercept(chain -> {
                        if (BridgeHook.DEV_KEY_LOG) {
                            final Object k = chain.getArg(0);
                            final Object e = chain.getArg(1);
                            if (e instanceof KeyEvent) {
                                final KeyEvent ev = (KeyEvent) e;
                                Log.i(TAG, "KEY " + n + " " + KeyEvent.keyCodeToString(ev.getKeyCode())
                                        + " meta=0x" + Integer.toHexString(ev.getMetaState())
                                        + " repeat=" + ev.getRepeatCount());
                            }
                        }
                        final boolean down = n.equals("onKeyDown");
                        // 标记"正在处理硬件按键"：软键盘不走这两个 hook，所以它能把
                        // 物理键盘的提交与软键盘的提交区分开（物理补全只认前者）
                        AutoPairHook.markHardwareKey(down);
                        if (chain.getArg(1) instanceof KeyEvent) {
                            final KeyEvent kev = (KeyEvent) chain.getArg(1);
                            final int u = kev.getUnicodeChar();
                            // 记录 / 与 \ （两者搜狗都产 、，反向还原时靠它消歧）
                            if (u == '/' || u == '\\') sLastSlashKey = (char) u;

                            final int kc2 = kev.getKeyCode();
                            final int meta2 = kev.getMetaState();
                            // 只在"按下"记一次！这三行同时挂在 onKeyDown / onKeyUp 上，
                            // 两边都记会让一个字母变成两个（Dance -> UllllUllll），
                            // 长度对不上后大小写还原就全错（表现：从某个字母起变回小写）。
                            if (down && isLetterKey(kc2)) {
                                final boolean sh = (meta2 & KeyEvent.META_SHIFT_ON) != 0;
                                sCaseMask.append(sh ? 'U' : 'l');
                                if (sCaseMask.length() > 32) sCaseMask.deleteCharAt(0);
                            }
                            final boolean ctrl = (meta2 & KeyEvent.META_CTRL_ON) != 0;
                            final boolean shift = (meta2 & KeyEvent.META_SHIFT_ON) != 0;
                            // Shift+Space → 全角/半角
                            if (kc2 == KeyEvent.KEYCODE_SPACE && shift && !ctrl) {
                                if (down && kev.getRepeatCount() == 0) {
                                    final boolean nv = !currentFullWidth();
                                    sFull = nv;
                                    final android.content.SharedPreferences sp = statePrefs();
                                    if (sp != null) sp.edit().putBoolean(KEY_FULL, nv).apply();
                                    Log.i(TAG, "hotkey Shift+Space -> fullwidth=" + nv + " (saved)");
                                    banner("全角模式：" + (nv ? "开" : "关"));
                                }
                                return true;
                            }
                            // 大写字母进拼音栏：中文态下剥掉 Shift 交给搜狗，
                            // 否则它会把大写字母直接上屏，导致 "Dance" 只剩 "ance" 参与候选
                            final LangConfig ccfg = sConfig;
                            if (ccfg != null && ccfg.capitalInPinyin && !ctrl
                                    && shift && rawLanguageState() != 1
                                    && kc2 >= KeyEvent.KEYCODE_A && kc2 <= KeyEvent.KEYCODE_Z) {
                                sShiftUsedForLetter = true;
                                Log.i(TAG, "capital: strip shift -> "
                                        + KeyEvent.keyCodeToString(kc2));
                                return chain.proceed(new Object[]{kc2, withoutShift(kev)});
                            }
                            // 这次 Shift 被用于字母：吞掉它的抬起，免得搜狗当成"Shift 单击切语言"
                            // 注意：Shift 抬起事件的 meta 里通常已经不带 SHIFT 位，所以这里只看键码
                            if (sShiftUsedForLetter && !down
                                    && (kc2 == KeyEvent.KEYCODE_SHIFT_LEFT
                                        || kc2 == KeyEvent.KEYCODE_SHIFT_RIGHT)) {
                                sShiftUsedForLetter = false;
                                Log.i(TAG, "capital: swallow shift up");
                                return true;
                            }
                            // Ctrl+. → 中英文标点
                            if (kc2 == KeyEvent.KEYCODE_PERIOD && ctrl) {
                                if (down && kev.getRepeatCount() == 0) {
                                    final boolean nv = !currentEnPunct();
                                    sModeEn = nv;
                                    final android.content.SharedPreferences sp = statePrefs();
                                    if (sp != null) sp.edit().putBoolean(KEY_MODE_EN, nv).apply();
                                    Log.i(TAG, "hotkey Ctrl+. -> enPunct=" + nv + " (saved)");
                                    banner("标点模式：" + (nv ? "英文标点" : "中文标点"));
                                }
                                return true;
                            }
                            // Ctrl+Shift+9 → 物理键盘补全的状态位（功能开关 9 之下的临时开关）
                            if (kc2 == KeyEvent.KEYCODE_9 && ctrl && shift) {
                                if (down && kev.getRepeatCount() == 0) {
                                    final boolean nv = !physCompleteActive();
                                    sPhysState = nv;
                                    final android.content.SharedPreferences sp = statePrefs();
                                    if (sp != null) sp.edit().putBoolean("physComplete", nv).apply();
                                    Log.i(TAG, "hotkey Ctrl+Shift+9 -> physComplete=" + nv + " (saved)");
                                    banner("物理键盘补全：" + (nv ? "开" : "关"));
                                }
                                return true;
                            }
                        }
                        if (!sStrict) return chain.proceed();
                        final Object kcArg = chain.getArg(0);
                        final Object evArg = chain.getArg(1);
                        if (!(kcArg instanceof Integer) || !(evArg instanceof KeyEvent)) {
                            return chain.proceed();
                        }
                        final int kc = (Integer) kcArg;
                        final int meta = ((KeyEvent) evArg).getMetaState();
                        final boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;
                        if (ctrl && (kc == KeyEvent.KEYCODE_SPACE
                                || kc == KeyEvent.KEYCODE_SHIFT_LEFT
                                || kc == KeyEvent.KEYCODE_SHIFT_RIGHT)) {
                            Log.i(TAG, "strict: blocked native switch key "
                                    + KeyEvent.keyCodeToString(kc));
                            return true;             // 吞掉，不给搜狗处理
                        }
                        return chain.proceed();
                    });
                    Log.i(TAG, "strict key guard on " + c.getSimpleName() + "#" + n);
                } catch (Throwable err) {
                    Log.w(TAG, "key guard failed: " + err);
                }
            }
        }
    }

    /**
     * 查询 App 的 ContentProvider 用的 resolver。
     *
     * <p>必须用**本 App（搜狗）自己的** context：系统 context 的 callingPackage 是 "android"，
     * 与调用方 uid 不匹配，provider 会抛
     * {@code SecurityException: Given calling package android does not match caller's uid}。
     */
    private static android.content.ContentResolver providerResolver() {
        try {
            final Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                return ((android.content.Context) app).getContentResolver();
            }
        } catch (Throwable err) {
            Log.d(TAG, "currentApplication unavailable: " + err);
        }
        return sCtx != null ? sCtx.getContentResolver() : null;
    }

    /**
     * 定时轮询配置（2 秒）。
     *
     * <p>界面上的开关/顺序改完，最多 2 秒就生效，不用等下一次输入会话、也不用重启进程。
     * 签名没变时 {@link #reloadConfig()} 什么都不做，开销可以忽略。
     */
    private static void startConfigWatch() {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                reloadConfig();
                handler.postDelayed(this, 2000);
            }
        }, 2000);
        Log.i(TAG, "config watch started (2s)");
    }

    /** 读（并应用）语言顺序配置；注入是幂等的，只有变化时才真的写 IMMS。 */
    private static void reloadConfig() {
        final XposedModule m = sModule;
        if (m == null) return;
        // 优先走 App 的 ContentProvider（不依赖 XposedService），失败再退回 remote prefs
        final String raw = LangConfig.readProvider(providerResolver());
        if (raw != null && !raw.equals(sLastRaw)) {
            sLastRaw = raw;
            Log.i(TAG, "provider raw = " + raw);
        }
        LangConfig cfg = LangConfig.parseDump(raw);
        if (cfg == null) cfg = LangConfig.load(m);
        final boolean changed = sConfig == null || !cfg.signature().equals(sConfig.signature());
        sConfig = cfg;
        // 同步给 hook 用（hook 可能在非主线程被调用，不能让它直接读 sConfig）
        sAutoPair = cfg.autoPair;
        sPairMap = cfg.pairMap;
        sPhysComplete = cfg.physComplete;
        // 重试安装自动配对 hook：引擎类（UU 是输入会话类）要等真正开始输入才加载完，
        // 首次在 installKeyGuards 里装时可能还拿不到。已装上的会在内部直接返回。
        if (sService != null && sModule != null) {
            AutoPairHook.install(sModule, sService.getClass().getClassLoader());
        }
        if (changed) {
            if (sCtx != null && sPkg != null) {
                Log.i(TAG, "config -> " + cfg.signature() + " | "
                        + SubtypeInjector.apply(sCtx, sPkg, cfg));
            }
            syncMarker();
        }
    }

    // ------------------------------------------------------------------
    // 命令注册表（WO.e → eP，SparseArray b）
    // ------------------------------------------------------------------

    private static void bindCommandRegistry() {
        try {
            sWo = field(sService, "b");                 // coa.b : WO
            if (sWo == null) return;
            sEp = field(sWo, "e");                      // WO.e  : eP
            if (sEp == null) {
                Log.w(TAG, "command registry (WO.e) not found");
                return;
            }
            final Object sparse = field(sEp, "b");      // eP.b : SparseArray<hP>
            if (sparse == null) return;
            final int n = (Integer) sparse.getClass().getMethod("size").invoke(sparse);
            final Method keyAt = sparse.getClass().getMethod("keyAt", int.class);
            final Method valueAt = sparse.getClass().getMethod("valueAt", int.class);
            for (int i = 0; i < n; i++) {
                final int id = (Integer) keyAt.invoke(sparse, i);
                final Object cmd = valueAt.invoke(sparse, i);
                if (cmd == null) continue;
                final String cn = cmd.getClass().getSimpleName();
                sCommands.put(cn, cmd);
                if (sCommandTrace) {
                    Log.i(TAG, "cmd id=" + id + " class=" + cn + " name=" + cmdName(cmd));
                }
                if (CMD_CLASS_ZH_TO_EN.equals(cn)) {
                    sCmdZhToEn = id;
                    sCmdZhToEnObj = cmd;
                } else if (CMD_CLASS_EN_TO_ZH.equals(cn)) {
                    sCmdEnToZh = id;
                    sCmdEnToZhObj = cmd;
                }
            }
            Log.i(TAG, "language commands: zh->en=" + sCmdZhToEn + " en->zh=" + sCmdEnToZh
                    + " (registry size=" + n + ")");
            if (sCommandTrace) {
                traceCommandRequests();
            }
            installLanguageGuards();      // 常驻：是否生效由开关在运行时判定
            // 自己执行时用缓存的方法（不走 eP.a(int)），与守卫同一份 Method
            if (sCmdZhToEnObj != null) {
                sRunZhToEn = execMethod(sCmdZhToEnObj, sWo);
            }
            if (sCmdEnToZhObj != null) {
                sRunEnToZh = execMethod(sCmdEnToZhObj, sWo);
            }
        } catch (Throwable err) {
            Log.w(TAG, "bindCommandRegistry failed: " + err);
        }
    }

    /** 开发期：读一条命令的名字（getId()）。 */
    private static String cmdName(Object cmd) {
        try {
            final Method g = cmd.getClass().getMethod("getId");
            g.setAccessible(true);
            return String.valueOf(g.invoke(cmd));
        } catch (Throwable err) {
            return "?";
        }
    }

    /** 开发期：记录 eP.a(int) 的每次请求（含搜狗自己按键处理发起的）。 */
    private static void traceCommandRequests() {
        final XposedModule module = sModule;
        if (module == null) return;
        try {
            final Method lookup = sEp.getClass().getDeclaredMethod("a", int.class);
            lookup.setAccessible(true);
            module.hook(lookup).intercept(chain -> {
                Log.i(TAG, "sogou requested command " + chain.getArg(0));
                return chain.proceed();
            });
            Log.i(TAG, "command trace installed");
        } catch (Throwable err) {
            Log.w(TAG, "command trace failed: " + err);
        }
    }

    /**
     * 开发期观察：只记录搜狗自己调这条命令时传的 Bundle 和调用者，不拦截。
     *
     * <p>目的：搞清 UI 上"切五笔"与快捷键"中→英"共用 {@code -2} 时，区别是不是在 Bundle 参数里。
     */
    private static void observeLanguageCommand(Object cmd, String label) {
        final XposedModule module = sModule;
        final Object wo = sWo;
        if (cmd == null || module == null || wo == null) return;
        try {
            final Method run = cmd.getClass().getMethod("a", wo.getClass(), Bundle.class);
            run.setAccessible(true);
            module.hook(run).intercept(chain -> {
                Log.i(TAG, "observe " + label + " bundle=" + dumpBundle(chain.getArg(1))
                        + " caller=" + caller());
                return chain.proceed();
            });
            Log.i(TAG, "observer on " + label);
        } catch (Throwable err) {
            Log.w(TAG, "observer failed on " + label + ": " + err);
        }
    }

    private static String dumpBundle(Object arg) {
        if (!(arg instanceof Bundle)) return String.valueOf(arg);
        final Bundle b = (Bundle) arg;
        if (b.isEmpty()) return "{}";
        final StringBuilder sb = new StringBuilder("{");
        for (String k : b.keySet()) {
            sb.append(k).append('=').append(b.get(k)).append(' ');
        }
        return sb.append('}').toString();
    }

    /** 第一个不在本模块 / 反射 / libxposed 里的调用者。 */
    private static String caller() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            final String cn = e.getClassName();
            if (cn.startsWith("moe.lovefirefly") || cn.startsWith("java.")
                    || cn.startsWith("io.github") || cn.startsWith("android.os.Handler")
                    || cn.startsWith("android.os.Looper")) continue;
            return cn + "#" + e.getMethodName();
        }
        return "?";
    }

    /**
     * 给搜狗的中↔英命令装上守卫：**硬键盘那条路不许自己切语言**。
     *
     * <p>实测搜狗自己有这些入口：
     * <ul>
     *   <li>硬键盘快捷键（含物理 Ctrl+Shift 以及搜狗映射的其它组合）→ {@code cta/dta}
     *       且 {@code Bundle=null} —— <b>拦掉</b>，语言只由框架 subtype 决定；</li>
     *   <li>搜狗界面按钮 → {@code cta} + {@code {keyboardEventId=…}}（方案键 1002/1005、
     *       软键盘中/英键 1007）—— <b>放行</b>。这是"用户手动"那条轴，marker 随后会被
     *       {@link #syncMarker()} 摆正，不影响 BZK 的下一次切换；</li>
     *   <li>{@code kwa/hwa}：曾经想一起拦，实测会**导致软键盘弹不出来**（键盘显示也走它），
     *       所以不碰。</li>
     * </ul>
     *
     * <p>判定用**运行时命令实例**而不是钩子来源：几个命令可能共享同一个执行方法。
     */
    private static void installLanguageGuards() {
        final XposedModule module = sModule;
        final Object wo = sWo;
        if (module == null || wo == null) return;
        final java.util.Set<Method> hooked = new java.util.HashSet<>();
        for (String cn : new String[]{"cta", "dta"}) {
            final Object cmd = sCommands.get(cn);
            if (cmd == null) continue;
            final Method run;
            try {
                run = cmd.getClass().getMethod("a", wo.getClass(), Bundle.class);
                run.setAccessible(true);
            } catch (Throwable err) {
                Log.w(TAG, "guard: no exec method for " + cn + ": " + err);
                continue;
            }
            if (!hooked.add(run)) continue;          // 共享同一个方法 → 只钩一次
            module.hook(run).intercept(chain -> {
                if (sOurs.get()) return chain.proceed();          // 本模块自己发起的
                if (!sStrict) return chain.proceed();             // 开关关闭：搜狗自己的切换照旧
                final Object arg = chain.getArg(1);
                final boolean fromUi = (arg instanceof Bundle)
                        && ((Bundle) arg).containsKey("keyboardEventId");
                if (fromUi) {
                    final int ev = ((Bundle) arg).getInt("keyboardEventId", Integer.MIN_VALUE);
                    Log.i(TAG, "strict: allow UI switch " + dumpBundle(arg));
                    final Object r = chain.proceed();
                    if (ev == EVENT_PINYIN) sScheme = LangSpec.PINYIN;
                    else if (ev == EVENT_WUBI) sScheme = LangSpec.WUBI;
                    syncMarker();
                    return r;
                }
                Log.i(TAG, "strict: blocked sogou chord switch (" + dumpBundle(arg) + ")");
                return defaultValue(run.getReturnType());
            });
            Log.i(TAG, "strict guard on " + cn + " (" + cmd.getClass().getSimpleName() + ")");
        }
    }

    private static Method execMethod(Object cmd, Object wo) {
        if (cmd == null || wo == null) return null;
        try {
            final Method m = cmd.getClass().getMethod("a", wo.getClass(), Bundle.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable err) {
            Log.w(TAG, "execMethod failed: " + err);
            return null;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    /** 执行一条语言命令（空参数）。 */
    private static boolean executeLanguageCommand(int id) {
        return executeCommand(id, new Bundle());
    }

    /** 执行一条语言命令：hP.a(WO, Bundle)（优先用枚举时缓存的方法，退化时查注册表）。 */
    private static boolean executeCommand(int id, Bundle args) {
        final Object wo = sWo;
        if (wo == null || id == Integer.MIN_VALUE) return false;

        Object cmd;
        Method run;
        if (id == sCmdZhToEn) {
            cmd = sCmdZhToEnObj;
            run = sRunZhToEn;
        } else if (id == sCmdEnToZh) {
            cmd = sCmdEnToZhObj;
            run = sRunEnToZh;
        } else {
            cmd = null;
            run = null;
        }
        try {
            if (cmd == null || run == null) {
                // 退化路径：直接查注册表（严格模式下这条路径不受守卫保护，仅作兜底）
                final Object ep = sEp;
                if (ep == null) return false;
                final Method lookup = ep.getClass().getDeclaredMethod("a", int.class);
                lookup.setAccessible(true);
                cmd = lookup.invoke(ep, id);
                if (cmd == null) {
                    Log.w(TAG, "command " + id + " not registered");
                    return false;
                }
                run = cmd.getClass().getMethod("a", wo.getClass(), Bundle.class);
                run.setAccessible(true);
            }
            sOurs.set(Boolean.TRUE);
            try {
                run.invoke(cmd, wo, args);
            } finally {
                sOurs.set(Boolean.FALSE);
            }
            Log.i(TAG, "executed command " + id + " (" + cmd.getClass().getSimpleName()
                    + ") args=" + dumpBundle(args));
            return true;
        } catch (Throwable err) {
            Log.w(TAG, "executeLanguageCommand failed: " + err + " cause=" + err.getCause());
            return false;
        }
    }

    // ------------------------------------------------------------------

    private static void onSubtypeChanged(Object subtype) {
        if (subtype instanceof InputMethodSubtype) sCurrentSubtype = (InputMethodSubtype) subtype;
        final int hash = (subtype instanceof InputMethodSubtype) ? subtype.hashCode() : 0;
        if (DEV_CB_TRACE) Log.i(TAG, "cb hash=" + hash);
        synchronized (SogouTranslator.class) {
            if (hash == 0) return;
            if (sLastSubtypeHash == 0) {
                final boolean justReady = SystemClock.uptimeMillis() - sReadyAt < 2000;
                sLastSubtypeHash = hash;
                if (justReady) {
                    Log.i(TAG, "subtype baseline (late) hash=" + hash);
                    return;
                }
            } else if (hash == sLastSubtypeHash) {
                return;                      // 同一次变化的重复回调
            } else {
                sLastSubtypeHash = hash;
            }

            if (!sViewReady || sService == null) {
                Log.i(TAG, "subtype changed -> " + hash + " (keyboard not ready, skip)");
                return;
            }
        }
        // 严格模式下每次按键只有一个 subtype 回调（搜狗自己那次已被守卫拦掉），
        // 所以不需要任何抑制窗口：立即按真实语言状态幂等处理，连按也一一对应。
        if (sSuppress) {
            Log.i(TAG, "marker changed while re-positioning, no language action");
            return;
        }
        applySubtype(subtype);
    }

    /**
     * marker（框架当前 subtype）→ 搜狗动作。
     *
     * <p>映射（实测）：拼音 = {@code cta + keyboardEventId=1002}；五笔 = {@code cta + 1005}；
     * 英语 = {@code cta} 空参数（从五笔出发要先切回拼音，空参数从五笔只能到拼音）。
     * 幂等：中/英看 {@code F()}，拼音/五笔看跟踪到的方案。
     */
    private static void applySubtype(Object subtype) {
        final String id = LangSpec.identify(
                subtype instanceof InputMethodSubtype ? (InputMethodSubtype) subtype : null);
        if (id == null) {
            Log.i(TAG, "marker -> not our subtype, ignore");
            return;
        }
        final int f = rawLanguageState();
        final String scheme = sScheme;
        switch (id) {
            case LangSpec.PINYIN:
                if (f == 0 && LangSpec.PINYIN.equals(scheme)) {
                    Log.i(TAG, "marker pinyin: already pinyin -> no action");
                    return;
                }
                Log.i(TAG, "marker pinyin (f=" + f + " scheme=" + scheme + ")");
                execScheme(LangSpec.PINYIN);
                return;
            case LangSpec.WUBI:
                if (f == 0 && LangSpec.WUBI.equals(scheme)) {
                    Log.i(TAG, "marker wubi: already wubi -> no action");
                    return;
                }
                Log.i(TAG, "marker wubi (f=" + f + " scheme=" + scheme + ")");
                execScheme(LangSpec.WUBI);
                return;
            case LangSpec.EN:
                if (f == 1) {
                    Log.i(TAG, "marker en: already english -> no action");
                    return;
                }
                Log.i(TAG, "marker en (f=" + f + " scheme=" + scheme + ")");
                execEnglish();
                return;
            default:
        }
    }

    /** 切到指定中文方案：cta + keyboardEventId。 */
    private static void execScheme(String scheme) {
        final int id = LangSpec.PINYIN.equals(scheme) ? EVENT_PINYIN : EVENT_WUBI;
        final Bundle args = new Bundle();
        args.putInt("keyboardEventId", id);
        if (executeCommand(sCmdZhToEn, args)) {
            sScheme = scheme;
        }
    }

    /** 切到英文：从五笔出发必须先回拼音（实测空参数从五笔只能到拼音）。 */
    private static void execEnglish() {
        if (LangSpec.WUBI.equals(sScheme)) {
            execScheme(LangSpec.PINYIN);
        }
        if (executeCommand(sCmdZhToEn, new Bundle()) && rawLanguageState() == 1) {
            Log.i(TAG, "english ok");
            return;
        }
        // 兜底：先显式回拼音再切一次
        execScheme(LangSpec.PINYIN);
        executeCommand(sCmdZhToEn, new Bundle());
    }

    /**
     * 让 marker 指向"真实语言"对应的位置。
     *
     * <p>规则：真实语言在上方轮转集合里 → marker 指向它；在下方的（例如五笔）
     * → marker 停在<b>最后一项</b>，这样 BZK 的下一次 next 正好绕回<b>第一项</b>。
     */
    private static void syncMarker() {
        final LangConfig cfg = sConfig;
        if (cfg == null || sService == null) return;
        if (cfg.rotation().isEmpty()) return;
        final int f = rawLanguageState();
        final String real = (f == 1) ? LangSpec.EN
                : (sScheme != null ? sScheme : LangSpec.PINYIN);
        final String want = cfg.inRotation(real) ? real : cfg.lastRotation();
        if (want == null) return;
        final String cur = LangSpec.identify(sCurrentSubtype);
        if (want.equals(cur)) return;
        Log.i(TAG, "sync marker: real=" + real + " cur=" + cur + " -> want=" + want);
        repositionTo(want);
    }

    /** 用公开 API 把 marker 前进到目标（期间屏蔽自己的语言动作）。 */
    private static void repositionTo(final String targetId) {
        final Object svc = sService;
        if (svc == null) return;
        synchronized (SogouTranslator.class) {
            if (sRepositioning) return;          // 已有一个在推，别抢
            sRepositioning = true;
        }
        sSuppress = true;
        final Thread t = new Thread(() -> {
            try {
                final Method m = svc.getClass().getMethod(
                        "switchToNextInputMethod", boolean.class);
                m.setAccessible(true);
                for (int i = 0; i < 6; i++) {
                    if (targetId.equals(LangSpec.identify(sCurrentSubtype))) {
                        Log.i(TAG, "marker repositioned to " + targetId + " in " + i + " step(s)");
                        return;
                    }
                    m.invoke(svc, true);
                    Thread.sleep(250);
                }
                Log.w(TAG, "marker reposition to " + targetId + " gave up");
            } catch (Throwable err) {
                Log.w(TAG, "marker reposition failed: " + err);
            } finally {
                sSuppress = false;
                sRepositioning = false;
            }
        }, "sogou-marker");
        t.setDaemon(true);
        t.start();
    }

    /** 读搜狗当前语言：LUa.B() 单例的 F()，0=中文 1=英文；读不到返回 null。 */
    private static Boolean readEnglishState() {
        final int v = rawLanguageState();
        return v == Integer.MIN_VALUE ? null : (v == 1);
    }

    /** LUa.F() 的原始 int（探针用）；读不到返回 Integer.MIN_VALUE。 */
    static int rawLanguageState() {
        try {
            if (sLuaGetLanguage == null) {
                final Object svc = sService;
                if (svc == null) return Integer.MIN_VALUE;
                final Class<?> lua = Class.forName("LUa", false,
                        svc.getClass().getClassLoader());
                sLuaInstance = lua.getMethod("B").invoke(null);
                final Method f = lua.getDeclaredMethod("F");
                f.setAccessible(true);
                sLuaGetLanguage = f;
                Log.i(TAG, "language state getter bound: LUa.F()");
            }
            final Object v = sLuaGetLanguage.invoke(sLuaInstance);
            return (v instanceof Integer) ? (Integer) v : Integer.MIN_VALUE;
        } catch (Throwable err) {
            Log.d(TAG, "rawLanguageState unavailable: " + err);
            return Integer.MIN_VALUE;
        }
    }

    // ------------------------------------------------------------------
    // 兜底：合成 Shift 单击
    // ------------------------------------------------------------------

    private static void shiftTap() {
        final Object svc = sService;
        if (svc == null) return;
        final int dev = findKeyboardDeviceId();
        final int scan = 42;                     // SHIFT_LEFT
        final Handler handler = new Handler(Looper.getMainLooper());
        final long downTime = SystemClock.uptimeMillis();
        handler.post(() -> send(svc, downTime, downTime, KeyEvent.ACTION_DOWN, scan, dev));
        handler.postDelayed(() -> send(svc, downTime, downTime + 60,
                KeyEvent.ACTION_UP, scan, dev), 60);
    }

    private static void send(Object svc, long downTime, long eventTime, int action,
            int scan, int dev) {
        try {
            final KeyEvent ev = new KeyEvent(downTime, eventTime, action,
                    KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0, dev, scan, KeyEvent.FLAG_FROM_SYSTEM);
            final Field src = KeyEvent.class.getDeclaredField("mSource");
            src.setAccessible(true);
            src.setInt(ev, InputDevice.SOURCE_KEYBOARD);

            final Method m = Class.forName("android.inputmethodservice.InputMethodService")
                    .getMethod(action == KeyEvent.ACTION_DOWN ? "onKeyDown" : "onKeyUp",
                            int.class, KeyEvent.class);
            m.invoke(svc, KeyEvent.KEYCODE_SHIFT_LEFT, ev);
            Log.i(TAG, "shift " + (action == KeyEvent.ACTION_DOWN ? "DOWN" : "UP")
                    + " sent (dev=" + dev + ")");
        } catch (Throwable err) {
            Log.w(TAG, "shift dispatch failed: " + err + " cause=" + err.getCause());
        }
    }

    /** 真实键盘设备（id 随重连变化，必须动态找）。 */
    private static int findKeyboardDeviceId() {
        try {
            for (int id : InputDevice.getDeviceIds()) {
                if (id < 0) continue;
                final InputDevice d = InputDevice.getDevice(id);
                if (d == null || d.isVirtual()) continue;
                final String name = String.valueOf(d.getName()).toLowerCase();
                if (!name.contains("keyboard") || name.contains("touchpad")) continue;
                if ((d.getSources() & InputDevice.SOURCE_KEYBOARD) == 0) continue;
                return id;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 快捷键切换后的状态提示。
     *
     * <p>不用 Toast：实测系统会拦（{@code NotificationService: Suppressing toast from package
     * com.sohu.inputmethod.sogou.oem by user request} —— Android 11+ 起 Toast 跟随应用通知设置，
     * 而搜狗的通知被关了）。改成在**输入法自己的窗口**里贴一条小横幅，1.2 秒后移除：
     * 不依赖任何权限，也拦不掉。失败时退回 Toast。
     */
    private static void banner(final String text) {
        final Object svc = sService;
        if (svc == null) {
            Log.w(TAG, "banner: no service");
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (!(svc instanceof android.inputmethodservice.InputMethodService)) {
                    toast(text);
                    return;
                }
                final android.inputmethodservice.InputMethodService ims =
                        (android.inputmethodservice.InputMethodService) svc;
                final android.app.Dialog dialog = ims.getWindow();
                if (dialog == null || dialog.getWindow() == null) {
                    toast(text);
                    return;
                }
                final android.view.View decor = dialog.getWindow().getDecorView();
                final float density = ims.getResources().getDisplayMetrics().density;
                final int padH = (int) (14 * density);
                final int padV = (int) (7 * density);

                final android.widget.TextView tv = new android.widget.TextView(ims);
                tv.setText(text);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
                tv.setTextColor(0xFFFFFFFF);
                tv.setBackgroundColor(0xCC202020);
                tv.setPadding(padH, padV, padH, padV);

                // 模仿系统 toast 的位置：贴底、水平居中，离屏幕底部约 12% 高度
                final int screenH = ims.getResources().getDisplayMetrics().heightPixels;
                final int y = (int) (screenH * 0.12f);

                final android.widget.PopupWindow pw = new android.widget.PopupWindow(tv,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false);
                pw.setOutsideTouchable(false);
                pw.setFocusable(false);
                pw.showAtLocation(decor,
                        android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL, 0, y);
                Log.i(TAG, "banner: " + text + " (y=" + y + "/" + screenH + ")");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        pw.dismiss();
                    } catch (Throwable ignored) {
                    }
                }, 1200);
            } catch (Throwable err) {
                Log.w(TAG, "banner failed: " + err);
                toast(text);
            }
        });
    }

    /** 兜底：系统通知没被关时用 Toast。 */
    private static void toast(final String text) {
        try {
            final android.content.Context ctx = sCtx;
            if (ctx == null) return;
            android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable err) {
            Log.w(TAG, "toast failed: " + err);
        }
    }

    /** 字段可能在父类上（SogouIME 是空壳，字段都在 coa），沿继承链找。 */
    private static Object field(Object obj, String name) {
        if (obj == null) return null;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                // 继续往上
            } catch (Throwable err) {
                Log.d(TAG, "field " + name + " failed: " + err);
                return null;
            }
        }
        return null;
    }
}
