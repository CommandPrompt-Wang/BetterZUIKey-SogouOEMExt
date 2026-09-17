package moe.lovefirefly.bzk.sogouoemext;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedModule;

/**
 * 开发期探针：自动跑一遍 拼音 → 英语 → 五笔 → 拼音 的命令链，每步读一次 {@code F()}。
 *
 * <p>目的：确认三种语言都能被"命令 + keyboardEventId 参数"直接驱动，
 * 以及五笔状态下切英文到底要几步。
 */
public final class SogouSequenceProbe {

    private static final String TAG = "BZK-SogouOEMExt";
    private static final int EVENT_PINYIN = 1002;
    private static final int EVENT_WUBI = 1005;
    private static final int NO_EVENT = Integer.MIN_VALUE;
    private static volatile boolean sDone;

    private SogouSequenceProbe() {}

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
                        final Thread t = new Thread(() -> run(self), "sogou-seq-probe");
                        // 只跑一次，避免反复弹键盘时反复切语言
                        t.setDaemon(true);
                        t.start();
                    }
                    return chain.proceed();
                });
            }
            Log.i(TAG, "SEQ probe installed");
        } catch (Throwable err) {
            Log.w(TAG, "SEQ install failed: " + err);
        }
    }

    private static void run(Object service) {
        try {
            Thread.sleep(2500);
            final Object wo = field(service, "b");
            final Object ep = field(wo, "e");
            if (wo == null || ep == null) { Log.w(TAG, "SEQ wo/ep null"); return; }
            final Method lookup = ep.getClass().getDeclaredMethod("a", int.class);
            lookup.setAccessible(true);
            final Object cta = lookup.invoke(ep, -2);
            final Object dta = lookup.invoke(ep, -6);
            if (cta == null || dta == null) { Log.w(TAG, "SEQ commands null"); return; }
            final Method runCta = cta.getClass().getMethod("a", wo.getClass(), Bundle.class);
            final Method runDta = dta.getClass().getMethod("a", wo.getClass(), Bundle.class);
            runCta.setAccessible(true);
            runDta.setAccessible(true);

            log("start");
            step("cta+1002  -> 拼音", cta, runCta, wo, EVENT_PINYIN);
            step("cta+{}    -> 英语?", cta, runCta, wo, NO_EVENT);
            step("cta+1005  -> 五笔?", cta, runCta, wo, EVENT_WUBI);
            step("cta+{}    -> 从五笔按空参数", cta, runCta, wo, NO_EVENT);
            step("cta+1002  -> 拼音", cta, runCta, wo, EVENT_PINYIN);
            step("dta+{}    -> 从拼音按 dta", dta, runDta, wo, NO_EVENT);
            step("cta+{}    -> 英语?", cta, runCta, wo, NO_EVENT);
            step("cta+1005  -> 五笔?", cta, runCta, wo, EVENT_WUBI);
            step("dta+{}    -> 从五笔按 dta", dta, runDta, wo, NO_EVENT);
            step("cta+1002  -> 拼音", cta, runCta, wo, EVENT_PINYIN);
        } catch (Throwable err) {
            Log.w(TAG, "SEQ probe failed: " + err);
        }
    }

    private static void step(String label, Object cmd, Method run, Object wo, int eventId) {
        try {
            final Handler handler = new Handler(Looper.getMainLooper());
            final CountDownLatch latch = new CountDownLatch(1);
            final Throwable[] failure = new Throwable[1];
            handler.post(() -> {
                SogouTranslator.beginOurs();
                try {
                    run.invoke(cmd, wo, eventId == NO_EVENT ? new Bundle() : bundle(eventId));
                } catch (Throwable err) {
                    failure[0] = err;
                } finally {
                    SogouTranslator.endOurs();
                    latch.countDown();
                }
            });
            if (!latch.await(3, TimeUnit.SECONDS)) {
                Log.w(TAG, "SEQ " + label + " timeout");
                return;
            }
            if (failure[0] != null) {
                Log.w(TAG, "SEQ " + label + " failed: " + failure[0]
                        + " cause=" + failure[0].getCause());
                return;
            }
            Thread.sleep(700);
            log(label);
        } catch (Throwable err) {
            Log.w(TAG, "SEQ step failed: " + err);
        }
    }

    private static Bundle bundle(int eventId) {
        final Bundle b = new Bundle();
        b.putInt("keyboardEventId", eventId);
        return b;
    }

    private static void log(String label) {
        Log.i(TAG, "SEQ " + label + "  => F()=" + SogouTranslator.rawLanguageState());
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
