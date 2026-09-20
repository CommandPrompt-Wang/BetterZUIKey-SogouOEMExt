package moe.lovefirefly.bzk.sogouoemext;

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
 * <p>分隔线<b>上方</b>的语言会被做成 subtype、进入框架的 subtype 列表
 * （= BZK 的默认轮转顺序：BZK 没给这个输入法单独排过顺序时，轮转就按它走）；
 * <b>下方</b>的不进 subtype，只能手动切。
 *
 * <p>存在 libxposed 的 remote preferences（模块 App 通过 XposedService 写入，
 * 这里的 hooked 进程只读）。
 */
final class LangConfig {

    private static final String TAG = "BZK-SogouOEMExt";
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

    /** 功能 S：引号/括号自动补全（true=保留自动补另一半，false=禁用）。 */
    private static final String KEY_AUTO_PAIR = "autoPair";
    /** 功能 9：物理键盘自动补全（true = 打字即补另一半）。 */
    private static final String KEY_PHYS_COMPLETE = "physComplete";
    /** 功能：「完整的 …… 和 ——」（true = 各两个，false = 各一个）。 */
    private static final String KEY_LONG_MARKS = "longMarks";
    /** 功能：选中文本时用配对标点包住选区（true = 包裹，false = 照旧替换掉选区）。 */
    private static final String KEY_WRAP_SELECTION = "wrapSelection";
    /** 功能：光标后已有闭字符时只移光标、不再补一个（true = 跳过）。 */
    private static final String KEY_CLOSE_SKIP = "closeSkip";
    /** 功能：Shift+方向键选区修复（宿主把 extend 当 move 用时由我们接管推进）。 */
    private static final String KEY_SHIFT_ARROW_REPAIR = "shiftArrowRepair";
    /** 功能：解除硬键盘设置里 Alt/Shift+字母 不让设快捷键的限制。 */
    private static final String KEY_UNLOCK_HOTKEY_LIMIT = "unlockHotkeyLimit";

    /** 功能 S 的自定义配对表：若干"前-后"配对依次排列（相邻两字符一组）。空=用输入法默认规则。 */
    private static final String KEY_AUTO_PAIR_TABLE = "autoPairTable";

    /**
     * 「填入建议项」用的建议配对串。
     *
     * <p>共 18 对：半角 / 全角、中文括号、中英引号各一行。它同时是编辑窗口的
     * hint、初始值，以及「填入建议项」填入的内容（完整串见 PRINCIPLE.md 3.8）。
     *
     * <p><b>串里带换行是故意的</b>：编辑窗口是多行框，分行只是给人看的分组。
     * 存储与 dump 都原样保留换行，真正解析成配对表前会先过一遍 {@link #cleanTable}。
     */
    static final String SUGGEST_PAIR_TABLE =
            "()[]{}（）［］｛｝＜＞\n"
            + "【】《》〈〉「」『』〖〗〔〕\n"
            + "\"\"''“”‘’";

    /**
     * 去掉配对串里的换行（分组用），**解析、签名、UI 校验三处共用同一个清洗**，
     * 免得出现"UI 按清洗后的长度校验、解析却按原始串切两字符"这种错位。
     *
     * <p>只去 {@code \r\n}，不去空格：空格有可能会是用户真想配对的字符。
     */
    static String cleanTable(String raw) {
        return raw == null ? "" : raw.replace("\r", "").replace("\n", "");
    }

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

    /** 功能 S：引号/括号自动补全，默认关（= 拿掉搜狗原生配对）。打开才补另一半。 */
    final boolean autoPair;

    /** 功能 S 的自定义配对串（相邻两字符一组）；空串 = 使用输入法默认匹配规则。 */
    final String autoPairTable;

    /** 功能 9：物理键盘自动补全，默认关。 */
    final boolean physComplete;

    /**
     * 功能：「完整的 …… 和 ——」，默认<b>开</b>。
     *
     * <p>开启 = 破折号/省略号各出两个（{@code ——} / {@code ……}，中文排版标准）；
     * 关闭 = 恢复搜狗原生的单出（{@code —} / {@code …}，模块原样放行）。
     * 没有配套的临时状态位/快捷键：这一项就是纯开关（用户定）。
     */
    final boolean longMarks;

