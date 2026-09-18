package moe.lovefirefly.bzk.sogouoemext;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import java.util.ArrayList;
import java.util.List;

/**
 * 给输入法补上语言层（zh-CN / en-US）。
 *
 * <p>必须在**输入法自己的进程、以输入法自己的 uid** 调用：IMMS 侧
 * {@code InputMethodSettings.getNewAdditionalSubtypeMap()} 会做
 * {@code checkIfPackageBelongsToUid(callingUid, imePackage)}，而
 * {@code PackageManagerService$PackageManagerInternalImpl.isSameApp} 没有特权 UID 特判 ——
 * root(0) 和 system_server(1000) 都会被静默拒绝。
 *
 * <p>补完还要 {@code setExplicitlyEnabledInputMethodSubtypes} 显式启用，
 * 否则 additional subtype 不会出现在 enabled 列表里。
 */
public final class SubtypeInjector {

    public static final String TAG = "BZK-SogouOEMExt";
    private static final String SERVICE_ACTION = "android.view.InputMethod";
    private static final String IME_PERMISSION = "android.permission.BIND_INPUT_METHOD";

    private SubtypeInjector() {}

    /** 按配置把"分隔线上方"的语言做成 subtype（顺序 = 框架列表顺序 = BZK 的默认轮转顺序）。幂等。 */
    public static String apply(Context ctx, String packageName, LangConfig cfg) {
        try {
            return applyInternal(ctx, packageName, cfg);
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    private static String applyInternal(Context ctx, String packageName, LangConfig cfg) {
        if (ctx == null || packageName == null || cfg == null) return "bad-args";
        final String imeId = findImeId(ctx, packageName);
        if (imeId == null) return "not-an-ime";
        final InputMethodManager imm =
                (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return "no-imm";
        final InputMethodInfo imi = findIme(imm, imeId);
        if (imi == null) return "ime-not-listed";

        final List<String> want = cfg.rotation();
        final List<InputMethodSubtype> wantSubs = new ArrayList<>();
        for (String id : want) {
            final InputMethodSubtype s = LangSpec.subtype(id);
            if (s != null) wantSubs.add(s);
        }
        final List<String> have = enabledLangIds(imm, imi);
        if (have.equals(want)) return "ok: rotation=" + want + " (unchanged)";

        imm.setAdditionalInputMethodSubtypes(imeId,
                wantSubs.toArray(new InputMethodSubtype[0]));
        final int[] hashes = new int[wantSubs.size()];
        for (int i = 0; i < hashes.length; i++) hashes[i] = wantSubs.get(i).hashCode();
        imm.setExplicitlyEnabledInputMethodSubtypes(imeId, hashes);
        return "applied rotation=" + want + " (was " + have + ")";
    }

    /** 当前已启用里属于我们的语言，按框架给出的顺序。 */
    private static List<String> enabledLangIds(InputMethodManager imm, InputMethodInfo imi) {
        final List<String> out = new ArrayList<>();
        try {
            for (InputMethodSubtype s : imm.getEnabledInputMethodSubtypeList(imi, true)) {
                final String id = LangSpec.identify(s);
                if (id != null && !out.contains(id)) out.add(id);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String findImeId(Context ctx, String packageName) {
        final PackageManager pm = ctx.getPackageManager();
        final List<ResolveInfo> services = pm.queryIntentServices(new Intent(SERVICE_ACTION), 0);
        if (services == null) return null;
        for (ResolveInfo ri : services) {
            final ServiceInfo si = ri.serviceInfo;
            if (si == null) continue;
            if (!packageName.equals(si.packageName)) continue;
            if (!IME_PERMISSION.equals(si.permission)) continue;
            return new ComponentName(si.packageName, si.name).flattenToShortString();
        }
        return null;
    }

    private static InputMethodInfo findIme(InputMethodManager imm, String imeId) {
        final List<InputMethodInfo> list = imm.getInputMethodList();
        if (list == null) return null;
        for (InputMethodInfo i : list) {
            if (imeId.equals(i.getId())) return i;
        }
        return null;
    }

}
