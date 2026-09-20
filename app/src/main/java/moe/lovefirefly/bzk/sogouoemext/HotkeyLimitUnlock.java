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

    /** 功能开关（配置热更新）。 */
    private static volatile boolean sEnabled;

    private static volatile boolean sHooked;
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
        try {
            final Class<?> toast = Class.forName("android.widget.Toast");
            int n = 0;
            for (Method m : toast.getDeclaredMethods()) {
                if (!m.getName().equals("makeText")) continue;
                m.setAccessible(true);
                try {
                    module.hook(m).intercept(chain -> {
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