    /**
     * 功能：「选中文本时用配对标点包住选区」，默认<b>开</b>。
     *
     * <p>开 = 选中 {@code abc} 打 {@code （} ⇒ {@code （abc）}（光标落在闭字符之后）；
     * 关 = 照旧把选区替换掉（搜狗原生行为）。纯开关，没有快捷键。
     */
    final boolean wrapSelection;

    /**
     * 功能：「光标后侧已有闭字符时只移光标」，默认<b>开</b>。
     *
     * <p>开 = {@code （xxx|} 打 {@code ）} ⇒ 只把光标移到闭字符之后（不再多出一个 {@code ）}）；
     * 关 = 照旧再输出一个闭字符。纯开关，没有快捷键。
     *
     * <p>判据里的"闭合符"用的是**实际上屏的那个字符**（过了全角/半角与智能标点），
     * 所以全角 {@code ）} 与半角 {@code )} 不会互相误判。
     */
    final boolean closeSkip;

    /**
     * 功能：「Shift+方向键选区修复」，默认<b>开</b>。
     *
     * <p>开 = 遇到把 {@code Selection.modify("extend")} 当"移光标"用的宿主
     * （Lexical / Android WebView，见 {@link ShiftArrowRepair}），由模块接管推进并补回选区；
     * 关 = 完全不动方向键，宿主原来什么样就什么样。纯开关，没有快捷键。
     */
    final boolean shiftArrowRepair;

    /**
     * 功能：「解除快捷键设置限制」，默认<b>关</b>。
     *
     * <p>开 = 搜狗「硬件键盘设置」录快捷键时不再拦 Alt/Shift 系组合（原本会弹
     * 「不支持设置Alt/Shift+字母/符号组合键」）。底层组合键模型本来就认 Shift/Alt，
     * 只是设置页的策略挡着；详见 {@link HotkeyLimitUnlock}。
     *
     * <p>默认关：放开后会遇到快捷键冲突、被上层应用抢占、以及 Shift+字母=大写 的问题。
     */
    final boolean unlockHotkeyLimit;

    /**
     * 配对表：**开字符 → 闭字符**（由 {@link #autoPairTable} 一次性解析）。
     *
     * <p>用 Map 而不是每次扫字符串：查表 O(1)，而且**方向性由结构本身保证** ——
     * 闭字符根本不是 key，所以"打闭字符反而补出开字符"这类问题不会发生。
     */
    final java.util.Map<Character, Character> pairMap;

    private LangConfig(List<String> order, int divider, boolean strict,
            boolean fullwidth, boolean smartPunct, boolean enPunct, boolean smartNumbering,
            int slashMode, boolean capitalInPinyin, boolean autoPair, String autoPairTable,
            boolean physComplete, boolean longMarks, boolean wrapSelection, boolean closeSkip,
            boolean shiftArrowRepair, boolean unlockHotkeyLimit) {
        this.order = order;
        this.divider = divider;
        this.strict = strict;
        this.fullwidth = fullwidth;
        this.smartPunct = smartPunct;
        this.enPunct = enPunct;
        this.smartNumbering = smartNumbering;
        this.slashMode = slashMode;
        this.capitalInPinyin = capitalInPinyin;
        this.autoPair = autoPair;
        this.autoPairTable = autoPairTable == null ? "" : autoPairTable;
        this.physComplete = physComplete;
        this.longMarks = longMarks;
        this.wrapSelection = wrapSelection;
        this.closeSkip = closeSkip;
        this.shiftArrowRepair = shiftArrowRepair;
        this.unlockHotkeyLimit = unlockHotkeyLimit;
        // 每 2 个字符一组：前 = 开字符（key），后 = 闭字符（value）。
        // 只有开字符会成为 key，方向性由此天然保证；末尾落单字符忽略；
        // 重复的开字符按"首次出现生效"（putIfAbsent），与旧的扫描行为一致。
        // 先清洗：表里允许用换行分组（见 SUGGEST_PAIR_TABLE），换行不能占配对位。
        final java.util.Map<Character, Character> m = new java.util.LinkedHashMap<>();
        final String t = cleanTable(this.autoPairTable);
        for (int i = 0; i + 1 < t.length(); i += 2) {
            m.putIfAbsent(t.charAt(i), t.charAt(i + 1));
        }
        this.pairMap = java.util.Collections.unmodifiableMap(m);
    }

