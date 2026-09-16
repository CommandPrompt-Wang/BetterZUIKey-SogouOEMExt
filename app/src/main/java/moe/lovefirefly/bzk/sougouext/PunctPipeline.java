package moe.lovefirefly.bzk.sougouext;

/**
 * 标点管线：语义层（中文标点 / 键盘标点）→ 形式层（全角 / 半角）。
 *
 * <p>顺序按用户定义：{@code {开关3; 开关1，3 优先于1} -> 开关2}。
 * 之所以形式层必须最后：语义层决定"是哪个字符"，形式层只决定"它的宽窄形态"。
 *
 * <p><b>为什么要在提交环节做</b>：搜狗的中文标点映射是硬编码的（shared_prefs 84 键、
 * MMKV 二十多个库都没有开关），而且发生在它内部 —— 到 {@code commitText} 时
 * 已经是中文标点了。所以这里只能"反向还原"或"再归一"。
 */
final class PunctPipeline {

    /**
     * 语义层负责的标点：宽度层（全/半角）**不许碰**它们。
     *
     * <p>否则"半角模式"会把 ！？；：，（） 一起拉成 ASCII，等于绕开开关1/3 的语义决定。
     */
    private static final String PUNCT_OWNED = "！？；：，（）";

    /** ASCII → 中文（一一对应，索引对齐；{@code /} 与 {@code \} 都落到 、）。 */
    private static final String ASCII =
            ",./\\;:!?()[]<>\"'+-*={}|~@#%&^$_`";
    private static final String CHINESE =
            "，。、、；：！？（）【】《》“‘＋－＊＝｛｝｜～＠＃％＆＾＄＿·";

    /**
     * 特例：搜狗反引号键实际输出 {@code ·}（U+00B7），但别的路径可能给 {@code ｀}(FF40)。
     * 两者都还原成 ASCII 的反引号。
     */
    private static final String SPECIAL_CN = "·｀";
    private static final String SPECIAL_ASCII = "``";

    private PunctPipeline() {}

    /** 启动自检：两张表长度一致，且每一对都能双向对上（防手滑写错顺序）。 */
    static String selfCheck() {
        if (ASCII.length() != CHINESE.length()) {
            return "LENGTH MISMATCH ascii=" + ASCII.length() + " chinese=" + CHINESE.length();
        }
        final StringBuilder bad = new StringBuilder();
        for (int i = 0; i < ASCII.length(); i++) {
            final char a = ASCII.charAt(i);
            final char c = CHINESE.charAt(i);
            final String back = toAsciiPunct(String.valueOf(c), a);
            if (back == null || back.charAt(0) != a) {
                bad.append(a).append("->").append(c).append(' ');
            }
        }
        return bad.length() == 0 ? "ok (" + ASCII.length() + " pairs)" : "BAD " + bad;
    }

    /** 反向：中文标点 → ASCII；{@code \} 与 {@code /} 用"上一个物理键"消歧。 */
    static String toAsciiPunct(CharSequence src, char lastSlashKey) {
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '、' && (lastSlashKey == '/' || lastSlashKey == '\\')) {
                sb.append(lastSlashKey);
                changed = true;
                continue;
            }
            final int sp = SPECIAL_CN.indexOf(c);
            if (sp >= 0) {
                sb.append(SPECIAL_ASCII.charAt(sp));
                changed = true;
                continue;
            }
            final int idx = CHINESE.indexOf(c);
            if (idx < 0) {
                sb.append(c);
                continue;
            }
            sb.append(ASCII.charAt(idx));
            changed = true;
        }
        return changed ? sb.toString() : null;
    }

    /** 正向：ASCII → 中文标点（搜狗通常已经做过，这里用于归一/兜底）。 */
    static String toChinesePunct(CharSequence src) {
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            final int idx = ASCII.indexOf(c);
            if (idx < 0) {
                sb.append(c);
                continue;
            }
            sb.append(CHINESE.charAt(idx));
            changed = true;
        }
        return changed ? sb.toString() : null;
    }

    /**
     * 半角：全角 ASCII 区（FF01–FF5E）→ ASCII，全角空格 → 普通空格。
     *
     * <p>故意<b>不碰</b>中文标点（。、；：？！【】《》等）—— 那些由语义层负责，
     * 否则会掉进 ICU 那套日式半角（。→｡、→､）。
     */
    static String toHalfWidth(CharSequence src) {
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E && PUNCT_OWNED.indexOf(c) < 0) {
                sb.append((char) (c - 0xFEE0));      // 全角 ASCII 区 → 半角
                changed = true;
            } else if (c == 0x3000) {
                sb.append(' ');
                changed = true;
            } else {
                sb.append(c);
            }
        }
        return changed ? sb.toString() : null;
    }

    /**
     * 全角：ASCII 可打印 → FF01–FF5E，空格 → U+3000。
     *
     * <p>（Android 自带 {@code android.icu.text.Transliterator} 的
     * {@code Halfwidth-Fullwidth}/{@code Fullwidth-Halfwidth} 也能做，
     * 但它连中文标点一起改（。→｡），所以这里用等价的显式映射，行为可控。）
     */
    static String toFullWidth(CharSequence src) {
        final StringBuilder sb = new StringBuilder(src.length());
        boolean changed = false;
        for (int i = 0; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c >= 0x21 && c <= 0x7E) {
                sb.append((char) (c + 0xFEE0));
                changed = true;
            } else if (c == ' ') {
                sb.append((char) 0x3000);
                changed = true;
            } else {
                sb.append(c);
            }
        }
        return changed ? sb.toString() : null;
    }
}
