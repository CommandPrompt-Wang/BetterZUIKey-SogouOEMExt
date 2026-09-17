package moe.lovefirefly.bzk.sougouext;

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

    private static final String TAG = "SogouOemBridge";

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
        final char open = committed.charAt(0);
        final Character close = SogouTranslator.autoPairMap().get(open);
        if (close == null) return;                                  // 不是开字符
        final InputConnection ic = (InputConnection) connection;
        try {
            sInjecting.set(Boolean.TRUE);
            ic.beginBatchEdit();
            ic.commitText(String.valueOf(close), 1);
            ic.endBatchEdit();
            moveCursorLeftOne(ic);
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
