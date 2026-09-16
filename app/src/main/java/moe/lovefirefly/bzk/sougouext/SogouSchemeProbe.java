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
 * 开发期探针（两件事）：
 *
 * <ol>
 *   <li><b>找"拼音/五笔"方案状态变量</b>：自动执行 切五笔(cta + keyboardEventId=1005) 与
 *       切拼音(cta + keyboardEventId=1002)，diff 出跟着变的字段。</li>
 *   <li><b>测 {@code InputMethodService.switchToNextInputMethod(true)}</b>：
 *       这是"把 marker 在 subtype 列表里挪一格"的候选手段，用于实现
 *       "从分隔线下方切出时回到第一项"。</li>
 * </ol>
 */
public final class SogouSchemeProbe {

    private static final String TAG = "SogouOemBridge";
    private static final int EVENT_PINYIN = 1002;
    private static final int EVENT_WUBI = 1005;
    private static volatile boolean sDone;

    private SogouSchemeProbe() {}

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
                        final Thread t = new Thread(() -> run(self), "sogou-scheme-probe");
                        t.setDaemon(true);
                        t.start();
                    }
                    return chain.proceed();
                });
            }
            Log.i(TAG, "SCHEME probe installed");
        } catch (Throwable err) {
            Log.w(TAG, "SCHEME install failed: " + err);
        }
    }

    private static void run(Object service) {
        try {
            Thread.sleep(2500);
            final Object wo = field(service, "b");
            final Object ep = field(wo, "e");
            if (wo == null || ep == null) { Log.w(TAG, "SCHEME wo/ep null"); return; }

            final List<Object> subjects = new ArrayList<>();
            subjects.add(wo);
            for (String f : new String[]{"c", "d", "e", "f", "h"}) subjects.add(field(service, f));
            try {
                final Class<?> lua = Class.forName("LUa", false,
                        service.getClass().getClassLoader());
                subjects.add(lua.getMethod("B").invoke(null));
            } catch (Throwable err) {
                Log.i(TAG, "SCHEME LUa.B() unavailable: " + err);
            }

            final Map<String, String> s0 = snapshot(subjects);
            exec(ep, wo, -2, EVENT_WUBI);        // 切五笔
            Thread.sleep(1000);
            final Map<String, String> s1 = snapshot(subjects);
            diff("to-wubi", s0, s1);

            exec(ep, wo, -2, EVENT_PINYIN);      // 切拼音
            Thread.sleep(1000);
            final Map<String, String> s2 = snapshot(subjects);
            diff("to-pinyin", s1, s2);

            // --- switchToNextInputMethod(true)：能否让框架 subtype 前进一格 ---
            final Object before = SogouTranslator.currentSubtype();
            Log.i(TAG, "SWITCH before=" + desc(before));
            try {
                final Method m = service.getClass().getMethod(
                        "switchToNextInputMethod", boolean.class);
                m.setAccessible(true);
                final Object r = m.invoke(service, true);
                Log.i(TAG, "SWITCH switchToNextInputMethod(true) -> " + r);
            } catch (Throwable err) {
                Log.w(TAG, "SWITCH call failed: " + err);
            }
            Thread.sleep(1500);
            Log.i(TAG, "SWITCH after=" + desc(SogouTranslator.currentSubtype()));
        } catch (Throwable err) {
            Log.w(TAG, "SCHEME probe failed: " + err);
        }
    }

    private static String desc(Object subtype) {
        if (!(subtype instanceof android.view.inputmethod.InputMethodSubtype)) return "null";
        final android.view.inputmethod.InputMethodSubtype s =
                (android.view.inputmethod.InputMethodSubtype) subtype;
        return s.getLocale() + "/" + s.getMode() + " hash=" + s.hashCode();
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
                Log.i(TAG, "SCHEME " + label + "  " + e.getKey() + ": " + old + " -> " + e.getValue());
                n++;
            }
        }
        Log.i(TAG, "SCHEME " + label + " changed=" + n);
    }

    private static void exec(Object ep, Object wo, int id, int eventId) {
        try {
            final Method lookup = ep.getClass().getDeclaredMethod("a", int.class);
            lookup.setAccessible(true);
            final Object cmd = lookup.invoke(ep, id);
            if (cmd == null) { Log.w(TAG, "SCHEME command " + id + " null"); return; }
            final Method run = cmd.getClass().getMethod("a", wo.getClass(), Bundle.class);
            run.setAccessible(true);
            final Bundle args = new Bundle();
            args.putInt("keyboardEventId", eventId);

            final Handler handler = new Handler(Looper.getMainLooper());
            final CountDownLatch latch = new CountDownLatch(1);
            final Throwable[] failure = new Throwable[1];
            handler.post(() -> {
                try {
                    run.invoke(cmd, wo, args);
                } catch (Throwable err) {
                    failure[0] = err;
                } finally {
                    latch.countDown();
                }
            });
            if (!latch.await(3, TimeUnit.SECONDS)) {
                Log.w(TAG, "SCHEME exec " + eventId + " timeout");
            } else if (failure[0] != null) {
                Log.w(TAG, "SCHEME exec failed: " + failure[0] + " cause=" + failure[0].getCause());
            } else {
                Log.i(TAG, "SCHEME executed cta event=" + eventId);
            }
        } catch (Throwable err) {
            Log.w(TAG, "SCHEME exec failed: " + err);
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
            } catch (Throwable err) {
                return null;
            }
        }
        return null;
    }
}
