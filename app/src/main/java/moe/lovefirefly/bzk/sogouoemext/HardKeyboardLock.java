package moe.lovefirefly.bzk.sogouoemext;

import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 锁定软硬键盘模式：<b>模块自己维护状态</b>，不去猜搜狗的黑箱模式逻辑。
 *
 * <h3>状态</h3>
 * 只有一个状态位：{@link #sModeHard}（true = 硬键盘，false = 软键盘）。
 * 界面上的"当前状态"就是它。
 *
 * <h3>唯一允许的状态转移</h3>
 * <ol>
 *   <li><b>CSP</b>（Ctrl/Alt + 字母这类热键）→ 双向切换；</li>
 *   <li><b>同一个输入框 2s 内连点两次</b>"要软键盘" → 软键盘；</li>
 *   <li><b>软键盘态下用物理键盘打字</b> → 自动切硬键盘；</li>
 *   <li><b>工具栏呼出软键盘</b>（命令 -3 = SwitchSoftKeyboard）→ 软键盘，任何时候都允许；</li>
 *   <li><b>插件长按</b> → 按方向切。</li>
 * </ol>
 *
 * <h3>怎么"锁"</h3>
 * 不是跟框架顶，而是在<b>搜狗自己的状态写入点</b>上做取舍：
 * <ul>
 *   <li>硬键盘态：吞掉"输入开始那次切软键盘页"（{@code iP.a(2)}），软键盘页就不会起来；</li>
 *   <li>软键盘态：拦掉硬键盘容器页的条件位写入（{@code Lta.a(true)}），
 *       调度器因此不会选中硬键盘页。</li>
 * </ul>
 * 两者都只在"非显式动作"时拦 —— 显式动作（上面 5 条）永远放行。
 *
 * <h3>⛔ 硬约束（踩过的坑）</h3>
 * 绝不在框架层说"不"：{@code onShowInputRequested → false} 或吞 {@code showSoftInput}
 * 会让窗口被收起后再也显示不回来（工具栏/候选窗消失 + 输入被阻塞）。
 * 锁只在"页/条件位"这一层做。
 */
final class HardKeyboardLock {

    private static final String TAG = "BZK-SogouOEMExt";

    /** 切页/模式 id（真机实测）。 */
    private static final int MODE_SOFT = 2;

    /** 总开关（插件里的「锁定软硬键盘模式」）。 */
    private static volatile boolean sEnabled;

    /** 我们维护的模式：true = 硬键盘。 */
    private static volatile boolean sModeHard;

    /** 连点判定：同一个框内两次"要软键盘"的最大间隔。 */
    private static final long DOUBLE_TAP_MS = 2000;

    /** 同一击的重复请求去抖。 */
    private static final long SAME_TAP_MS = 150;

    /** 按键去抖：按键钩子装在 coa 与 InputMethodService 两层，同一个键事件会来两次。 */
    private static volatile int sLastKey;
    private static volatile boolean sLastKeyDown;
    private static volatile long sLastKeyAt;

    /** 夺回软键盘的限流时间戳（避免和搜狗打架）。 */
    private static volatile long sLastRestore;

    private static volatile long sLastShow;
    private static volatile long sFirstShow;
    private static volatile String sFirstEditor;

    // ---- 搜狗侧句柄 ----
    private static boolean sInstalled;
    private static volatile Object sService;
    private static volatile Object sWo;
    private static volatile Object sPageContainer;
    private static volatile Object sCondHard;
    private static volatile Object sCondSoft;
    private static volatile Method sStartHard;      // Lta.c(Z)V = StartFromHardKey
    private static volatile Method sPageStart;      // iP.a(I)V
    private static volatile boolean sDriving;       // 防止"驱动"触发自己的钩子递归
    private static volatile Method sSoftSwitch;
    private static volatile Object sSoftSwitchCmd;

    /** 状态变化通知（App 镜像用）。 */
    private static volatile Runnable sOnStateChanged;

    private HardKeyboardLock() {
    }

    // ------------------------------------------------------------------ 对外

    static void setEnabled(boolean on) {
        if (sEnabled == on) return;
        sEnabled = on;
        Log.i(TAG, "hardkbd-lock: 开关 -> " + on);
        notifyState();
    }

    /**
     * 搜狗「软硬键盘切换」热键的可读名（给 App 的规则弹窗显示；未设置为 null）。
     *
     * <p>来源：搜狗私有 MMKV {@code files/mmkv/HardKeyboardRepository} 里的键
     * {@code hardkeyboard_soft_hard_switch_hotkey}。格式：键名后紧跟 {@code [len+1][len][值]}。
     * 用搜狗服务实例拿 files 目录（模块自己的 ctx 是系统上下文，读不到私有目录）。
     */
    static String hotkeyLabel() {
        final Object svc = sService;
        if (!(svc instanceof android.content.Context)) return null;
        // 直接解析 MMKV 文件（真机验证过布局）：
        //   [0,4) = 当前有效数据长度 actualSize；有效条目只在 [4, actualSize) 内；
        //   超出部分是陈旧数据（append/重写留下的），同一个键在有效区内**最后一份**才是当前值。
        try {
            final java.io.File f = new java.io.File(
                    ((android.content.Context) svc).getFilesDir(),
                    "mmkv/HardKeyboardRepository");
            if (!f.exists()) return null;
            final byte[] d = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0;
                while (off < d.length) {
                    final int n = in.read(d, off, d.length - off);
                    if (n <= 0) break;
                    off += n;
                }
            }
            if (d.length < 8) return null;
            final int actual = (d[0] & 0xff) | ((d[1] & 0xff) << 8)
                    | ((d[2] & 0xff) << 16) | ((d[3] & 0xff) << 24);
            final int end = (actual > 8 && actual <= d.length) ? actual : d.length;
            final byte[] key = "hardkeyboard_soft_hard_switch_hotkey".getBytes("UTF-8");
            int idx = -1;
            for (int at = indexOf(d, key, 0, end); at >= 0; at = indexOf(d, key, at + 1, end)) {
                idx = at;
            }
            if (idx < 0) return null;
            final int p = idx + key.length;
            if (p + 2 > end) return null;
            final int len = d[p + 1] & 0xff;
            if ((d[p] & 0xff) != len + 1 || len > 32) return null;      // 空值 = len 0
            final String v = len == 0 ? null : new String(d, p + 2, len, "UTF-8");
            Log.i(TAG, "hardkbd-lock: 热键设置 = \"" + v + "\" (actual=" + actual + ")");
            return v;
        } catch (Throwable err) {
            Log.w(TAG, "hardkbd-lock: 读热键设置失败: " + err);
            return null;
        }
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        return indexOf(hay, needle, 0);
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        return indexOf(hay, needle, from, hay.length);
    }

    private static int indexOf(byte[] hay, byte[] needle, int from, int end) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= Math.min(end, hay.length); i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** 当前模式（true = 硬键盘）。状态行/镜像用这个。 */
    static boolean hardModeNow() {
        return sModeHard;
    }

    /** 兼容旧调用点（App 的 want 判定）。 */
    static boolean wantHard() {
        return sModeHard;
    }

    static void setOnStateChanged(Runnable r) {
        sOnStateChanged = r;
    }

    /**
     * 物理按键（由按键钩子调用）。
     *
     * <p>按状态机设计：**软键盘态下只要用户碰物理键盘就切硬键盘**（打字如此，
     * CSP 要先按 P 也如此）；硬键盘态下什么都不做 —— 那条路由搜狗自己的热键处理
     * （CSP → 命令 -3 → 我们切软）。
     *
     * <p>真机教训：软键盘态下 {@code qua.d()} 走的是"只重启调度器"的分支，
     * 不会调 {@code Lta.c(true)}，所以钩那种信号收不到，必须从按键侧判。
     */
    static void noteKey(int keyCode, int meta, boolean down) {
        if (!sEnabled || !down || sModeHard) return;
        if (isModifierKey(keyCode)) return;              // 纯修饰键不算"打字"
        final long now = SystemClock.uptimeMillis();
        if (keyCode == sLastKey && down == sLastKeyDown && now - sLastKeyAt < 60) return;
        sLastKey = keyCode;
        sLastKeyDown = down;
        sLastKeyAt = now;
        setMode(true, "物理键盘输入");
    }

    /** 修饰键（单独按不算打字）。 */
    private static boolean isModifierKey(int kc) {
        return kc == android.view.KeyEvent.KEYCODE_SHIFT_LEFT
                || kc == android.view.KeyEvent.KEYCODE_SHIFT_RIGHT
                || kc == android.view.KeyEvent.KEYCODE_CTRL_LEFT
                || kc == android.view.KeyEvent.KEYCODE_CTRL_RIGHT
                || kc == android.view.KeyEvent.KEYCODE_ALT_LEFT
                || kc == android.view.KeyEvent.KEYCODE_ALT_RIGHT
                || kc == android.view.KeyEvent.KEYCODE_META_LEFT
                || kc == android.view.KeyEvent.KEYCODE_META_RIGHT
                || kc == android.view.KeyEvent.KEYCODE_CAPS_LOCK;
    }

    /** App 长按（应急切换）。 */
    static void applyWant(boolean hard) {
        if (hard) {
            setMode(true, "插件长按");
            // 收掉软键盘，硬键盘工具条会在下一次物理键时接管
            final Object svc = sService;
            if (svc != null) {
                try {
                    svc.getClass().getMethod("requestHideSelf", int.class).invoke(svc, 0);
                } catch (Throwable err) {
                    Log.w(TAG, "hardkbd-lock: requestHideSelf 失败: " + err);
                }
            }
        } else {
            setMode(false, "插件长按");
            execSoftSwitch();
        }
    }

    // ------------------------------------------------------------------ 安装

    static void install(XposedModule module, Object wo, Object svc) {
        if (sInstalled || module == null || wo == null) return;
        sInstalled = true;
        sWo = wo;
        sService = svc;
        try {
            final Object page = pageContainer(wo);
            if (page == null) {
                Log.w(TAG, "hardkbd-lock: 找不到页容器，功能不生效");
                return;
            }
            sPageContainer = page;

            // 1) 切页：硬键盘态下吞掉"输入开始那次切软键盘页"
            final Method start = singleIntArgumentVoid(page.getClass());
            if (start != null) {
                start.setAccessible(true);
                module.hook(start).intercept(chain -> {
                    final Object arg = chain.getArg(0);
                    if (!(arg instanceof Integer)) return chain.proceed();
                    final int mode = (Integer) arg;
                    Log.i(TAG, "hardkbd-lock: page.a(" + mode + ") modeHard=" + sModeHard);
                    if (mode == MODE_SOFT && sEnabled && sModeHard) {
                        // 吞掉"切软键盘页"之后，这个输入视图里可能还没有任何页 ⇒
                        // 工具条要等物理键才出来（用户实测：拿到焦点时不弹）。
                        // 所以顺手把**硬键盘页**驱动出来（搜狗自己的 startHardKeyboard 那套）。
                        Log.i(TAG, "hardkbd-lock: 硬键盘态 → 吞掉切软键盘页，并驱动硬键盘页");
                        startHard();
                        return null;
                    }
                    return chain.proceed();
                });
                sPageStart = start;
                Log.i(TAG, "hardkbd-lock: 切页钩子已装 (" + start + ")");
            }

            // 2) 条件位：软键盘态下拦掉硬键盘容器页的写入
            final android.util.SparseArray<?> conds = conditionArray(page);
            if (conds != null) {
                sCondHard = conds.get(3);       // 真机 dump 定案：3 = 硬键盘、2 = 软键盘
                sCondSoft = conds.get(2);
                hookCondition(module, sCondHard, true);
                hookCondition(module, sCondSoft, false);
                // 硬键盘条件对象上还有两个写点（真机反汇编）：
                //   a(Z) 写字段 c = 硬键盘容器页 onCreate/onDestroy（上面已钩）
                //   c(Z) 写字段 d = StartFromHardKey，**只被 qua.d()（startHardKeyboard）调用，
                //        而 qua.d 只被物理键调用** ⇒ 它就是"用户用物理键盘要硬键盘"（含 CSP）的信号。
                hookStartHard(module, sCondHard);
            } else {
                Log.w(TAG, "hardkbd-lock: 找不到条件对象表");
            }

            // 3) 连点两次（App 请求显示软键盘）→ 软键盘
            hookShowSoftInput(module, svc);
        } catch (Throwable err) {
            Log.w(TAG, "hardkbd-lock: install failed: " + err);
        }
    }

    /**
     * 钩住"切到软键盘"命令（注册表 id -3 = {@code jua}）。
     *
     * <p>工具栏的"呼出软键盘"按钮、以及硬键盘态下按 CSP 都走它 ⇒ 一律当作"用户要软键盘"。
     */
    static void hookSoftSwitchCommand(XposedModule module, Object cmd, Object wo) {
        if (module == null || cmd == null || wo == null) return;
        try {
            final Method run = cmd.getClass().getMethod("a", wo.getClass(),
                    android.os.Bundle.class);
            run.setAccessible(true);
            sSoftSwitch = run;
            sSoftSwitchCmd = cmd;
            module.hook(run).intercept(chain -> {
                setMode(false, "软键盘按钮/命令 -3");
                return chain.proceed();
            });
            Log.i(TAG, "hardkbd-lock: 软键盘命令钩子已装 (" + run + ")");
        } catch (Throwable err) {
            Log.w(TAG, "hardkbd-lock: 软键盘命令钩子失败: " + err);
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 我们唯一的写状态入口。
     *
     * <p>顺序很关键：处理器**先**改模式、**再**放行搜狗的动作，
     * 这样随后那些页面事件（硬键盘页 onCreate / 软键盘页 onCreate）天然与模式一致，
     * 不需要任何时间窗（踩过：时间窗会连带放行搜狗自己的回流）。
     */
    private static void setMode(boolean hard, String why) {
        if (sModeHard == hard) {
            Log.i(TAG, "hardkbd-lock: 模式已是 " + (hard ? "硬键盘" : "软键盘") + "（" + why + "）");
            return;
        }
        sModeHard = hard;
        Log.i(TAG, "hardkbd-lock: 模式 -> " + (hard ? "硬键盘" : "软键盘") + "（" + why + "）");
        if (hard) startHard();
        notifyState();
    }

    private static void toggle(String why) {
        setMode(!sModeHard, why);
    }

    private static void notifyState() {
        final Runnable r = sOnStateChanged;
        if (r == null) return;
        try {
            r.run();
        } catch (Throwable err) {
            Log.w(TAG, "hardkbd-lock: 状态通知失败: " + err);
        }
    }

    /**
     * 驱动硬键盘：用搜狗自己的 {@code startHardKeyboard} 那套动作
     * （{@code Lta.c(true)} = StartFromHardKey → 重启调度器 → 请框架显示窗口）。
     *
     * <p>为什么需要它：软键盘态下按 CSP/打字时，搜狗走的是 {@code qua.d} 的另一个分支，
     * 只重启调度器而不建硬键盘页 ⇒ 光改我们的模式位不够，得把它推过去。
     */
    private static void startHard() {
        if (sDriving) return;
        sDriving = true;
        try {
            if (sStartHard != null && sCondHard != null) sStartHard.invoke(sCondHard, true);
            if (sPageStart != null && sPageContainer != null) sPageStart.invoke(sPageContainer, 3);
            if (sService != null) {
                sService.getClass().getMethod("requestShowSelf", int.class).invoke(sService, 0);
            }
        } catch (Throwable err) {
            Log.w(TAG, "hardkbd-lock: 驱动硬键盘失败: " + err);
        } finally {
            sDriving = false;
        }
    }

    /** 搜狗在"我们选了软键盘"时又想切硬 ⇒ 稍后用搜狗自己的切软动作把软键盘要回来。 */
    private static void restoreSoft() {
        final long now = SystemClock.uptimeMillis();
        if (now - sLastRestore < 800) return;       // 限流，别打架
        sLastRestore = now;
        sHandler.postDelayed(() -> {
            if (sEnabled && !sModeHard) {
                Log.i(TAG, "hardkbd-lock: 夺回软键盘");
                execSoftSwitch();
            }
        }, 250);
    }

    /** 主线程 Handler（延迟动作 / 夺回用）。 */
    private static final android.os.Handler sHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** 执行"切回软键盘"命令（等价于用户按了工具栏的软键盘按钮）。 */
    private static void execSoftSwitch() {
        final Method m = sSoftSwitch;
        final Object cmd = sSoftSwitchCmd;
        final Object wo = sWo;
        if (m == null || cmd == null || wo == null) return;
        try {
            m.invoke(cmd, wo, null);
        } catch (Throwable err) {
            Log.w(TAG, "hardkbd-lock: 切回软键盘失败: " + err);
        }
    }

    /**
     * 条件位写点：{@code Lta.a(Z)} = 硬键盘容器页 onCreate/onDestroy；
     * {@code Gra.a(Z)} = 软键盘容器页 onCreate/onDestroy。
     */
    private static void hookCondition(XposedModule module, Object cond, boolean hard) {
        if (cond == null) {
            Log.w(TAG, "hardkbd-lock: " + (hard ? "硬" : "软") + "键盘条件对象缺失");
            return;
        }
        for (Method m : cond.getClass().getDeclaredMethods()) {
            if (!m.getName().equals("a") || m.getParameterCount() != 1
                    || m.getParameterTypes()[0] != boolean.class
                    || m.getReturnType() != void.class) {
                continue;
            }
            try {
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    final boolean on = Boolean.TRUE.equals(chain.getArg(0));
                    if (hard && on && sEnabled && !sModeHard) {
                        // 软键盘态下搜狗要建硬键盘页 ⇒ **不拦**（拦了会让它的页面状态机不一致，
                        // 真机实测：拦完软键盘页会被拆且再也起不来），改为稍后用它的切软动作夺回。
                        Log.i(TAG, "hardkbd-lock: 搜狗要切硬（当前选软）→ 稍后夺回软键盘");
                        restoreSoft();
                    }
                    Log.i(TAG, "hardkbd-lock: " + (hard ? "硬" : "软") + "键盘容器页 "
                            + (on ? "onCreate" : "onDestroy"));
                    return chain.proceed();
                });
                Log.i(TAG, "hardkbd-lock: " + (hard ? "硬" : "软") + "键盘状态写点已钩 ("
                        + cond.getClass().getSimpleName() + ".a)");
            } catch (Throwable err) {
                Log.w(TAG, "hardkbd-lock: 状态写点钩失败: " + err);
            }
        }
    }

    /**
     * 钩 {@code Lta.c(Z)}（StartFromHardKey）= 搜狗 {@code startHardKeyboard} 那一步。
     *
     * <p>它只被物理键路径调用 ⇒ 一旦为 true，就是"用户用物理键盘要硬键盘"
     * （CSP 在软键盘态下按、或直接打字都走这里）。这是"钩处理函数"而不是猜键位。
     */
    private static void hookStartHard(XposedModule module, Object cond) {
        if (cond == null) return;
        for (Method m : cond.getClass().getDeclaredMethods()) {
            if (!m.getName().equals("c") || m.getParameterCount() != 1
                    || m.getParameterTypes()[0] != boolean.class
                    || m.getReturnType() != void.class) {
                continue;
            }
            try {
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    final boolean on = Boolean.TRUE.equals(chain.getArg(0));
                    if (on && sEnabled) setMode(true, "搜狗启动硬键盘(物理键/CSP)");
                    return chain.proceed();
                });
                sStartHard = m;
                Log.i(TAG, "hardkbd-lock: StartFromHardKey 写点已钩");
            } catch (Throwable err) {
                Log.w(TAG, "hardkbd-lock: StartFromHardKey 钩失败: " + err);
            }
        }
    }

    /** App 请求显示软键盘（用户点输入框）：只在"同一个框 2s 内第二次"时切到软键盘。 */
    private static void hookShowSoftInput(XposedModule module, Object svc) {
        if (svc == null) return;
        try {
            final Class<?> impl = Class.forName(
                    "android.inputmethodservice.InputMethodService$InputMethodImpl",
                    false, svc.getClass().getClassLoader());
            for (Method m : impl.getDeclaredMethods()) {
                if (!m.getName().equals("showSoftInput") || m.getParameterCount() != 2) continue;
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    final Object a0 = chain.getArg(0);
                    final int flags = (a0 instanceof Integer) ? (Integer) a0 : 0;
                    if ((flags & 1) == 0 || !sEnabled) return chain.proceed();  // 只看 SHOW_EXPLICIT
                    final String editor = editorKey(svc);
                    final long now = SystemClock.uptimeMillis();
                    if (now - sLastShow < SAME_TAP_MS) return chain.proceed();
                    sLastShow = now;
                    if (sFirstShow != 0 && editor != null && editor.equals(sFirstEditor)
                            && now - sFirstShow <= DOUBLE_TAP_MS) {
                        setMode(false, "同一个框连点两次");
                        execSoftSwitch();
                    } else {
                        sFirstShow = now;
                        sFirstEditor = editor;
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "hardkbd-lock: showSoftInput 钩子已装");
            }
        } catch (Throwable err) {
            Log.w(TAG, "hardkbd-lock: showSoftInput 钩子失败: " + err);
        }
    }

    /** 当前编辑器标识（判"同一个框"）。 */
    private static String editorKey(Object svc) {
        if (svc == null) return null;
        try {
            final Object ei = svc.getClass().getMethod("getCurrentInputEditorInfo").invoke(svc);
            if (ei instanceof android.view.inputmethod.EditorInfo) {
                final android.view.inputmethod.EditorInfo info =
                        (android.view.inputmethod.EditorInfo) ei;
                return info.packageName + "/" + info.fieldId + "/0x"
                        + Integer.toHexString(info.inputType);
            }
        } catch (Throwable ignored) {
            // 拿不到就按"换框"
        }
        return null;
    }

    /** {@code WO.k()}：页容器（失败就按返回类型扫）。 */
    private static Object pageContainer(Object wo) {
        try {
            final Method k = wo.getClass().getMethod("k");
            k.setAccessible(true);
            final Object page = k.invoke(wo);
            if (page != null) return page;
        } catch (Throwable ignored) {
            // 落到扫描
        }
        for (Method m : wo.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType().isPrimitive()) continue;
            try {
                m.setAccessible(true);
                final Object r = m.invoke(wo);
                if (r != null && singleIntArgumentVoid(r.getClass()) != null) return r;
            } catch (Throwable ignored) {
                // 继续找
            }
        }
        return null;
    }

    /** 类里唯一的 {@code void f(int)}（页容器的 startKeyboard(mode)）。 */
    private static Method singleIntArgumentVoid(Class<?> c) {
        Method found = null;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != int.class) continue;
            if (m.getReturnType() != void.class) continue;
            if (found != null) return null;      // 不唯一就不猜
            found = m;
        }
        return found;
    }

    /** 条件对象表：页容器里那个"值带 (Z)V 方法"的 SparseArray（真机 = {@code iP.e}）。 */
    private static android.util.SparseArray<?> conditionArray(Object page) {
        for (Field f : page.getClass().getDeclaredFields()) {
            if (!android.util.SparseArray.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                final Object v = f.get(page);
                if (!(v instanceof android.util.SparseArray)) continue;
                final android.util.SparseArray<?> arr = (android.util.SparseArray<?>) v;
                if (arr.size() == 0) continue;
                final Object first = arr.valueAt(0);
                if (first == null) continue;
                for (Method m : first.getClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class
                            && m.getReturnType() == void.class) {
                        return arr;
                    }
                }
            } catch (Throwable ignored) {
                // 继续找
            }
        }
        return null;
    }
}
