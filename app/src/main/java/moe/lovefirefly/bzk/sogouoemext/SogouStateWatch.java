package moe.lovefirefly.bzk.sogouoemext;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;


/**
 * 开发期探针：键盘弹起后每 500ms 采一次 {@code LUa.F()}，只在值变化时打日志。
 *
 * <p>用途：有些状态变化（例如切到五笔）不一定触发 subtype 回调，只有靠采样才能看到
 * {@code F()} 的真实取值。五次里上限 5 分钟，成品必须关闭。
 */
final class SogouStateWatch {

    private static final String TAG = "BZK-SogouOEMExt";
    private static final long PERIOD_MS = 500;
    private static final int MAX_SAMPLES = 600;

    private SogouStateWatch() {}

    static void install(XposedModule module, ClassLoader cl) {
        try {
            final Class<?> svc = Class.forName(
                    "android.inputmethodservice.InputMethodService", false, cl);
            for (Method m : svc.getDeclaredMethods()) {
                if (!"setInputView".equals(m.getName())) continue;
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    start();
                    return chain.proceed();
                });
            }
            Log.i(TAG, "state watch installed");
        } catch (Throwable err) {
            Log.w(TAG, "state watch install failed: " + err);
        }
    }

    private static void start() {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            private int last = Integer.MIN_VALUE;
            private int n;

            @Override public void run() {
                final int v = SogouTranslator.rawLanguageState();
                if (v != last) {
                    Log.i(TAG, "watch F: " + last + " -> " + v);
                    last = v;
                }
                if (++n < MAX_SAMPLES) handler.postDelayed(this, PERIOD_MS);
            }
        });
    }
}
