package moe.lovefirefly.bzk.sogouoemext;

import android.util.Log;
import android.view.inputmethod.InputConnection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 功能 S：引号/括号自动补全。
 *
 * <p>搜狗的自动配对只有<b>一个咽喉点</b>（4 条调用路径 —— {@code UU}/{@code WU}/{@code YU}/{@code bV}
 * 的 {@code d(...)} —— 全部汇聚到它）：
 *
 * <pre>
 *   UU;->a(Landroid/view/inputmethod/InputConnection;Ljava/lang/CharSequence;IZ)Z
 * </pre>
 *
 * <p>它返回 {@code true} 表示"配对已由我提交"，返回 {@code false} 表示"我没配对，你自己提交单字符"
 * —— 后者正是原生"没找到配对"的语义，调用方随即按 {@code length==1} 提交原始序列。
 *
 * <p>因此本类挂两个各司其职的小 hook，<b>都不构造搜狗的内部状态</b>：
 *
 * <ol>
 *   <li><b>总闸</b>（{@code UU.a}）：S 关时直接返回 {@code false}，跳过整段配对。
 *       必须挂在最外层 —— 因为即使查找返回 null，外层对 {@code '［'} 还有个硬编码兜底会照样配对。</li>
 *   <li><b>自定义表</b>（{@code Yja.a(CharSequence)Yja$b}，真正查表的那一层）：
 *       命中自定义配对串时，造一个 {@code Yja$b(open, close, open)} 交回给搜狗，
 *       由它自己提交并定位光标（复用原生逻辑，比我们自己算绝对光标位置可靠）。
 *       未命中一律放行，所以自定义表是<b>叠加</b>而非替换。</li>
 * </ol>
 */
final class AutoPairHook {

    private static final String TAG = "BZK-SogouOEMExt";

    private static volatile boolean sGateInstalled;
    private static volatile boolean sTableInstalled;
    private static volatile boolean sWarned;

    /** 缓存 {@code Yja$b(String,String,String)} 构造器。 */
    private static volatile Constructor<?> sPairCtor;

    private AutoPairHook() {}

    /**
     * 安装两个 hook。
     *
     * @param cl 必须是<b>输入法服务自己的</b> ClassLoader（引擎类只在那里可见）
     */
    static void install(XposedModule module, ClassLoader cl) {
        installGate(module, cl);
        installTable(module, cl);
        installQuoteFlag(module, cl);    // ④ 引号标志位
    }

    /**
     * 中文引号是"按一次翻一格"的开关：搜狗的 {@code KG.d(I)I}（键码 → 中文标点）用一对
     * boolean 决定这次出开引号还是闭引号，每调一次翻一格。
     *
     * <p>我们替它把配对补全了（一次塞进开+闭两个字），却只让它翻了一格 ——
     * 于是下一次按键落在"闭"的奇偶上，只吐出一个 {@code ”}。
     *
     * <p>补偿办法：配对补完之后，<b>用同一个方法再翻一格</b>。{@code KG.d(34)} / {@code KG.d(39)}
     * 只借它的副作用（翻标志位），返回的那个字符丢掉。
     */
    private static volatile boolean sQuoteFlagInstalled;
    private static volatile Object sQuoteMapper;     // KG 实例（标志位是它的字段）
    private static volatile Method sQuoteMapMethod;  // KG.d(I)I

    /** {@code KG.d} 刚产出、还没上屏的那个开引号（用来区分"引号键翻出来的"和符号页直接输入的）。 */
    private static volatile char sPendingToggleOpen;
    private static volatile long sPendingToggleAt;

