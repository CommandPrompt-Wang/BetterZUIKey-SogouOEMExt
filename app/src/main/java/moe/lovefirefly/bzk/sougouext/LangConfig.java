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
    private static final String KEY_STRICT = "strict";
    private static final String KEY_FULLWIDTH = "fullwidth";
    private static final String KEY_SMART_PUNCT = "smartPunct";
    private static final String KEY_EN_PUNCT = "enPunct";

    final List<String> order;
    final int divider;

    /** 只响应系统框架语言切换消息（= 拦掉搜狗自己那条硬键盘切换路）。 */
    final boolean strict;

    /** 开关2：全角模式（true=全角，false=半角）。搜狗没有相关开关，硬编码行为。 */
    final boolean fullwidth;

    /** 开关1：智能中文标点（按映射表输出中文标点）。 */
    final boolean smartPunct;

    /** 开关3：中英文标点切换（true=按键盘显示的标点输出，优先于开关1）。 */
    final boolean enPunct;

    private LangConfig(List<String> order, int divider, boolean strict,
            boolean fullwidth, boolean smartPunct, boolean enPunct) {
        this.order = order;
        this.divider = divider;
        this.strict = strict;
        this.fullwidth = fullwidth;
        this.smartPunct = smartPunct;
        this.enPunct = enPunct;
    }

    static LangConfig load(XposedModule module) {
        try {
            final SharedPreferences sp = module.getRemotePreferences(GROUP);
            final String raw = sp.getString(KEY_ORDER, LangSpec.DEFAULT_ORDER);
            final int div = sp.getInt(KEY_DIVIDER, LangSpec.DEFAULT_DIVIDER);
            final boolean strict = sp.getBoolean(KEY_STRICT, false);
            final boolean fullwidth = sp.getBoolean(KEY_FULLWIDTH, false);
            final boolean smartPunct = sp.getBoolean(KEY_SMART_PUNCT, true);
            final boolean enPunct = sp.getBoolean(KEY_EN_PUNCT, false);
            return parse(raw, div, strict, fullwidth, smartPunct, enPunct);
        } catch (Throwable err) {
            Log.w(TAG, "config load failed, using defaults: " + err);
            return parse(LangSpec.DEFAULT_ORDER, LangSpec.DEFAULT_DIVIDER,
                    false, false, true, false);
        }
    }

    /** 容错解析：未知/重复项丢弃，缺失项补到分隔线下方，保证三项齐全。 */
    static LangConfig parse(String raw, int divider) {
        return parse(raw, divider, false, false, true, false);
    }

    static LangConfig parse(String raw, int divider, boolean strict) {
        return parse(raw, divider, strict, false);
    }

    static LangConfig parse(String raw, int divider, boolean strict, boolean fullwidth) {
        return parse(raw, divider, strict, fullwidth, true, false);
    }

    static LangConfig parse(String raw, int divider, boolean strict,
            boolean fullwidth, boolean smartPunct, boolean enPunct) {
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
        return new LangConfig(list, d, strict, fullwidth, smartPunct, enPunct);
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
        return String.join(",", order) + "|" + divider + "|strict=" + strict
                + "|full=" + fullwidth + "|smart=" + smartPunct + "|en=" + enPunct;
    }

    static List<String> defaultOrder() {
        return new ArrayList<>(Arrays.asList(LangSpec.DEFAULT_ORDER.split(",")));
    }
}
