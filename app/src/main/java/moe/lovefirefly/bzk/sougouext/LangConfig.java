package moe.lovefirefly.bzk.sougouext;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * 语言顺序配置：一条可拖拽的顺序 + 一个分隔线位置。
 *
 * <p>分隔线<b>上方</b>的语言会被做成 subtype、参与 BZK 的 next 轮转；
 * <b>下方</b>的不进 subtype，只能手动切。
 *
 * <p>存在 libxposed 的 remote preferences（模块 App 通过 XposedService 写入，
 * 这里的 hooked 进程只读）。
 */
final class LangConfig {

    private static final String TAG = "SogouOemBridge";
    static final String GROUP = "sogou_lang";
    private static final String KEY_ORDER = "order";
    private static final String KEY_DIVIDER = "divider";

    final List<String> order;
    final int divider;

    private LangConfig(List<String> order, int divider) {
        this.order = order;
        this.divider = divider;
    }

    static LangConfig load(XposedModule module) {
        try {
            final SharedPreferences sp = module.getRemotePreferences(GROUP);
            final String raw = sp.getString(KEY_ORDER, LangSpec.DEFAULT_ORDER);
            final int div = sp.getInt(KEY_DIVIDER, LangSpec.DEFAULT_DIVIDER);
            return parse(raw, div);
        } catch (Throwable err) {
            Log.w(TAG, "config load failed, using defaults: " + err);
            return parse(LangSpec.DEFAULT_ORDER, LangSpec.DEFAULT_DIVIDER);
        }
    }

    /** 容错解析：未知/重复项丢弃，缺失项补到分隔线下方，保证三项齐全。 */
    static LangConfig parse(String raw, int divider) {
        final List<String> list = new ArrayList<>();
        if (raw != null) {
            for (String p : raw.split(",")) {
                final String id = p.trim();
                if (LangSpec.ALL.contains(id) && !list.contains(id)) list.add(id);
            }
        }
        for (String id : LangSpec.ALL) {
            if (!list.contains(id)) list.add(id);
        }
        int d = Math.max(0, Math.min(divider, list.size()));
        return new LangConfig(list, d);
    }

    /** 分隔线上方（轮转集合），按顺序。 */
    List<String> rotation() {
        return new ArrayList<>(order.subList(0, divider));
    }

    /** 分隔线下方（手动集合）。 */
    List<String> manualOnly() {
        return new ArrayList<>(order.subList(divider, order.size()));
    }

    boolean inRotation(String id) {
        return order.indexOf(id) >= 0 && order.indexOf(id) < divider;
    }

    String firstRotation() {
        return divider > 0 ? order.get(0) : null;
    }

    String lastRotation() {
        return divider > 0 ? order.get(divider - 1) : null;
    }

    String signature() {
        return String.join(",", order) + "|" + divider;
    }

    static List<String> defaultOrder() {
        return new ArrayList<>(Arrays.asList(LangSpec.DEFAULT_ORDER.split(",")));
    }
}
