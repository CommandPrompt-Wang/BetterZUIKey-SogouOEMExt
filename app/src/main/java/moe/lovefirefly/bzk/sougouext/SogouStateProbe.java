package moe.lovefirefly.bzk.sougouext;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedModule;

/**
 * 开发期探针：找"当前语言"的状态读取口。
 *
 * <p>做法：把候选对象（语言管理器 LUa 单例 + coa/WO 持有的管理器）上所有
 * 无参且返回 int/boolean/String 的方法快照一遍，然后<b>自己执行</b>一次
 * cta(中→英) 与 dta(英→中)，对比哪一项跟着翻转 —— 那就是语言状态，
 * 拿到它就能把 ③ 改成幂等（不一致才切），彻底不再需要时间抑制窗口。
 */
public final class SogouStateProbe {

    private static final String TAG = "BZK-SogouOEMExt";
    private static volatile boolean sDone;

    private SogouStateProbe() {}

    public static void install(XposedModule module, ClassLoader cl) {
        try {
            final Class<?> svc = Class.forName(
                    "android.inputmethodservice.InputMethodService", false, cl);
            for (Method m : svc.getDeclaredMethods()) {
                if (!m.getName().equals("setInputView")) continue;
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    final Object self = chain.getThisObject();
                    if (self != null && !sDone) {
                        sDone = true;
                        Thread t = new Thread(() -> run(self), "sogou-state-probe");
                        t.setDaemon(true);
                        t.start();
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "STATE hooked IMS#setInputView");
            }
        } catch (Throwable err) {
            Log.w(TAG, "STATE install failed: " + err);
        }
    }

    private static void run(Object service) {
        try {
            Thread.sleep(2000);
            final Object wo = field(service, "b");
            final Object ep = field(wo, "e");
            if (wo == null || ep == null) { Log.w(TAG, "STATE wo/ep null"); return; }

            final List<Object> subjects = new ArrayList<>();
            subjects.add(wo);
            subjects.add(field(service, "c"));   // iP
            subjects.add(field(service, "d"));   // Oaa
            subjects.add(field(service, "e"));   // uP
            subjects.add(field(service, "f"));   // wP
            subjects.add(field(service, "h"));   // _A
            try {
                final Class<?> lua = Class.forName("LUa", false, service.getClass().getClassLoader());
                final Object inst = lua.getMethod("B").invoke(null);
                if (inst != null) {
                    subjects.add(inst);
                    Log.i(TAG, "STATE LUa.B() ok: " + inst.getClass().getName());
                }
            } catch (Throwable err) {
                Log.i(TAG, "STATE LUa.B() unavailable: " + err);
            }

            final Map<String, String> s0 = snapshot(subjects);
            Log.i(TAG, "STATE baseline entries=" + s0.size());

            exec(ep, wo, -2);                    // cta : 中 → 英
            Thread.sleep(900);
            final Map<String, String> s1 = snapshot(subjects);
            diff("cta(-2)", s0, s1);

            exec(ep, wo, -6);                    // dta : 英 → 中
            Thread.sleep(900);
            final Map<String, String> s2 = snapshot(subjects);
            diff("dta(-6)", s1, s2);
        } catch (Throwable err) {
            Log.w(TAG, "STATE probe failed: " + err);
        }
    }

    private static Map<String, String> snapshot(List<Object> subjects) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (Object o : subjects) {
            if (o == null || out.size() > 400) continue;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                final String cn = c.getName();
                if (cn.startsWith("android.") || cn.startsWith("java.")) break;
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    final Class<?> rt = m.getReturnType();
                    if (rt != int.class && rt != boolean.class && rt != String.class) continue;
                    final String n = m.getName();
                    if (n.equals("hashCode") || n.equals("toString")) continue;
                    try {
                        m.setAccessible(true);
                        out.put(c.getSimpleName() + "#" + n, String.valueOf(m.invoke(o)));
                    } catch (Throwable ignored) {
                        // 读不到就算了
                    }
                }
            }
        }
        return out;
    }

    private static void diff(String label, Map<String, String> a, Map<String, String> b) {
        int n = 0;
        for (Map.Entry<String, String> e : b.entrySet()) {
            final String old = a.get(e.getKey());
            if (old == null || !old.equals(e.getValue())) {
                Log.i(TAG, "STATE " + label + "  " + e.getKey() + ": " + old + " -> " + e.getValue());
                n++;
            }
        }
        Log.i(TAG, "STATE " + label + " changed=" + n);
    }

    /** 必须在主线程执行：命令内部会更新 LiveData，后台线程会抛 IllegalStateException。 */
    private static void exec(Object ep, Object wo, int id) {
        try {
            final Method lookup = ep.getClass().getDeclaredMethod("a", int.class);
            lookup.setAccessible(true);
            final Object cmd = lookup.invoke(ep, id);
            if (cmd == null) { Log.w(TAG, "STATE command " + id + " null"); return; }
            final Method run = cmd.getClass().getMethod("a", wo.getClass(), Bundle.class);
            run.setAccessible(true);

            final Handler handler = new Handler(Looper.getMainLooper());
            final CountDownLatch latch = new CountDownLatch(1);
            final Throwable[] failure = new Throwable[1];
            handler.post(() -> {
                try {
                    run.invoke(cmd, wo, new Bundle());
                } catch (Throwable err) {
                    failure[0] = err;
                } finally {
                    latch.countDown();
                }
            });
            final boolean done = latch.await(3, TimeUnit.SECONDS);
            if (!done) {
                Log.w(TAG, "STATE exec " + id + " timeout");
            } else if (failure[0] != null) {
                Log.w(TAG, "STATE exec failed: " + failure[0] + " cause=" + failure[0].getCause());
            } else {
                Log.i(TAG, "STATE executed " + id + " (" + cmd.getClass().getSimpleName() + ")");
            }
        } catch (Throwable err) {
            Log.w(TAG, "STATE exec failed: " + err);
        }
    }

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
                return null;
            }
        }
        return null;
    }
}
