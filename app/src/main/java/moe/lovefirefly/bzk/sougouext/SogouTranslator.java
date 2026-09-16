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
        final LangConfig cfg = LangConfig.load(m);
        final boolean changed = sConfig == null || !cfg.signature().equals(sConfig.signature());
        sConfig = cfg;
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
