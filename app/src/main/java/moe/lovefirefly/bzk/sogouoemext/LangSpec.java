package moe.lovefirefly.bzk.sogouoemext;

import android.os.Build;
import android.view.inputmethod.InputMethodSubtype;

import java.util.Arrays;
import java.util.List;

/**
 * 三种语言的规范定义（OEM 只有这仨）。
 *
 * <p>subtype 身份 = locale + mode（{@link InputMethodSubtype#hashCode()} 覆盖两者），
 * 所以"拼音"和"五笔"虽然都是 zh-CN，靠 mode 区分。
 */
final class LangSpec {

    static final String PINYIN = "pinyin";
    static final String EN = "en";
    static final String WUBI = "wubi";

    static final List<String> ALL = Arrays.asList(PINYIN, EN, WUBI);

    /** 默认顺序：拼音、英语 轮转；五笔在分隔线下方（可手动切）。 */
    static final String DEFAULT_ORDER = PINYIN + "," + EN + "," + WUBI;
    static final int DEFAULT_DIVIDER = 2;

    private LangSpec() {}

    static String label(String id) {
        switch (id) {
            case PINYIN: return "拼音";
            case EN: return "英语";
            case WUBI: return "五笔";
            default: return id;
        }
    }

    static InputMethodSubtype subtype(String id) {
        switch (id) {
            case PINYIN: return build("zh-CN", "keyboard", "拼音", false);
            case EN:     return build("en-US", "keyboard", "English", true);
            case WUBI:   return build("zh-CN", "wubi", "五笔", false);
            default:     return null;
        }
    }

    /** subtype → 语言 id；不是我们的三个之一时返回 null。 */
    static String identify(InputMethodSubtype s) {
        if (s == null) return null;
        final String locale = s.getLocale();
        final String mode = s.getMode();
        if ("en-US".equals(locale)) return EN;
        if (!"zh-CN".equals(locale)) return null;
        if ("wubi".equals(mode)) return WUBI;
        if (mode == null || "keyboard".equals(mode)) return PINYIN;
        return null;
    }

    private static InputMethodSubtype build(String locale, String mode,
            String name, boolean asciiCapable) {
        final InputMethodSubtype.InputMethodSubtypeBuilder b =
                new InputMethodSubtype.InputMethodSubtypeBuilder()
                        .setSubtypeLocale(locale)
                        .setSubtypeMode(mode)
                        .setIsAsciiCapable(asciiCapable);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setSubtypeNameOverride(name);
        }
        return b.build();
    }
}
