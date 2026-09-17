package moe.lovefirefly.bzk.sogouoemext;

import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 开发期探针：验证"实际修改"通路（无需按键、无需弹键盘）。
 *
 * <p>静态分析得到的链路：
 * <pre>
 *   Qra.b(KeyEvent) : public static int   ← 组合键 → 命令 id
 *   eP.a(int) : hP                        ← 命令注册表（eP == WO.e）
 *   hP.a(WO, Bundle) : void               ← 真正执行
 * </pre>
 * 语言命令注册为：/hardKeyboardMode/SwitchChineseEnglishWuBi → 类 cta、
 * /hardKeyboardMode/SwitchEnglishChinese → 类 dta。因此只要 eP.a(id) 的类名是
 * cta/dta，就证明 id 是语言切换命令。
 */
public final class SogouCommandProbe {

    private static final String TAG = "BZK-SogouOEMExt";
    private static volatile boolean sDone;

    private SogouCommandProbe() {}

    public static void install(XposedModule module, ClassLoader cl) {
        try {
            final Class<?> svc = Class.forName(
                    "android.inputmethodservice.InputMethodService", false, cl);
            for (Method m : svc.getDeclaredMethods()) {
                boolean isCreate = m.getName().equals("onCreate");
                boolean isView = m.getName().equals("setInputView");
                if (!isCreate && !isView) continue;
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    final Object self = chain.getThisObject();
                    if (self != null) {
                        final String trigger = m.getName();
                        Thread t = new Thread(() -> {
                            try { Thread.sleep(trigger.equals("onCreate") ? 3000 : 1200); } catch (Throwable ignored) {}
                            probe(self, trigger);
                        }, "sogou-cmd-probe-" + trigger);
                        t.setDaemon(true);
                        t.start();
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "CMD hooked IMS#" + m.getName());
            }
        } catch (Throwable err) {
            Log.w(TAG, "CMD install failed: " + err);
        }
    }

    private static void probe(Object service, String trigger) {
        try {
            final Object wo = field(service, "b");          // coa.b : WO
            final Object ep = field(wo, "e");               // WO.e : eP（命令注册表）
            Log.i(TAG, "CMD [" + trigger + "] service=" + service.getClass().getName()
                    + " wo=" + (wo == null ? "null" : wo.getClass().getName())
                    + " eP=" + (ep == null ? "null" : ep.getClass().getName()));
            if (ep == null) return;

            // eP.b : SparseArray<hP>  —— 把注册表整个列出来
            final Object sparse = field(ep, "b");
            if (sparse == null) { Log.i(TAG, "CMD eP.b=null (registry not filled yet)"); return; }
            final int n = (Integer) sparse.getClass().getMethod("size").invoke(sparse);
            Log.i(TAG, "CMD eP.b size=" + n);
            final Method keyAt = sparse.getClass().getMethod("keyAt", int.class);
            final Method valueAt = sparse.getClass().getMethod("valueAt", int.class);
            for (int i = 0; i < n; i++) {
                final int id = (Integer) keyAt.invoke(sparse, i);
                final Object cmd = valueAt.invoke(sparse, i);
                final String cn = cmd == null ? "null" : cmd.getClass().getSimpleName();
                Log.i(TAG, "CMD cmd id=" + id + " class=" + cn
                        + (cn.equals("cta") || cn.equals("dta") ? "   <<<< 语言切换!" : ""));
            }
        } catch (Throwable err) {
            Log.w(TAG, "CMD probe failed: " + err + " cause=" + err.getCause());
        }
    }

    /** eP.a(int) → hP，返回命令类名（cta/dta 即语言切换命令）。 */
    private static String commandClass(Object ep, int id) {
        if (ep == null || id == 0) return "-";
        try {
            final Method a = ep.getClass().getDeclaredMethod("a", int.class);
            a.setAccessible(true);
            final Object cmd = a.invoke(ep, id);
            return cmd == null ? "null" : cmd.getClass().getName();
        } catch (Throwable err) {
            return "err:" + err.getClass().getSimpleName();
        }
    }

    private static KeyEvent makeEvent(int keyCode, int action) {
        final long now = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(now, now, action, keyCode, 0,
                KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON, 0, 42,
                KeyEvent.FLAG_FROM_SYSTEM);
        try {
            final Field src = KeyEvent.class.getDeclaredField("mSource");
            src.setAccessible(true);
            src.setInt(ev, InputDevice.SOURCE_KEYBOARD);
        } catch (Throwable ignored) {
        }
        return ev;
    }

    /** 字段在父类上（SogouIME 是空壳，字段都在 coa），必须沿继承链找。 */
    private static Object field(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                final Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                // 继续往上找
            } catch (Throwable err) {
                Log.d(TAG, "CMD field " + name + " failed: " + err);
                return null;
            }
        }
        Log.d(TAG, "CMD field " + name + " not found in hierarchy");
        return null;
    }

    private static int asInt(Object o) {
        return o instanceof Integer ? (Integer) o : -1;
    }
}