    /** 开字符 → 闭字符；该字符不是开字符时返回 0。 */
    char closerFor(char c) {
        final Character v = pairMap.get(c);
        return v == null ? 0 : v;
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
            final boolean autoPair = sp.getBoolean(KEY_AUTO_PAIR, false);
            final String autoPairTable = sp.getString(KEY_AUTO_PAIR_TABLE, SUGGEST_PAIR_TABLE);
            final boolean physComplete = sp.getBoolean(KEY_PHYS_COMPLETE, false);
            final boolean longMarks = sp.getBoolean(KEY_LONG_MARKS, true);
            final boolean wrapSelection = sp.getBoolean(KEY_WRAP_SELECTION, true);
            final boolean closeSkip = sp.getBoolean(KEY_CLOSE_SKIP, true);
            final boolean shiftArrowRepair = sp.getBoolean(KEY_SHIFT_ARROW_REPAIR, true);
            final boolean unlockHotkeyLimit = sp.getBoolean(KEY_UNLOCK_HOTKEY_LIMIT, false);
            return parse(raw, div, strict, fullwidth, smartPunct, enPunct, smartNumbering,
                    slashMode, capitalInPinyin, autoPair, autoPairTable, physComplete, longMarks,
                    wrapSelection, closeSkip, shiftArrowRepair, unlockHotkeyLimit);
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
                + "&" + KEY_CAPITAL_PINYIN + "=" + sp.getBoolean(KEY_CAPITAL_PINYIN, true)
                + "&" + KEY_AUTO_PAIR + "=" + sp.getBoolean(KEY_AUTO_PAIR, false)
                + "&" + KEY_PHYS_COMPLETE + "=" + sp.getBoolean(KEY_PHYS_COMPLETE, false)
                + "&" + KEY_LONG_MARKS + "=" + sp.getBoolean(KEY_LONG_MARKS, true)
                + "&" + KEY_WRAP_SELECTION + "=" + sp.getBoolean(KEY_WRAP_SELECTION, true)
                + "&" + KEY_CLOSE_SKIP + "=" + sp.getBoolean(KEY_CLOSE_SKIP, true)
                + "&" + KEY_SHIFT_ARROW_REPAIR + "=" + sp.getBoolean(KEY_SHIFT_ARROW_REPAIR, true)
                + "&" + KEY_UNLOCK_HOTKEY_LIMIT + "=" + sp.getBoolean(KEY_UNLOCK_HOTKEY_LIMIT, false)
                // 配对串里可能出现 & 或 =，必须转义，否则会破坏 k=v&k=v 的行格式
                + "&" + KEY_AUTO_PAIR_TABLE + "=" + encodeTable(
                        sp.getString(KEY_AUTO_PAIR_TABLE, SUGGEST_PAIR_TABLE))
                // 期望状态位：只有 App 长按标题写过才带上（模块据此去设状态位）
                + (sp.contains(KEY_WANT_FULL)
                        ? "&" + KEY_WANT_FULL + "=" + sp.getBoolean(KEY_WANT_FULL, false) : "")
                + (sp.contains(KEY_WANT_ENP)
                        ? "&" + KEY_WANT_ENP + "=" + sp.getBoolean(KEY_WANT_ENP, false) : "")
                + (sp.contains(KEY_WANT_PHYS)
                        ? "&" + KEY_WANT_PHYS + "=" + sp.getBoolean(KEY_WANT_PHYS, true) : "")
                + "&" + KEY_WANT_SEQ + "=" + sp.getLong(KEY_WANT_SEQ, 0L);
    }

