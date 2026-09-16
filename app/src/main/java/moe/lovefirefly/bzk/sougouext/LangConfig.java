package moe.lovefirefly.bzk.sougouext;

import android.content.SharedPreferences;
import android.database.Cursor;
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

    /** App 侧本地配置文件名（模块通过 ContentProvider 读取，不依赖 LSPosed 服务）。 */
    static final String PREFS_NAME = "sogouext_config";
    private static final String KEY_ORDER = "order";
    private static final String KEY_DIVIDER = "divider";
    private static final String KEY_STRICT = "strict";
    private static final String KEY_FULLWIDTH = "fullwidth";
    private static final String KEY_SMART_PUNCT = "smartPunct";
    private static final String KEY_EN_PUNCT = "enPunct";
    private static final String KEY_SMART_NUMBERING = "smartNumbering";
    private static final String KEY_SLASH_MODE = "slashMode";
    private static final String KEY_CAPITAL_PINYIN = "capitalInPinyin";

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

    /** 开关4：智能编号 —— 数字后面的 。/） 自动用半角（1.  2) 这种）。 */
    final boolean smartNumbering;

    /** 斜杠：0=关（搜狗原样，/ 与 \ 都出 、）；1=原样输出 /；2=原样输出 \。 */
    final int slashMode;

    /** 功能：中文态下大写字母也进拼音串（利用候选/英文补全），默认开。 */
    final boolean capitalInPinyin;

    private LangConfig(List<String> order, int divider, boolean strict,
            boolean fullwidth, boolean smartPunct, boolean enPunct, boolean smartNumbering,
            int slashMode, boolean capitalInPinyin) {
        this.order = order;
        this.divider = divider;
        this.strict = strict;
        this.fullwidth = fullwidth;
        this.smartPunct = smartPunct;
        this.enPunct = enPunct;
        this.smartNumbering = smartNumbering;
        this.slashMode = slashMode;
        this.capitalInPinyin = capitalInPinyin;
    }

    static LangConfig load(XposedModule module) {
        try {
            return load(module.getRemotePreferences(GROUP));
        } catch (Throwable err) {
            Log.w(TAG, "config load failed, using defaults: " + err);
            return defaults();
        }
    }

    /** App 侧与模块侧共用同一份读取逻辑（默认值只有一处）。 */
    static LangConfig load(SharedPreferences sp) {
        try {
            final String raw = sp.getString(KEY_ORDER, LangSpec.DEFAULT_ORDER);
            final int div = sp.getInt(KEY_DIVIDER, LangSpec.DEFAULT_DIVIDER);
            final boolean strict = sp.getBoolean(KEY_STRICT, false);
            final boolean fullwidth = sp.getBoolean(KEY_FULLWIDTH, true);
            final boolean smartPunct = sp.getBoolean(KEY_SMART_PUNCT, true);
            final boolean enPunct = sp.getBoolean(KEY_EN_PUNCT, true);
            final boolean smartNumbering = sp.getBoolean(KEY_SMART_NUMBERING, true);
            final int slashMode = sp.getInt(KEY_SLASH_MODE, 0);
            final boolean capitalInPinyin = sp.getBoolean(KEY_CAPITAL_PINYIN, true);
            return parse(raw, div, strict, fullwidth, smartPunct, enPunct, smartNumbering,
                    slashMode, capitalInPinyin);
        } catch (Throwable err) {
            Log.w(TAG, "config load failed, using defaults: " + err);
            return defaults();
        }
    }

    /** App 侧把配置序列化成一行 k=v&k=v（ContentProvider 用）。 */
    static String dump(SharedPreferences sp) {
        // 键名必须和 parseDump 里认的完全一致（曾经写成 full/smart/en/num/slash 导致全部读不到）
        return KEY_ORDER + "=" + sp.getString(KEY_ORDER, LangSpec.DEFAULT_ORDER)
                + "&" + KEY_DIVIDER + "=" + sp.getInt(KEY_DIVIDER, LangSpec.DEFAULT_DIVIDER)
                + "&" + KEY_STRICT + "=" + sp.getBoolean(KEY_STRICT, false)
                + "&" + KEY_FULLWIDTH + "=" + sp.getBoolean(KEY_FULLWIDTH, true)
                + "&" + KEY_SMART_PUNCT + "=" + sp.getBoolean(KEY_SMART_PUNCT, true)
                + "&" + KEY_EN_PUNCT + "=" + sp.getBoolean(KEY_EN_PUNCT, true)
                + "&" + KEY_SMART_NUMBERING + "=" + sp.getBoolean(KEY_SMART_NUMBERING, true)
                + "&" + KEY_SLASH_MODE + "=" + sp.getInt(KEY_SLASH_MODE, 0)
                + "&" + KEY_CAPITAL_PINYIN + "=" + sp.getBoolean(KEY_CAPITAL_PINYIN, true);
    }

    /** 模块侧解析上面那行；失败返回 null。 */
    static LangConfig parseDump(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String order = LangSpec.DEFAULT_ORDER;
            int divider = LangSpec.DEFAULT_DIVIDER;
            boolean strict = false;
            boolean full = false;
            boolean smart = true;
            boolean en = false;
            boolean num = true;
            int slash = 0;
            boolean capital = true;
            for (String kv : s.split("&")) {
                final int i = kv.indexOf('=');
                if (i <= 0) continue;
                final String k = kv.substring(0, i);
                final String v = kv.substring(i + 1);
                switch (k) {
                    case KEY_ORDER: order = v; break;
                    case KEY_DIVIDER: divider = Integer.parseInt(v); break;
                    case KEY_STRICT: strict = Boolean.parseBoolean(v); break;
                    case KEY_FULLWIDTH: full = Boolean.parseBoolean(v); break;
                    case KEY_SMART_PUNCT: smart = Boolean.parseBoolean(v); break;
                    case KEY_EN_PUNCT: en = Boolean.parseBoolean(v); break;
                    case KEY_SMART_NUMBERING: num = Boolean.parseBoolean(v); break;
                    case KEY_SLASH_MODE: slash = Integer.parseInt(v); break;
                    case KEY_CAPITAL_PINYIN: capital = Boolean.parseBoolean(v); break;
                    default: break;
                }
            }
            return parse(order, divider, strict, full, smart, en, num, slash, capital);
        } catch (Throwable err) {
            Log.w(TAG, "parseDump failed: " + err);
            return null;
        }
    }

    /** 模块侧：优先从 App 的 ContentProvider 读（不依赖 LSPosed 服务）。失败返回 null。 */
    static LangConfig loadFromProvider(android.content.ContentResolver cr) {
        return parseDump(readProvider(cr));
    }

    /** 读原始串（调试/日志用）；失败返回 null。 */
    static String readProvider(android.content.ContentResolver cr) {
        if (cr == null) {
            Log.d(TAG, "provider: no resolver");
            return null;
        }
        try (Cursor c = cr.query(ConfigProvider.URI, null, null, null, null)) {
            if (c == null) {
                Log.d(TAG, "provider: null cursor");
                return null;
            }
            if (c.moveToFirst()) return c.getString(0);
            Log.d(TAG, "provider: empty cursor");
        } catch (Throwable err) {
            Log.d(TAG, "provider read failed: " + err);
        }
        return null;
    }

    /** 自检：dump(parse(x)) 应该等于 x 的关键项（防止键名两边写歪）。 */
    static String dumpRoundTripCheck(SharedPreferences sp) {
        final LangConfig a = load(sp);
        final LangConfig b = parseDump(dump(sp));
        if (b == null) return "parseDump returned null";
        return a.signature().equals(b.signature()) ? "ok"
                : "MISMATCH\n  stored: " + a.signature() + "\n  dumped: " + b.signature();
    }

    static LangConfig defaults() {
        return parse(LangSpec.DEFAULT_ORDER, LangSpec.DEFAULT_DIVIDER,
                false, false, true, false, true);
    }

    /** 容错解析：未知/重复项丢弃，缺失项补到分隔线下方，保证三项齐全。 */
    static LangConfig parse(String raw, int divider) {
        return parse(raw, divider, false, false, true, false, true, 0);
    }

    static LangConfig parse(String raw, int divider, boolean strict) {
        return parse(raw, divider, strict, false);
    }

    static LangConfig parse(String raw, int divider, boolean strict, boolean fullwidth) {
        return parse(raw, divider, strict, fullwidth, true, false, true);
    }

    static LangConfig parse(String raw, int divider, boolean strict,
            boolean fullwidth, boolean smartPunct, boolean enPunct) {
        return parse(raw, divider, strict, fullwidth, smartPunct, enPunct, true, 0);
    }

    static LangConfig parse(String raw, int divider, boolean strict,
            boolean fullwidth, boolean smartPunct, boolean enPunct, boolean smartNumbering) {
        return parse(raw, divider, strict, fullwidth, smartPunct, enPunct, smartNumbering, 0);
    }

    static LangConfig parse(String raw, int divider, boolean strict, boolean fullwidth,
            boolean smartPunct, boolean enPunct, boolean smartNumbering, int slashMode) {
        return parse(raw, divider, strict, fullwidth, smartPunct, enPunct, smartNumbering,
                slashMode, true);
    }

    static LangConfig parse(String raw, int divider, boolean strict, boolean fullwidth,
            boolean smartPunct, boolean enPunct, boolean smartNumbering, int slashMode,
            boolean capitalInPinyin) {
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
        return new LangConfig(list, d, strict, fullwidth, smartPunct, enPunct, smartNumbering,
                slashMode, capitalInPinyin);
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
                + "|full=" + fullwidth + "|smart=" + smartPunct + "|en=" + enPunct
                + "|num=" + smartNumbering + "|slash=" + slashMode
                + "|cap=" + capitalInPinyin;
    }

    static List<String> defaultOrder() {
        return new ArrayList<>(Arrays.asList(LangSpec.DEFAULT_ORDER.split(",")));
    }
}