    private static void installQuoteFlag(XposedModule module, ClassLoader cl) {
        if (sQuoteFlagInstalled) return;
        try {
            final Class<?> cls = Class.forName("KG", false, cl);
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("d")) continue;
                // 精确：单个 int 参数、返回 int（同名还有别的重载）
                if (!matchParams(m, int.class)) continue;
                if (m.getReturnType() != int.class) continue;
                found = m;
                break;
            }
            if (found == null) {
                sQuoteFlagInstalled = true;
                warnOnce("quoteflag: KG.d(I)I not found, quote parity not balanced");
                return;
            }
            found.setAccessible(true);
            sQuoteMapMethod = found;
            module.hook(found).intercept(chain -> {
                // 每次按键都会路过这里 —— 顺手记住是哪个实例管着这对标志位
                sQuoteMapper = chain.getThisObject();
                final Object r = chain.proceed();
                if (r instanceof Integer) {
                    final char c = (char) ((Integer) r).intValue();
                    if (c == '\u201c' || c == '\u2018') {     // 引号键刚翻出一个开引号
                        sPendingToggleOpen = c;
                        sPendingToggleAt = System.currentTimeMillis();
                    }
                }
                if (BridgeHook.DEV_AUTOPAIR_LOG) {
                    Log.i(TAG, "quotemap: d(" + chain.getArg(0) + ") -> "
                            + (r instanceof Integer ? String.valueOf((char) ((Integer) r).intValue()) : String.valueOf(r)));
                }
                return r;
            });
            sQuoteFlagInstalled = true;
            Log.i(TAG, "quoteflag hooked " + cls.getName() + "#" + found.getName());
        } catch (ClassNotFoundException err) {
            // 键盘起来才加载，交给配置轮询重试
            if (BridgeHook.DEV_AUTOPAIR_LOG) {
                Log.i(TAG, "quoteflag: KG not loaded yet, will retry");
            }
        } catch (Throwable err) {
            sQuoteFlagInstalled = true;
            warnOnce("quoteflag install failed: " + err);
        }
    }

    /**
     * 上屏的如果是<b>引号键刚翻出来的开引号</b>，而这一下确实会补上闭字符 —— 就把标志位多翻一格。
     *
     * <p>判据三条缺一不可：
     * <ol>
     *   <li>这个开引号来自 {@code KG.d}（引号键的翻转），而不是符号页直接敲的 —— 后者没翻，补翻就错了；</li>
     *   <li>这一下真的会配对：物理键盘看功能 9，软键盘看 S；</li>
     *   <li>自定义表里确实有它的闭字符。</li>
     * </ol>
     *
     * <p>不能用闸门返回值当判据：物理路径上搜狗只提交单字符，它照样返回 true（踩过）。
     */
    static void balanceQuoteToggleIfPaired(final CharSequence committed) {
        if (committed == null || committed.length() != 1) return;
        final char open = committed.charAt(0);
        if (open == 0 || open != sPendingToggleOpen) return;
        if (System.currentTimeMillis() - sPendingToggleAt > 800L) return;
        final boolean willPair = sHwKeyDown
                ? SogouTranslator.physCompleteActive()     // 物理侧：功能 9
                : SogouTranslator.autoPairEnabled();       // 软键盘侧：S
        if (!willPair) return;
        if (SogouTranslator.autoPairMap().get(open) == null) return;
        sPendingToggleOpen = 0;                            // 单次有效
        balanceQuoteToggle(open);
    }

    /**
     * 配对补全之后，把引号标志位多翻一格（只对中文引号 {@code “} / {@code ‘} 有意义）。
     *
     * <p>必须在配对<b>确实补上了</b>之后调用：S 关掉或没配对成功时搜狗只提交单字符，
     * 那一格翻转是它自己该有的，再去补就翻错了。
     */
    static void balanceQuoteToggle(final char open) {
        final int code;
        if (open == '\u201c') code = '"';           // “ ← 键码 "
        else if (open == '\u2018') code = '\'';    // ‘ ← 键码 '
        else return;
        final Object owner = sQuoteMapper;
        final Method m = sQuoteMapMethod;
        if (owner == null || m == null) return;     // 还没抓到实例 → 静默降级
        try {
            m.invoke(owner, code);
            if (BridgeHook.DEV_AUTOPAIR_LOG) {
                Log.i(TAG, "quotebalance: " + open + " -> 再翻一格");
            }
        } catch (Throwable err) {
            Log.w(TAG, "quotebalance failed: " + err);
        }
    }

    // ------------------------------------------------------------------
    // ① 总闸：S 关 → 返回 false（调用方会提交单字符）
    // ------------------------------------------------------------------

    private static void installGate(XposedModule module, ClassLoader cl) {
        if (sGateInstalled) return;
        try {
            final Class<?> cls = Class.forName("UU", false, cl);
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("a")) continue;
                // 必须按参数类型精确匹配：LUU 里还有另一个同名 4 参且返回 boolean 的方法
                // (a([Ljava/lang/String;Ljava/lang/String;...;Ljava/util/List;)Z)，
                // 只按"4 参 + boolean"会误选。这里比对的全是框架类（不被混淆），可靠。
                if (!matchParams(m, InputConnection.class, CharSequence.class,
                        int.class, boolean.class)) continue;
                found = m;
                break;
            }
            if (found == null) {
                // 类已加载但方法没匹配上（搜狗改版）→ 不再重试
                sGateInstalled = true;
                warnOnce("autopair: UU.a(IC,CharSequence,I,Z) not found, native pairing kept");
                return;
            }
            found.setAccessible(true);
            module.hook(found).intercept(chain -> {
                try {
                    // S 管软键盘那一侧；物理键盘归功能 9（我们自己注入闭字符）。
                    // 物理按键时也必须拦住搜狗的配对 —— 实测它自己会分两次单字符提交
                    // 把 “” 补全，我们再插一个就成三个字符了。
                    if (!SogouTranslator.autoPairEnabled() || sHwKeyDown) {
                        if (BridgeHook.DEV_AUTOPAIR_LOG) {
                            Log.i(TAG, "autopair: gate blocked ("
                                    + (sHwKeyDown ? "hardware" : "S off") + ") -> " + chain.getArg(1));
                        }
                        return Boolean.FALSE;
                    }
                } catch (Throwable err) {
                    Log.w(TAG, "autopair gate err: " + err);
                }
                return chain.proceed();
            });
            sGateInstalled = true;
            Log.i(TAG, "autopair gate hooked " + cls.getName() + "#" + found.getName());
        } catch (ClassNotFoundException err) {
            // 引擎类（UU 是输入会话类）要等真正开始输入才加载完 —— 不置位，交给
            // reloadConfig 的 2 秒轮询重试，直到类加载出来再挂上。
            if (BridgeHook.DEV_AUTOPAIR_LOG) {
                Log.i(TAG, "autopair gate: UU not loaded yet, will retry");
            }
        } catch (Throwable err) {
            sGateInstalled = true;   // 其它异常不重试，避免刷屏
            warnOnce("autopair gate install failed: " + err
                    + " cause=" + (err.getCause() == null ? "-" : err.getCause()));
        }
    }

    // ------------------------------------------------------------------
    // ② 自定义表：命中则交回一个自造的配对项
    // ------------------------------------------------------------------

    private static void installTable(XposedModule module, ClassLoader cl) {
        if (sTableInstalled) return;
        try {
            final Class<?> pairCls = Class.forName("Yja$b", false, cl);
            final Class<?> cls = Class.forName("Yja", false, cl);
            Method found = null;
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("a")) continue;
                // 精确：单个 CharSequence 参数、且返回类型正是 Yja$b
                // （只按"参数是 CharSequence"会误选 a(Ljava/lang/String;)Z）
                if (!matchParams(m, CharSequence.class)) continue;
                if (m.getReturnType() != pairCls) continue;
                found = m;
                break;
            }
            if (found == null) {
                sTableInstalled = true;
                warnOnce("autopair: Yja.a(CharSequence)Yja$b not found, custom table disabled");
                return;
            }
            sPairCtor = pairCls.getDeclaredConstructor(
                    String.class, String.class, String.class);
            sPairCtor.setAccessible(true);

            found.setAccessible(true);
            module.hook(found).intercept(chain -> {
                try {
                    final Object arg = chain.getArg(0);
                    if (arg instanceof CharSequence && ((CharSequence) arg).length() == 1
                            && SogouTranslator.autoPairEnabled()) {
                        // 配对表是"开字符 → 闭字符"的 Map：闭字符不是 key，
                        // 所以这里天然只会对开字符命中（方向性由数据结构保证）
                        final char c = ((CharSequence) arg).charAt(0);
                        final Character mate = SogouTranslator.autoPairMap().get(c);
                        if (mate != null) {
                            final Constructor<?> ctor = sPairCtor;
                            if (ctor != null) {
                                // a=前字符, b=后配字符（c 在该提交路径未使用）
                                final Object pair = ctor.newInstance(
                                        String.valueOf(c), String.valueOf(mate),
                                        String.valueOf(c));
                                if (BridgeHook.DEV_AUTOPAIR_LOG) {
                                    Log.i(TAG, "autopair: custom " + c + " -> " + mate);
                                }
                                return pair;
                            }
                        }
                    }
                } catch (Throwable err) {
                    Log.w(TAG, "autopair table err: " + err);
                }
                return chain.proceed();
            });
            sTableInstalled = true;
            Log.i(TAG, "autopair table hooked " + cls.getName() + "#" + found.getName());
        } catch (ClassNotFoundException err) {
            if (BridgeHook.DEV_AUTOPAIR_LOG) {
                Log.i(TAG, "autopair table: Yja not loaded yet, will retry");
            }
        } catch (Throwable err) {
            sTableInstalled = true;
            warnOnce("autopair table install failed: " + err
                    + " cause=" + (err.getCause() == null ? "-" : err.getCause()));
        }
    }

    /** 参数类型逐个精确比对（避免同签名重载误选）。 */
    private static boolean matchParams(Method m, Class<?>... want) {
        final Class<?>[] got = m.getParameterTypes();
        if (got.length != want.length) return false;
        for (int i = 0; i < want.length; i++) {
            if (got[i] != want[i]) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // ③ 物理键盘自动补全（功能 9）：硬件按键触发提交单个"开字符"时注入闭字符
    // ------------------------------------------------------------------

    /** 是否正在注入（防止我们自己的提交被再次当成"用户输入"）。 */
    private static final ThreadLocal<Boolean> sInjecting = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 注入期间为 true —— {@code commitText} 钩子见到它就原样放行，不让标点管线改写。 */
    static boolean isInjecting() {
        return Boolean.TRUE.equals(sInjecting.get());
    }

    /**
     * 包裹刚做完的一小段静默窗口的截止时刻（{@link System#currentTimeMillis()}）。
     *
     * <p>为什么需要：搜狗通过 {@code onUpdateSelection} 把选区变化喂给
     * {@code MF.a(IIIIII)}，它据此维护自己的光标模型，并会在约 300ms / 1s 后
     * 把光标改回"闭字符之前"（真机实测 6 → 1 → 0）。包裹是我们自己提交的，
     * 那几次回调对它没有意义、却会踩掉我们设的光标，所以在窗口内不转给它。
     * 窗口很短（{@link #WRAP_QUIET_MS}），过后一切照旧。
     */
    private static volatile long sWrapQuietUntil;

    /** 包裹后的静默窗口时长（ms）。 */
    private static final long WRAP_QUIET_MS = 800L;

    /**
     * 包裹后的窗口内，搜狗若又请求 {@code setSelection}，就把它改成这个位置（-1 = 不接管）。
     *
     * <p>为什么是"改"而不是"再设一次"：真机日志证明搜狗在包裹之后会通过自己的
     * {@code Qja.setSelection} 请求 {@code setSelection(1,1)}（把它自己的模型当成真相），
     * 而 {@code Qja} 是<b>排队</b>执行的（{@code Handler.post(new vja(...))}）——
     * 我们的同步/延后调用都排不进那个队，所以怎么设都会被它最后覆盖。
     * 直接改写它这一次请求的参数，才是"排到最后"的唯一办法。
     */
    private static volatile int sWrapOverrideTo = -1;

    /**
     * 给 {@code Qja.setSelection} 的钩子用：窗口内把搜狗那次请求改写到我们要的位置。
     *
     * @return 改写后的位置；{@code -1} = 不在窗口内 / 不改写
     */
    static int overrideSelectionWhileQuiet() {
        return System.currentTimeMillis() < sWrapQuietUntil ? sWrapOverrideTo : -1;
    }

    /**
     * 是否要吞掉这次 {@code onUpdateSelection}（窗口外恒 false）。 */
    static boolean shouldSwallowSelectionUpdate() {
        return System.currentTimeMillis() < sWrapQuietUntil;
    }

    /**
     * 包裹后的窗口内，把每次 {@code setSelection} 的入参打出来（诊断用）。
     */
    static void logSelectionWhileQuiet(final Object connection, final int start, final int end) {
        if (!BridgeHook.DEV_AUTOPAIR_LOG) return;
        if (System.currentTimeMillis() >= sWrapQuietUntil) return;
        Log.i(TAG, "pairwrap: setSelection(" + start + "," + end + ") via "
                + (connection == null ? "null" : connection.getClass().getName()));
    }

    /** 开始静默窗口（在真的提交整串之前调用）。 */
    private static void beginWrapQuiet() {
        sWrapQuietUntil = System.currentTimeMillis() + WRAP_QUIET_MS;
    }

    /**
     * 把"这次包裹是被谁调进来的"打出来（只打一次调用栈；诊断用，默认关）。
     *
     * <p>为什么要它：反编译能告诉我们搜狗有哪些成对提交/设光标的路径，但**不知道运行时
     * 实际走的是哪条** —— 靠猜字节码容易错（本轮就猜错过一次）。栈帧是运行时事实。
     */
    private static void logWrapStack() {
        if (!BridgeHook.DEV_AUTOPAIR_LOG) return;
        try {
            final StackTraceElement[] st = Thread.currentThread().getStackTrace();
            final StringBuilder sb = new StringBuilder("pairwrap stack:");
            int n = 0;
            for (StackTraceElement e : st) {
                final String cn = e.getClassName();
                if (cn.startsWith("java.lang.Thread") || cn.startsWith("dalvik.")
                        || cn.startsWith("android.os.Looper") || cn.startsWith("android.os.Handler")
                        || cn.startsWith("android.os.MessageQueue")) {
                    continue;
                }
                sb.append("\n      ").append(cn).append('.').append(e.getMethodName())
                  .append(':').append(e.getLineNumber());
                if (++n >= 14) break;
            }
            Log.i(TAG, sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 当前是否正在处理硬件按键（由按键守卫置/清）。 */
    private static volatile boolean sHwKeyDown;

    /** 按键守卫调用：标记硬件按键的按下/抬起。 */
    static void markHardwareKey(boolean down) {
        sHwKeyDown = down;
    }

    /**
     * 物理键盘补全：硬件按键期间提交了一个"开字符"时，补上闭字符并把光标移到两者之间。
     *
     * <p>必须在**原提交完成之后**调用（开字符先上屏，再补闭字符）。
     * 判据里"硬件按键期间"这一条把软键盘排除在外（软键盘不走 onKeyDown）；
     * "列表里是开字符"这一条由 {@code pairMap} 的结构保证（闭字符不是 key）。
     */
    static void maybeInjectPair(final Object connection, final CharSequence committed) {
        if (connection == null || committed == null) return;
        if (!(connection instanceof InputConnection)) return;
        if (!sHwKeyDown) return;                                   // 只处理硬件键盘
        if (!SogouTranslator.physCompleteActive()) return;          // 功能开关关 or 状态位关
        if (committed.length() != 1) return;                        // 只认单个字符
        final char c = committed.charAt(0);
        final Character close = SogouTranslator.autoPairMap().get(c);
        if (close == null) return;                                  // 不是开字符（闭字符由总入口处理）
        final char open = c;
        final InputConnection ic = (InputConnection) connection;
        try {
            sInjecting.set(Boolean.TRUE);
            ic.beginBatchEdit();
            ic.commitText(String.valueOf(close), 1);
            ic.endBatchEdit();
            moveCursorLeftOne(ic);
            // closeSkip 的状态位：只有"补完闭字符之后，光标后还是这个闭字符"才记为待跳过
            // （即打闭字符那一下补的其实是"光标后本来就有的那个"）
            if (SogouTranslator.closeSkipEnabled()) markPairInjected(ic, open, close);
            if (BridgeHook.DEV_AUTOPAIR_LOG) {
                Log.i(TAG, "physpair: " + open + " -> " + open + close + " (cursor mid)");
            }
        } catch (Throwable err) {
            Log.w(TAG, "physpair inject failed: " + err);
        } finally {
            sInjecting.set(Boolean.FALSE);
        }
    }

    /**
     * **有选区时把选区包起来 / 光标后已有闭字符时只移光标**（照 gb
     * {@code AutoPair.maybeWrapSelection} 那一套思路做的扩展）。
     *
     * <p>选中 {@code abc} 打 {@code （} ⇒ {@code （abc）}，光标落在闭字符之后；
     * {@code （xxx|} 再打 {@code ）} ⇒ 只把光标移过去、不再多出一个（两个开关分别控制，
     * 见下方 {@link #handlePairCommit}）。
     *
     * <p>必须在**原提交之前**调用：{@code getSelectedText(0)} 只有那一刻还问得到 ——
     * 开字符一旦上屏，选区就被顶掉了，之后再也问不到 ✗（gb 那边踩过这个坑）。
     * 命中即由本方法整串提交，调用方<b>不要再走原提交</b>。
     *
     * <p><b>参数用的是"管线之后的字符"</b>（{@code SogouTranslator} 传的是
     * {@code transformCommit} 的结果，不是键位字符）。这一条正是边界 §4
     * 「比较用实际上屏字符」的落地：中文态按 {@code (} 得到的是全角 {@code （}，
     * 拿半角 {@code (} 去查配对表会配成半角的 {@code )} ⇒ {@code （abc)} 这种混用。
     *
     * @param pair 这一次原本要提交的整串；搜狗有的输入方式（长按 z 的符号菜单）
     *             会一次提交 {@code （）} 两个字，靠它才认得出是成对提交
     */
    /**
     * 成对符号那一下进来时的总入口：先看要不要"只移光标"（closeSkip），再看要不要包选区。
     *
     * <p>两者都要先知道"光标后侧有什么"，所以合在一处判定，顺序也是固定的：
     * <ol>
     *   <li><b>closeSkip</b>（{@link SogouTranslator#closeSkipEnabled()}）：
     *       {@code （xxx|} 再打 {@code ）} ⇒ 光标后正是它 ⇒ 只把光标移过去、不再多出一个；</li>
     *   <li><b>选区包裹</b>（{@link SogouTranslator#wrapSelectionEnabled()}）：
     *       有选区 ⇒ 提交 {@code open + 选区 + close}。</li>
     * </ol>
     *
     * @return true = 已处理（调用方**别**再走原提交）
     */
    static boolean handlePairCommit(final Object connection, final CharSequence resolved,
                                    final CharSequence pair) {
        if (connection == null) return false;
        if (!(connection instanceof InputConnection)) return false;

        // 先按单个开字符判定；不成立再看它是不是"一整个配对"
        char open = 0;
        Character close = null;
        if (resolved != null && resolved.length() == 1) {
            open = resolved.charAt(0);
            close = SogouTranslator.autoPairMap().get(open);
        }
        if (close == null) {
            open = 0;
            close = null;
            if (pair != null && pair.length() == 2) {
                final char a = pair.charAt(0);
                final Character b = SogouTranslator.autoPairMap().get(a);
                // 第二字符必须**正是**它配对的闭字符：这样 "（（"、"（【" 之类的多字符
                // 标点串都不会被误当成一对
                if (b != null && b == pair.charAt(1)) {
                    open = a;
                    close = b;
                }
            }
        }

        // 既不是开字符、也不是任何配对里的闭字符 ⇒ 不关我们的事
        if (close == null && !isCloser(open)) {
            logWrapSkip(resolved, "not-opener");
            return false;
        }

        final InputConnection ic = (InputConnection) connection;

        if (close != null && (sHwKeyDown ? !SogouTranslator.physCompleteActive()
                                         : !SogouTranslator.autoPairEnabled())) {
            logWrapSkip(resolved, sHwKeyDown ? "off:physComplete" : "off:autoPair");
            return false;                                          // 开关关着 → 老路（包裹不做）
        }

        // ---- ① 跳过已存在的闭合符：光标后侧正是它 ⇒ 只把光标移过去，不再输出 ----
        //
        // 两种进来的字符都要处理：
        //   (a) 开字符（close != null，走包裹那条路）；
        //   (b) **纯闭字符**（它自己不是开字符，例如 ）】」）—— 它在上面查不到开字符，
        //       所以必须**在提前返回之前**先判一次，否则"打闭字符"永远走不到这里
        //       （这就是本轮踩的坑：先 return false 才轮到这段，等于开关没接线）。
        //   引号这类"既是开又是闭"的字符走 (a)：它的 close != null，天然不会被这里拦住。
        if (SogouTranslator.closeSkipEnabled()) {
            if (close != null) {
                final int skipTo = caretBeforeCloser(ic, open, close);
                if (skipTo >= 0) {
                    try {
                        ic.setSelection(skipTo, skipTo);
                        sWrapDepth = -1;                           // 这一格配对用完 ⇒ 清状态
                        if (BridgeHook.DEV_AUTOPAIR_LOG) {
                            Log.i(TAG, "closeskip: " + open + " -> caret only, setSel("
                                    + skipTo + "," + skipTo + ")");
                        }
                        return true;                               // 原提交不要走：闭字符已在光标后
                    } catch (Throwable err) {
                        Log.w(TAG, "closeskip failed: " + err);
                    }
                }
            } else if (isCloser(open)) {
                final char pairOpen = openerFor(open);
                final int skipTo = caretBeforeCloser(ic, pairOpen, open);
                if (skipTo >= 0) {
                    try {
                        ic.setSelection(skipTo, skipTo);
                        sWrapDepth = -1;
                        if (BridgeHook.DEV_AUTOPAIR_LOG) {
                            Log.i(TAG, "closeskip: " + pairOpen + " -> caret only, setSel("
                                    + skipTo + "," + skipTo + ")");
                        }
                        return true;
                    } catch (Throwable err) {
                        Log.w(TAG, "closeskip failed: " + err);
                    }
                }
                return false;                                      // 纯闭字符：照原样上屏（老路）
            }
        }
        if (close == null) return false;                           // 既不是开字符也不是闭字符

        // ---- ① 跳过已存在的闭合符：光标后侧正是它 ⇒ 只把光标移过去，不再输出 ----
        // 判据（plan §2.1）：光标后紧接着的字符 == **将要上屏的那个字符**，
        // 纯闭字符（不是任何配对的开字符）：到这里 closeSkip 已经判完，照原样上屏
        if (close == null) return false;

        // ---- ② 选区包裹 ----
        if (!SogouTranslator.wrapSelectionEnabled()) {
            logWrapSkip(resolved, "off:wrapSelection");
            return false;
        }
        logWrapStack();
        final CharSequence sel;
        try {
            sel = ic.getSelectedText(0);
        } catch (Throwable err) {
            Log.w(TAG, "pairwrap: getSelectedText threw for " + open + ": " + err);
            return false;                                          // 问不到就按老路走，不冒险
        }
        if (sel == null || sel.length() == 0) {
            logWrapSkip(resolved, "no-selection");
            return false;
        }
        if (sel.length() > 500) {                                   // 超大选区不重提交（避免卡顿）
            logWrapSkip(resolved, "sel-too-long:" + sel.length());
            return false;
        }
        // 插入点必须**在提交之前**取：提交之后光标前的内容就变成整串了，算不回去
        final int at = cursorOffset(ic);
        final String openStr = String.valueOf(open);
        final String closeStr = String.valueOf(close);
        final int end = at >= 0
                ? at + openStr.length() + sel.length() + closeStr.length() : -1;
        // 交给窗口内的 Qja.setSelection 改写用（搜狗那次请求比我们返回得还晚，见 finally 注释）
        sWrapOverrideTo = end;
        try {
            sInjecting.set(Boolean.TRUE);
            beginWrapQuiet();          // 先开静默窗口：下面那次 setSelection 引起的回调也别喂给搜狗
            ic.beginBatchEdit();
            // 1 = 相对插入文本的末尾（按协议就该落在整串之后）
            ic.commitText(openStr + sel + closeStr, 1);
            ic.endBatchEdit();
            if (close == open) {
                // 同字符对（引号）：这一格翻转已经被"选中+包裹"用掉了，
                // 再翻一格把奇偶补回来 —— 否则下一次按引号键会出成闭引号 ✗。
                // 只在真包成功之后做（失败就直接 return false 走老路，那一格归搜狗自己）。
                balanceQuoteToggle(open);
            }
            if (end >= 0) {
                // 光标只是收尾：**它失败也不能让包裹本身判负**。
                // 踩过：延后那条路构造 Handler 抛异常，被下面那个 catch 吞掉，
                // 于是"文字已经提交成功"却返回 false，调用方又走了一遍原提交 ⇒ 出双份。
                try {
                    retryCursorAfterSogou(ic, end);
                } catch (Throwable err) {
                    Log.w(TAG, "pairwrap cursor fix failed (text already committed): " + err);
                }
            }
            // closeSkip 的状态位：包裹之后光标就停在闭字符前，紧接着再打同一个闭字符
            // 就该"只移光标"（与物理补全那条路同一套判据）
            sWrapDepth = end;
            if (BridgeHook.DEV_AUTOPAIR_LOG) {
                Log.i(TAG, "pairwrap: " + open + "…" + close + " around " + sel.length()
                        + " char(s)" + (sHwKeyDown ? " [hw]" : " [soft]"));
            }
            return true;
        } catch (Throwable err) {
            Log.w(TAG, "pairwrap failed: " + err);
            return false;                                          // 失败就让原提交照常走
        } finally {
            sInjecting.set(Boolean.FALSE);
            // 注意：**不要**在这里清 sWrapOverrideTo。
            // 真机时序（2026-09-18）：搜狗那次 setSelection(1,1) 是在 commitText 链返回
            // **之后**才发出的 —— 清早了它就变成"窗口内但没目标"，等于没接管。
            // 覆盖值活到窗口结束即可（判定在 overrideSelectionWhileQuiet 里）。
        }
    }

    /**
     * 把光标挪到整串之后 —— <b>并且要再补一次</b>。
     *
     * <p>为什么一次不够（真机 2026-09-18 实测）：提交时已经按协议传了
     * {@code newCursorPosition=1}，随后又在同一个 batchEdit 里显式 {@code setSelection(6,6)}，
     * 两次日志都证明调用发出去了，可光标<b>照样</b>停在闭字符前（{@code （|1234）}）——
     * 搜狗在这之后又按它自己的内部光标模型挪了一次。所以必须让"挪光标"排到它<b>后面</b>：
     * <ol>
     *   <li>同步先设一次（搜狗不改的话立刻就对，也让同批编辑里以它为准）；</li>
     *   <li>再 {@code post} 一次到 {@link InputConnection#getHandler()} —— 它跑在输入事件
     *       之后，能把搜狗那次覆盖纠正回来。</li>
     * </ol>
     *
     * <p>post 那一步会**先核对**光标前确实是"我们刚包好的那一串"再动：万一用户在
     * 这一瞬间又输入了别的东西，就不去抢光标（宁可光标位置不理想，也不能把用户的光标跳错）。
     */
    private static void retryCursorAfterSogou(final InputConnection ic, final int end) {
        try {
            ic.setSelection(end, end);
        } catch (Throwable err) {
            Log.w(TAG, "pairwrap cursor sync failed: " + err);
        }
        final android.os.Handler h;
        try {
            h = ic.getHandler();
        } catch (Throwable err) {
            Log.w(TAG, "pairwrap: getHandler threw: " + err);
            return;                                                // 没有 handler 就只能靠同步那一次
        }
        if (h == null) {
            // RemoteInputConnection 不给我们 handler（真机实测为 null）⇒ 借搜狗主线程的
            SogouTranslator.postAfterImeSettles(ic, end);
            return;
        }
        postCursorFix(h, ic, end);
    }

    /**
     * 稍后把光标**再落一次**到整串之后。
     *
     * <p>为什么必须延后：真机日志证明同步那次 {@code setSelection(6,6)} 是<b>成功</b>的
     * （紧接着读回来就是 6），可搜狗随后又按它自己的模型挪回了闭字符前，
     * 最终渲染出来还是 {@code （|1234）}。而 {@code ic.getHandler()} 返回 null，
     * 没法在连接上排队，所以由 {@link SogouTranslator#postAfterImeSettles} 借搜狗主线程补一次。
     *
     * <p>只往前挪（{@code now < end}）就不碰 —— 那种情况是用户自己按了左方向键，
     * 不该把光标再拽回末尾。
     */
    static void settleCursor(final Object connection, final int end) {
        if (!(connection instanceof InputConnection) || end < 0) return;
        final InputConnection ic = (InputConnection) connection;
        try {
            final int now = cursorOffset(ic);
            if (now < 0 || now < end || now == end) {
                if (BridgeHook.DEV_AUTOPAIR_LOG) {
                    Log.i(TAG, "pairwrap: settle noop now=" + now + " end=" + end);
                }
                return;
            }
            ic.setSelection(end, end);
            if (BridgeHook.DEV_AUTOPAIR_LOG) {
                Log.i(TAG, "pairwrap: settle " + now + " -> " + end
                        + ", after=" + cursorOffset(ic));
            }
        } catch (Throwable err) {
            Log.w(TAG, "pairwrap cursor settle failed: " + err);
        }
    }

    /** 延迟 {@code delayMs} 后把光标落回整串之后（仅当它被挪到了后面）。 */
    private static void postCursorFix(final android.os.Handler h, final InputConnection ic,
                                      final int end) {
        try {
            h.postDelayed(() -> settleCursor(ic, end), 60L);
        } catch (Throwable err) {
            Log.w(TAG, "pairwrap cursor post schedule failed: " + err);
        }
    }

    /**
     * 光标的绝对偏移；拿不准时返回 {@code -1}（宁可不动，也不要用假偏移把光标跳错地方）。
     *
     * <p>{@code getTextBeforeCursor} 返回的<b>长度就是光标前的字符数</b>，但它有上限 ——
     * 一旦触顶说明拿到的不是全量，此时那个长度只是"上限"而不是真实偏移。
     */
    private static int cursorOffset(final InputConnection ic) {
        final int cap = 4096;
        try {
            final CharSequence before = ic.getTextBeforeCursor(cap, 0);
            if (before == null) return -1;
            if (before.length() >= cap) return -1;                  // 触顶 ⇒ 不是全量
            return before.length();
        } catch (Throwable err) {
            return -1;
        }
    }

    /**
     * 「为什么没包」的诊断：把进到 {@code maybeWrapSelection} 之后每一个 {@code return false}
     * 的理由打出来。
     *
     * <p>为什么值得单独有：包装失败的每一条分支都是<b>静默</b>的（悄悄回退老路），
     * 真机上看到的现象一律是"照旧补了一对"，光看现象分不清是"没选区"、"不是开字符"、
     * "开关没生效"还是"取选区失败"——只有打出来才知道该改哪一条。
     */
    private static void logWrapSkip(final CharSequence resolved, final String why) {
        if (!BridgeHook.DEV_AUTOPAIR_LOG) return;
        final StringBuilder hex = new StringBuilder();
        if (resolved != null) {
            for (int i = 0; i < resolved.length() && i < 4; i++) {
                hex.append(String.format("%04X ", (int) resolved.charAt(i)));
            }
        }
        Log.i(TAG, "pairwrap-skip: " + why + " ch=[" + resolved + "] codes="
                + hex.toString().trim() + (sHwKeyDown ? " [hw]" : " [soft]"));
    }

    /**
     * 配对表里所有闭字符（= value 集合）。用它反向判断"光标后这个字符是不是闭合符"。
     *
     * <p>为什么不自己写死一串：用户可以在设置里改配对表，判据必须跟着表走。
     * 现查现算，表很小（18 对），开销可以忽略。
     */
    private static boolean isCloser(final char c) {
        return SogouTranslator.autoPairMap().containsValue(c);
    }

    /** 闭字符 → 它的开字符（配对表反查）；查不到返回 0。 */
    private static char openerFor(final char close) {
        for (java.util.Map.Entry<Character, Character> e
                : SogouTranslator.autoPairMap().entrySet()) {
            if (e.getValue() == close) return e.getKey();
        }
        return 0;
    }

    /** 我们刚补出闭字符的那次插入点（-1 = 没有待确认的补全）。 */
    private static volatile int sWrapDepth = -1;

    /**
     * 补出闭字符之后由 {@link #maybeInjectPair} 调用，用于 closeSkip 的状态位。
     *
     * <p>判据是"这次补完之后，紧随光标之后的还是不是那个闭字符"：
     * <ul>
     *   <li>例如 {@code （xxx|} 打 {@code ）} 的那种情形（光标后本来就有一个 {@code ）}）：
     *       补进去的闭字符把原有的那个<b>顶到了光标后</b> ⇒ 位置没变 ⇒ 记下 1；</li>
     *   <li>普通补全（真补了一个）⇒ 位置往后挪了一格 ⇒ 不记。</li>
     * </ul>
     * 于是"再打一次同一个闭字符就只移光标"能成立，而且只依赖"上一步是补全"这个事实 ——
     * 光标后那格是别的普通字符时，它的编码不等于闭字符，同样不会误判。
     */
    static void markPairInjected(final Object connection, final char open, final char close) {
        if (!(connection instanceof InputConnection)) return;
        if (close == open) return;                                 // 引号走搜狗自己的翻转，不掺和
        final int here = cursorOffset((InputConnection) connection);
        sWrapDepth = here >= 0 ? here : -1;
    }

    /**
     * 这次按键是不是"光标后已有该闭字符，只需移光标"。
     *
     * @return 目标光标位置；{@code -1} = 不适用（该走原提交）
     */
    private static int caretBeforeCloser(final InputConnection ic, final char open, final char close) {
        if (sWrapDepth < 0) return -1;                             // 上一步不是"刚补出一个闭字符"
        final int at = cursorOffset(ic);
        final CharSequence after;
        try {
            after = ic.getTextAfterCursor(1, 0);
        } catch (Throwable err) {
            return -1;
        }
        if (after == null || after.length() != 1 || after.charAt(0) != close) return -1;
        final int to = at + 1;
        if (BridgeHook.DEV_AUTOPAIR_LOG) {
            Log.i(TAG, "closeskip: caret " + at + " -> " + to + " over existing " + close);
        }
        return to;
    }

    /**
     * 把光标左移一格（落在刚补上的闭字符之前）。
     *
     * <p>只有能拿到"光标前的完整文本"时才移动：{@code getTextBeforeCursor} 返回的
     * 长度即光标的绝对偏移，但它会被上限截断 —— 一旦达到上限说明拿到的不是全量，
     * 此时**宁可不动**，也不要用一个假偏移把光标跳到别处。
     */
    private static void moveCursorLeftOne(InputConnection ic) {
        final int cap = 4096;
        final CharSequence before = ic.getTextBeforeCursor(cap, 0);
        if (before == null) return;
        if (before.length() >= cap) {
            if (BridgeHook.DEV_AUTOPAIR_LOG) {
                Log.i(TAG, "physpair: cursor move skipped (text before cursor >= " + cap + ")");
            }
            return;
        }
        final int pos = before.length() - 1;
        if (pos < 0) return;
        ic.setSelection(pos, pos);
    }

    /** 装不上只报一次，避免每次按键刷屏。 */
    private static void warnOnce(String msg) {
        if (sWarned) return;
        sWarned = true;
        Log.w(TAG, msg);
    }
}