    /** App 侧「长按标题应急切换」写的期望状态位（键名与 App 侧一致）。 */
    static final String KEY_WANT_FULL = "wantFullwidth";
    static final String KEY_WANT_ENP = "wantEnPunct";
    static final String KEY_WANT_PHYS = "wantPhysComplete";
    /** 期望值的序号：只认比上次处理过的更新的一条（否则热键改回去会被旧请求"纠正"回来 ✗）。 */
    static final String KEY_WANT_SEQ = "wantSeq";

    /**
     * 只挑「期望状态位」三个键出来（有才返回）。
     *
     * <p>为什么不让 App 直接写状态位：状态位在**搜狗进程**的私有 prefs 里，
     * App 物理上写不到 ⇒ 只能写进 App 自己的配置，模块读到后去设。
     */
    static java.util.Map<String, Boolean> parseWants(String dump) {
        final java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        if (dump == null) return out;
        for (String kv : dump.split("&")) {
            final int i = kv.indexOf('=');
            if (i <= 0) continue;
            final String k = kv.substring(0, i);
            if (KEY_WANT_FULL.equals(k) || KEY_WANT_ENP.equals(k) || KEY_WANT_PHYS.equals(k)) {
                out.put(k, Boolean.parseBoolean(kv.substring(i + 1)));
            }
        }
        return out;
    }

    /** 解析期望值序号（0 = 没有/老格式 ⇒ 调用方应忽略整批期望值）。 */
    static long parseWantSeq(String dump) {
        if (dump == null) return 0L;
        for (String kv : dump.split("&")) {
            final int i = kv.indexOf('=');
            if (i > 0 && KEY_WANT_SEQ.equals(kv.substring(0, i))) {
                try { return Long.parseLong(kv.substring(i + 1)); } catch (Throwable t) { return 0L; }
            }
        }
        return 0L;
    }

