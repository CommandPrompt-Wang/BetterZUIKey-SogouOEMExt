package moe.lovefirefly.bzk.sogouoemext;

import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 「解除快捷键设置限制」：让搜狗硬键盘设置里能设 Alt/Shift+字母 的快捷键。
 *
 * <p><b>病灶</b>（真机 2026-09-20 定位）：搜狗「硬件键盘设置」录快捷键时，输入框的
 * {@code TextWatcher#afterTextChanged} 会调一个「这个组合能不能用」的校验方法；不通过就查表
 * 弹 Toast（资源里就是那四条：不支持设置 Alt/Shift + 字母/符号组合键）。所以 **Alt/Shift 系
 * 整类被设置页挡掉**，而底层组合键模型其实是认 Shift/Alt 的。
 *
 * <p><b>怎么定位到它</b>：那句话是**资源 id**，代码里只有数字常量，硬扫代码要吃很多反汇编；
 * 改成运行时定位 —— 捕获一次「这条 Toast」的调用栈，栈里紧挨着
 * {@code afterTextChanged} 的那个 App 帧就是校验入口，然后用反射按签名
 * {@code (String)Z} 找到它并挂钩。
 *
 * <p><b>为什么不写死类名/方法名</b>：那个类是混淆名（本次是 {@code _sa}），跨版本会变。
 * 项目纪律：代码里不出现混淆名 —— 名字一律运行时从栈里拿。
 *
 * <p><b>代价 / 注意</b>：挂钩后该校验一律返回"可用"，所以冲突、被上层抢占、
 * Shift+字母（大写）这类问题要用户自己判断；UI 上也这么写了。
 */
final class HotkeyLimitUnlock {

    private static final String TAG = "BZK-SogouOEMExt";

    /** 开发期（一次性）：把设置页弹出的每个 Toast 连同调用栈都打出来（定位"空值冲突"校验在哪）。 */
    private static final boolean DEV_TOAST_TRACE = false;

    /** 功能开关（配置热更新）。 */
    private static volatile boolean sEnabled;

    private static volatile boolean sHooked;

    /** 「该…已被占用」冲突提示的资源 id（反汇编 {@code Ysa.a(String)Z} 得到）。 */
    private static final int RES_CONFLICT = 2131624296;

    /** 空值豁免：是否已经找到并挂上"冲突检查"那个函数。 */
    private static volatile boolean sConflictHooked;
    private static volatile boolean sConflictTried;
    private static volatile boolean sTried;
    private static volatile XposedModule sModule;
    private static volatile ClassLoader sLoader;

    private HotkeyLimitUnlock() {}

    static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /** 模块装载时调用：只挂一个框架 Toast 钩子做"一次性发现"。 */
    static void install(XposedModule module, ClassLoader cl) {
        if (module == null || cl == null) return;
        sModule = module;
        sLoader = cl;
        hookTextWatcher(module);
        try {
            final Class<?> toast = Class.forName("android.widget.Toast");
            int n = 0;
            for (Method m : toast.getDeclaredMethods()) {
                if (!m.getName().equals("makeText")) continue;
                m.setAccessible(true);
                try {
                    module.hook(m).intercept(chain -> {
                        if (DEV_TOAST_TRACE) {
                            final StringBuilder sb = new StringBuilder("toast-trace: arg1=");
                            for (int i = 0; i < m.getParameterCount() && i < 3; i++) {
                                sb.append(chain.getArg(i)).append(' ');
                            }
                            sb.append("| ");
                            int depth = 0;
                            for (StackTraceElement f : Thread.currentThread().getStackTrace()) {
                                final String c = f.getClassName();
                                if (c.startsWith("android.") || c.startsWith("java.")
                                        || c.startsWith("io.github.libxposed")
                                        || c.startsWith("de.robv")) {
                                    continue;
                                }
                                sb.append(c).append('#').append(f.getMethodName()).append("<-");
                                if (++depth >= 10) break;
                            }
                            Log.i(TAG, sb.toString());
                        }
                        // 冲突提示（"该…已被占用"）：找出报冲突的那个函数，只对"空值"豁免
                        if (sEnabled && !sConflictHooked && !sConflictTried
                                && chain.getArg(0) instanceof android.content.Context
                                && isConflictToast(chain)) {
                            try {
                                discoverConflictHook(Thread.currentThread().getStackTrace());
                            } catch (Throwable err) {
                                Log.w(TAG, "hotkeyfix: discover conflict failed: " + err);
                            }
                        }
                        // 只在"开关开着 && 还没找到校验入口"时做点事；其余一律原样放行
                        if (sEnabled && !sHooked && !sTried) {
                            try {
                                discoverAndHook(Thread.currentThread().getStackTrace());
                            } catch (Throwable err) {
                                Log.w(TAG, "hotkeyunlock: discover failed: " + err);
                            }
                        }
                        return chain.proceed();
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "hotkeyunlock: toast watch on " + n + " makeText overload(s)");
        } catch (Throwable err) {
            Log.w(TAG, "hotkeyunlock: install failed: " + err);
        }
    }

    /** 这条 Toast 是不是"…已被占用"冲突提示（资源 id 命中）。 */
    private static boolean isConflictToast(io.github.libxposed.api.XposedInterface.Chain chain) {
        for (int i = 1; i < 3; i++) {
            final Object a = chain.getArg(i);
            if (a instanceof Integer && (Integer) a == RES_CONFLICT) return true;
        }
        return false;
    }

    /**
     * 钩 {@code EditText.addTextChangedListener(TextWatcher)}：设置页给"录制快捷键"的输入框
     * 挂监听器时，那个 TextWatcher 的类里就带着冲突检查（真机 = {@code Ysa}）。
     *
     * <p>钩它的 {@code (String)Z} 方法并做**空值豁免**：空串不算"已被占用"。
     * （比从 Toast 栈里认更稳 —— 真机上那条冲突提示并不走 {@code Toast.makeText}。）
     */
    private static void hookTextWatcher(XposedModule module) {
        try {
            // addTextChangedListener 声明在 TextView 上（EditText 继承），所以要沿继承链找
            Class<?> edit = Class.forName("android.widget.EditText");
            java.util.List<Method> cands = new java.util.ArrayList<>();
            for (Class<?> c = edit; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals("addTextChangedListener")) continue;
                    if (m.getParameterCount() != 1) continue;
                    cands.add(m);
                }
            }
            for (Method m : cands) {
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    final Object w = chain.getArg(0);
                    if (w != null) {
                        try {
                            hookEmptyExempt(w.getClass());
                        } catch (Throwable err) {
                            Log.w(TAG, "hotkeyfix: watcher hook failed: " + err);
                        }
                    }
                    return chain.proceed();
                });
            }
            Log.i(TAG, "hotkeyfix: addTextChangedListener 钩子已装 x" + cands.size());
        } catch (Throwable err) {
            Log.w(TAG, "hotkeyfix: addTextChangedListener 钩子失败: " + err);
        }
    }

    /** 给"录制器类"里的 {@code (String)Z} 方法装空值豁免（同一类只装一次）。 */
    private static void hookEmptyExempt(Class<?> cls) {
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != String.class) continue;
            if (m.getReturnType() != boolean.class) continue;
            try {
                m.setAccessible(true);
                sModule.hook(m).intercept(chain -> {
                    final Object arg = chain.getArg(0);
                    // 无条件：空热键不参与冲突判断
                    if (arg instanceof String && ((String) arg).isEmpty()) {
                        Log.i(TAG, "hotkeyfix: 空热键不算冲突 → 放行");
                        return Boolean.FALSE;
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "hotkeyfix: 空值豁免已装 " + cls.getName() + "#" + m.getName()
                        + "(String)Z");
            } catch (Throwable err) {
                Log.w(TAG, "hotkeyfix: 装空值豁免失败 " + cls.getName() + "#" + m.getName()
                        + ": " + err);
            }
        }
    }

    /**
     * 从"冲突提示"的调用栈里找出那个 {@code (String)Z} 的检查函数，挂上**空值豁免**。
     *
     * <p>病灶（真机反汇编 {@code Ysa.a(String)Z}）：它拿"热键表里那条的组合"和"当前编辑框内容"
     * 做 {@code equalsIgnoreCase} —— 两个都是空串时也相等 ⇒ 报"该…已被占用"（∅∩∅≠∅）。
     * 所以这里只对**空参数**返回 false（不冲突），真冲突照旧。
     */
    private static void discoverConflictHook(StackTraceElement[] st) {
        if (st == null) return;
        for (StackTraceElement f : st) {
            final String cn = f.getClassName();
            if (cn.startsWith("android.") || cn.startsWith("java.")
                    || cn.startsWith("moe.lovefirefly") || cn.startsWith("io.github.libxposed")
                    || cn.startsWith("de.robv")) {
                continue;
            }
            if (hookConflictCheck(cn, f.getMethodName())) return;
        }
        sConflictTried = true;
        Log.w(TAG, "hotkeyfix: 没从冲突提示里认出检查函数");
    }

    private static boolean hookConflictCheck(String className, String methodName) {
        final XposedModule module = sModule;
        if (module == null) return false;
        try {
            final Class<?> cls = Class.forName(className, false, sLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != String.class) continue;
                if (m.getReturnType() != boolean.class) continue;
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    final Object arg = chain.getArg(0);
                    // 无条件：空热键本来就不该参与"冲突"判断（∅∩∅≠∅ 是搜狗自己的 bug）
                    if (arg instanceof String && ((String) arg).isEmpty()) {
                        Log.i(TAG, "hotkeyfix: 空热键不算冲突 → 放行");
                        return Boolean.FALSE;
                    }
                    return chain.proceed();
                });
                sConflictHooked = true;
                Log.i(TAG, "hotkeyfix: 已挂钩冲突检查 " + className + "#" + methodName
                        + "(String)Z —— 空值不再报\"已被占用\"");
                return true;
            }
        } catch (Throwable err) {
            Log.w(TAG, "hotkeyfix: hook conflict " + className + "#" + methodName + " failed: " + err);
        }
        return false;
    }

    /**
     * 从 Toast 调用栈里找校验入口：栈里出现 {@code afterTextChanged}（框架接口方法名，稳定）后，
     * 紧接着的 App 帧就是它，按签名 {@code (String)Z} 挂钩。
     */
    private static void discoverAndHook(StackTraceElement[] st) {
        if (st == null) return;
        int idx = -1;
        for (int i = 0; i < st.length; i++) {
            if ("afterTextChanged".equals(st[i].getMethodName())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;                       // 不是录制框那条路，别乱钩
        sTried = true;
        for (int i = idx - 1; i >= 0; i--) {
            final StackTraceElement f = st[i];
            final String cn = f.getClassName();
            if (cn.startsWith("android.") || cn.startsWith("java.")
                    || cn.startsWith("moe.lovefirefly") || cn.startsWith("io.github.libxposed")) {
                continue;
            }
            if (hookValidator(cn, f.getMethodName())) return;
        }
        Log.w(TAG, "hotkeyunlock: 找到 afterTextChanged 但没认出校验入口");
    }

    private static boolean hookValidator(String className, String methodName) {
        final XposedModule module = sModule;
        if (module == null) return false;
        try {
            final Class<?> cls = Class.forName(className, false, sLoader);
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                if (m.getReturnType() != boolean.class) continue;
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    if (sEnabled) {
                        Log.i(TAG, "hotkeyunlock: 放行组合键 → " + chain.getArg(0));
                        return Boolean.TRUE;
                    }
                    return chain.proceed();
                });
                sHooked = true;
                Log.i(TAG, "hotkeyunlock: 已挂钩校验入口 " + className + "#" + methodName
                        + "(String)Z —— 之后 Alt/Shift 组合键不再被拦");
                return true;
            }
        } catch (Throwable err) {
            Log.w(TAG, "hotkeyunlock: hook " + className + "#" + methodName + " failed: " + err);
        }
        return false;
    }
}
