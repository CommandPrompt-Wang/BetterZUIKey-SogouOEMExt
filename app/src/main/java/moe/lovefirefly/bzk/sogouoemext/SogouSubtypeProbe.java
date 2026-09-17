package moe.lovefirefly.bzk.sogouoemext;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * 开发期探针：测试**输入法自己的进程**能否改动框架当前 subtype。
 *
 * <p>这是"BZK 只做 next subtype"方案的关键前提：如果模块能把当前 subtype 挪到
 * 指定位置（例如手动切到五笔后挪到列表末项，让下一次 next 落回第一项），
 * BZK 就完全不需要知道任何配置。
 *
 * <p>为不改动用户语言，这里用"把当前 subtype 设成它自己"来测返回值。
 */
final class SogouSubtypeProbe {

    private static final String TAG = "BZK-SogouOEMExt";
    private static volatile boolean sDone;

    private SogouSubtypeProbe() {}

    static void install(XposedModule module, ClassLoader cl, Context ctx) {
        try {
            final Class<?> svc = Class.forName(
                    "android.inputmethodservice.InputMethodService", false, cl);
            for (Method m : svc.getDeclaredMethods()) {
                if (!"setInputView".equals(m.getName())) continue;
                m.setAccessible(true);
                module.hook(m).intercept(chain -> {
                    runOnce(ctx);
                    return chain.proceed();
                });
            }
            Log.i(TAG, "subtype probe installed");
        } catch (Throwable err) {
            Log.w(TAG, "subtype probe install failed: " + err);
        }
    }

    private static android.view.inputmethod.InputMethodInfo imiOf(InputMethodManager imm) {
        try {
            for (android.view.inputmethod.InputMethodInfo i : imm.getInputMethodList()) {
                if (i.getPackageName().equals("com.sohu.inputmethod.sogou.oem")) return i;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void runOnce(Context ctx) {
        if (sDone) return;
        sDone = true;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                final InputMethodManager imm =
                        (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm == null) {
                    Log.w(TAG, "subtype probe: no imm");
                    return;
                }
                Log.i(TAG, "subtype probe: uid=" + Process.myUid());
                InputMethodSubtype target = SogouTranslator.currentSubtype();
                Log.i(TAG, "subtype probe: cached current=" + (target == null ? "null"
                        : target.getLocale() + "/" + target.getMode()
                          + " hash=" + target.hashCode()));
                if (target == null) {
                    // 回调还没来过：退化为直接取 IME 的 subtype 列表
                    for (InputMethodSubtype s : imm.getEnabledInputMethodSubtypeList(
                            imiOf(imm), true)) {
                        Log.i(TAG, "subtype probe: enabled locale=" + s.getLocale()
                                + " mode=" + s.getMode() + " hash=" + s.hashCode());
                        if (target == null) target = s;
                    }
                }
                if (target == null) {
                    Log.w(TAG, "subtype probe: no subtype to test with");
                    return;
                }
                // 逐个试：只有"和当前不同"的那次才可能真的写，返回 true 即证明输入法进程有写回能力
                final List<InputMethodSubtype> all =
                        imm.getEnabledInputMethodSubtypeList(imiOf(imm), true);
                for (InputMethodSubtype s : all) {
                    final boolean r = imm.setCurrentInputMethodSubtype(s);
                    Log.i(TAG, "subtype probe: setCurrentInputMethodSubtype(" + s.getLocale()
                            + " hash=" + s.hashCode() + ") -> " + r);
                }
            } catch (Throwable err) {
                Log.w(TAG, "subtype probe failed: " + err);
            }
        }, 3000);
    }
}