    /** 配对串编解码：只做百分号转义，避免其中的 & 与 = 破坏配置行格式。 */
    static String encodeTable(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            return java.net.URLEncoder.encode(raw, "UTF-8");
        } catch (Throwable err) {
            return "";
        }
    }

    static String decodeTable(String enc) {
        if (enc == null || enc.isEmpty()) return "";
        try {
            return java.net.URLDecoder.decode(enc, "UTF-8");
        } catch (Throwable err) {
            return enc;
        }
    }

    /** 模块侧解析上面那行；失败返回 null。 */
    static LangConfig parseDump(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String order = LangSpec.DEFAULT_ORDER;
            int divider = LangSpec.DEFAULT_DIVIDER;
            boolean strict = false;
            boolean full = true;
            boolean smart = true;
            boolean en = true;
            boolean num = true;
            int slash = 0;
            boolean capital = true;
            boolean autoPair = false;
            String autoPairTable = SUGGEST_PAIR_TABLE;
            boolean physComplete = false;
            boolean longMarks = true;
            boolean wrapSelection = true;
            boolean closeSkip = true;
            boolean shiftArrowRepair = true;
            boolean unlockHotkeyLimit = false;
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
                    case KEY_AUTO_PAIR: autoPair = Boolean.parseBoolean(v); break;
                    case KEY_PHYS_COMPLETE: physComplete = Boolean.parseBoolean(v); break;
                    case KEY_LONG_MARKS: longMarks = Boolean.parseBoolean(v); break;
                    case KEY_WRAP_SELECTION: wrapSelection = Boolean.parseBoolean(v); break;
                    case KEY_CLOSE_SKIP: closeSkip = Boolean.parseBoolean(v); break;
                    case KEY_SHIFT_ARROW_REPAIR: shiftArrowRepair = Boolean.parseBoolean(v); break;
                    case KEY_UNLOCK_HOTKEY_LIMIT: unlockHotkeyLimit = Boolean.parseBoolean(v); break;
                    case KEY_AUTO_PAIR_TABLE: autoPairTable = decodeTable(v); break;
                    default: break;
                }
            }
            return parse(order, divider, strict, full, smart, en, num, slash, capital,
                    autoPair, autoPairTable, physComplete, longMarks, wrapSelection, closeSkip,
                    shiftArrowRepair, unlockHotkeyLimit);
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
        if (!a.signature().equals(b.signature())) {
            return "MISMATCH\n  stored: " + a.signature() + "\n  dumped: " + b.signature();
        }
        // 再单独查一遍配对表：签名比对**看不出**"构造时忘了清洗换行"这类 bug，
        // 因为签名本身就是拿清洗后的串拼的。只有真去比 map 才拦得住。
        final java.util.Map<Character, Character> expect = new java.util.LinkedHashMap<>();
        final String t = cleanTable(a.autoPairTable);
        for (int i = 0; i + 1 < t.length(); i += 2) {
            expect.putIfAbsent(t.charAt(i), t.charAt(i + 1));
        }
        if (!expect.equals(a.pairMap)) {
            return "PAIRMAP MISMATCH\n  expect: " + describe(expect)
                    + "\n  actual: " + describe(a.pairMap);
        }
        return "ok (pairMap=" + a.pairMap.size() + " pairs)";
    }

    /** 自检日志用：把配对表写成 {@code ()[]} 这样的紧凑串。 */
    private static String describe(java.util.Map<Character, Character> m) {
        final StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Character, Character> e : m.entrySet()) {
            sb.append(e.getKey()).append(e.getValue());
        }
        return sb.toString();
    }

    static LangConfig defaults() {
        return parse(LangSpec.DEFAULT_ORDER, LangSpec.DEFAULT_DIVIDER,
                false, true, true, true, true, 0, true, false, SUGGEST_PAIR_TABLE, false, true,
                true, true, true, false);
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
        return parse(raw, divider, strict, fullwidth, smartPunct, enPunct, smartNumbering,
                slashMode, capitalInPinyin, true, SUGGEST_PAIR_TABLE, true, true, true, true, true,
                false);
    }

    static LangConfig parse(String raw, int divider, boolean strict, boolean fullwidth,
            boolean smartPunct, boolean enPunct, boolean smartNumbering, int slashMode,
            boolean capitalInPinyin, boolean autoPair, String autoPairTable,
            boolean physComplete, boolean longMarks, boolean wrapSelection, boolean closeSkip,
            boolean shiftArrowRepair, boolean unlockHotkeyLimit) {
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
                slashMode, capitalInPinyin, autoPair, autoPairTable, physComplete, longMarks,
                wrapSelection, closeSkip, shiftArrowRepair, unlockHotkeyLimit);
    }

    /**
     * **暴露集合**：会被做成 subtype 交给框架的那批语言。
     *
     * <p>顺序固定为规范顺序（`LangSpec.ALL` 的顺序）—— **轮转顺序不归这里**，
     * 那是 BZK 的事（BZK 的「语言轮转顺序」页）。这里只决定"哪些语言能被切到"。
     * 存储上仍是 order 的前 divider 项（暴露的写前面），所以语义与旧版一致。
     */
    List<String> exposed() {
        return new ArrayList<>(order.subList(0, divider));
    }

    /** 不暴露的那批（只能从搜狗键盘手动切）。 */
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
                + "|cap=" + capitalInPinyin
                + "|pair=" + autoPair
                + "|phys=" + physComplete
                + "|long=" + longMarks
                + "|wrap=" + wrapSelection
                + "|closeskip=" + closeSkip
                + "|shiftarrow=" + shiftArrowRepair
                + "|unlockhotkey=" + unlockHotkeyLimit
                // 表内容进签名，改表才能触发热重载；用清洗后的形式，
                // 这样只改分组换行（配对结果不变）不会白热重载一次，日志也仍是单行
                + "|pairtbl=" + cleanTable(autoPairTable);
    }

    static List<String> defaultOrder() {
        return new ArrayList<>(Arrays.asList(LangSpec.DEFAULT_ORDER.split(",")));
    }

    /** 默认暴露的那批（拼音 + 英语）。 */
    static List<String> defaultExposed() {
        return new ArrayList<>(LangSpec.DEFAULT_EXPOSED);
    }
}
