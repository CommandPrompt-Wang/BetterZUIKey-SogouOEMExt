package moe.lovefirefly.bzk.sogouoemext;

import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * 开发期探针：抓"全角标点"是在哪一步产生的。
 *
 * <p>观察三处：
 * <ol>
 *   <li>{@code InputMethodService#sendKeyChar(char)} —— 老式路径；</li>
 *   <li>当前 {@link InputConnection} 的 {@code commitText}/{@code setComposingText}
 *       —— 实际提交给应用的内容（IME 进程内调用，可直接钩）；</li>
 *   <li>连接对象的真实类名（决定钩哪个类）。</li>
 * </ol>
 */
final class SogouPunctProbe {

    private static final String TAG = "BZK-SogouOEMExt";

    private SogouPunctProbe() {}

    static void install(XposedModule module, ClassLoader cl, Object service) {
        if (service == null) return;
        try {
            for (Method m : service.getClass().getMethods()) {
                if (m.getName().equals("sendKeyChar") && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    module.hook(m).intercept(chain -> {
                        final Object c = chain.getArg(0);
                        Log.i(TAG, "PUNCT sendKeyChar(" + c + ") U+"
                                + Integer.toHexString(((Character) c)));
                        return chain.proceed();
                    });
                    Log.i(TAG, "PUNCT hooked sendKeyChar");
                }
            }
        } catch (Throwable err) {
            Log.w(TAG, "PUNCT sendKeyChar hook failed: " + err);
        }
        try {
            final Method get = service.getClass().getMethod("getCurrentInputConnection");
            final Object ic = get.invoke(service);
            if (ic == null) {
                Log.w(TAG, "PUNCT no input connection yet");
                return;
            }
            Log.i(TAG, "PUNCT connection class = " + ic.getClass().getName());
            for (Class<?> c = ic.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    final String n = m.getName();
                    if (!n.equals("commitText") && !n.equals("setComposingText")
                            && !n.equals("sendKeyEvent")) continue;
                    m.setAccessible(true);
                    try {
                        module.hook(m).intercept(chain -> {
                            final StringBuilder sb = new StringBuilder("PUNCT " + n + "(");
                            for (int i = 0; i < chain.getArgs().size(); i++) {
                                final Object a = chain.getArg(i);
                                sb.append(i == 0 ? String.valueOf(a) : "").append(i == 0 ? "" : "");
                            }
                            Log.i(TAG, sb.append(")").toString());
                            return chain.proceed();
                        });
                        Log.i(TAG, "PUNCT hooked " + c.getSimpleName() + "#" + n);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable err) {
            Log.w(TAG, "PUNCT connection hook failed: " + err);
        }
    }
}
