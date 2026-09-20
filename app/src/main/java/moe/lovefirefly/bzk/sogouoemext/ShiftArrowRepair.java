package moe.lovefirefly.bzk.sogouoemext;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.icu.text.BreakIterator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.Locale;

/**
 * Shift+方向键「选区修复」（真机 2026-09-20 定位并修复）。
 *
 * <p><b>病灶</b>：一部分宿主不用浏览器原生行为做 Shift+方向键选中，而是
 * {@code preventDefault()} 之后自己调 {@code Selection.modify("extend", …, "character")}。
 * 真机数据：DOM 事件 {@code shiftKey=true}、裸 contenteditable 原生选中正常，但这条
 * {@code modify("extend")} 在 Android WebView 上**退化成移动光标** —— 每按一下只把光标挪一格
 * （{@code onUpdateSelection(n,n)}，start==end ⇒ 无选中）。DSH 的输入框（Lexical 富文本）
 * 就是这种宿主，它连 Ctrl+Shift 也只按字符走。
 *
 * <p><b>三档行为</b>：{@code Shift+←/→} 逐字扩选、{@code Ctrl+Shift+←/→} 按词扩选、
 * {@code Ctrl+←/→}（不按 Shift）按词移光标。判定方式见下。
 *
 * <p><b>判定方式：逐次核对，不做"第一下定性"</b>。每次放行水平方向键之前，我们先自己算一个
 * 「期望焦点」；宿主回报的焦点与它一致就继续不插手，不一致就当场接管，按我们的期望补选区，
 * 之后吞掉按键自己推进。这样：
 * <ul>
 *   <li>原生宿主（地址栏 EditText）每一下都和我们算的一致 ⇒ 一个字都不碰；</li>
 *   <li>Lexical 这种反向会"给一格假区间"的宿主也会被识破（它给 25，我们期望 24 ⇒ 接管）。</li>
 * </ul>
 * 早期版本有两个坑，都记在这里：
 * <ul>
 *   <li>用"第一下有没有区间"定性 ⇒ 被 Lexical 反向的假区间骗过去，反向不工作；</li>
 *   <li>只比对"焦点位置对不对" ⇒ <b>单独 Shift+方向键</b>时，我们期望"逐字"、坏宿主干的
 *       也正好是"移一格"，两边数字一样 ⇒ 误判成好宿主，永远不接管。所以"要选区却收到塌的
 *       选区"必须直接算失败（用户 2026-09-20 实测："单独 shift、单独 ctrl 不工作，合起来可以"）。</li>
 * </ul>
 *
 * <p><b>步长</b>：无 Ctrl/Alt ⇒ 逐字（±1）；带 Ctrl/Alt ⇒ **按词**，用公开 API
 * {@link android.icu.text.BreakIterator#getWordInstance(Locale)}（ICU 分词，内置中日韩词典，
 * 与系统原生 Ctrl+方向键同一套引擎）—— 不需要 jieba，也不用碰搜狗自己的词表
 * （那是它拼音引擎的词典，跟"选区边界"无关）。取词窗口优先用 {@code getExtractedText}
 * （整段文本 + 绝对起点，映射最干净），拿不到再退回
 * {@code before(相对选区起点) + getSelectedText() + after(相对选区终点)} 拼绝对连续窗口。
 *
 * <p>垂直键（↑/↓/Home/End/翻页）算不出屏幕行 ⇒ 继续放行让宿主挪，只在
 * {@link #onSelection} 里把「锚点 → 宿主挪到的位置」补成区间。
 */
final class ShiftArrowRepair {

    private static final String TAG = "BZK-SogouOEMExt";

    /** 不干预：原样放行按键。 */
    static final int PASS = 0;
    /** 已接管：按键不要给宿主。 */
    static final int CONSUMED = 1;

    /** 取词窗口大小（caret 两侧各取这么多字符）。 */
    private static final int WINDOW = 256;
    /** 算不出期望焦点（宿主不给文本）。 */
    private static final int UNKNOWN = Integer.MIN_VALUE;
    /** 开发期：打印每步的取文本细节（成品关闭；接管/补选区两条日志保留，排查够用且不刷屏）。 */
    private static final boolean DEV_LOG = false;
    /** 功能开关（来自配置，默认开）：关掉就完全不动方向键。 */
    private static volatile boolean sEnabled = true;

    /** 宿主报回的选区（来自 onUpdateSelection，绝对偏移）。 */
    private static volatile int sSelStart = -1, sSelEnd = -1;
    /** 本次序列锚点；-1 = 无序列。 */
    private static volatile int sAnchor = -1;
    /** 我们跟踪的焦点位置。 */
    private static volatile int sFocus = -1;
    /** 本次序列方向：-1 = ←，+1 = →（仅水平键有意义）。 */
    private static volatile int sDir;
    /** 本次序列是否按词（带 Ctrl/Alt）。 */
    private static volatile boolean sWord;
    /** 本次序列是否在扩选（带 Shift）；false = 只按词移光标（Ctrl+方向键）。 */
    private static volatile boolean sSelect = true;
    /** 最近一次按键是否垂直类（垂直键由宿主自己挪，我们只补区间）。 */
    private static volatile boolean sVertical;
    /** 本次序列的期望焦点（放行后等宿主回报来核对）。 */
    private static volatile int sExpected = UNKNOWN;
    /** 序列有效期。 */
    private static volatile long sDeadline;
    /** 已接管，本次序列由我们推进。 */
    private static volatile boolean sActive;
    /** 已吞掉 key down ⇒ 配对的 key up 也要吞。 */
    private static volatile boolean sConsumedDown;
    /** 我们自己 setSelection 引发的回调。 */
    private static volatile boolean sSelfUpdate;
    private static volatile long sSelfUpdateAt;

    private static final long WINDOW_MS = 700L;
    private static final long SELF_MS = 250L;

    private ShiftArrowRepair() {}

    /** 配置热更新入口：关掉时顺手结束当前序列。 */
    static void setEnabled(boolean enabled) {
        if (sEnabled != enabled) Log.i(TAG, "selshift: 开关 -> " + enabled);
        sEnabled = enabled;
        if (!enabled) reset();
    }

    private static boolean isHorizontal(int kc) {
        return kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static boolean isMovement(int kc) {
        return isHorizontal(kc)
                || kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN
                || kc == KeyEvent.KEYCODE_MOVE_HOME || kc == KeyEvent.KEYCODE_MOVE_END
                || kc == KeyEvent.KEYCODE_PAGE_UP || kc == KeyEvent.KEYCODE_PAGE_DOWN;
    }

    private static void reset() {
        sAnchor = -1;
        sFocus = -1;
        sDir = 0;
        sWord = false;
        sSelect = true;
        sVertical = false;
        sExpected = UNKNOWN;
        sActive = false;
        sConsumedDown = false;
    }

    /** 宿主报回的选区即为"当前光标"（有区间时取焦点端 = 末端）。 */
    private static int currentCaret() {
        if (sSelStart < 0 && sSelEnd < 0) return -1;
        if (sSelStart < 0) return sSelEnd;
        if (sSelEnd < 0) return sSelStart;
        return sSelEnd;
    }

    /** 我们自己刚补的那次选区回调（供 AutoPairHook 判断"这不是用户操作"）。 */
    static boolean isSelfUpdate() {
        return sSelfUpdate && SystemClock.uptimeMillis() - sSelfUpdateAt < SELF_MS;
    }

    /**
     * 按键钩子入口。
     *
     * @return {@link #PASS} = 照常放行；{@link #CONSUMED} = 已接管，不要给宿主
     */
    static int onKey(Object service, boolean down, Object evArg) {
        if (!sEnabled || !(evArg instanceof KeyEvent)) return PASS;
        final KeyEvent ev = (KeyEvent) evArg;
        final int kc = ev.getKeyCode();
        final long now = SystemClock.uptimeMillis();

        if (kc == KeyEvent.KEYCODE_SHIFT_LEFT || kc == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (!down) reset();                       // Shift 抬起 ⇒ 序列结束
            return PASS;
        }
        if (!isMovement(kc)) {
            if (down) reset();                        // 打了别的键 ⇒ 序列结束
            return PASS;
        }

        if (!down) {
            if (sConsumedDown) {                      // 吞掉配对的抬起，保持对称
                sConsumedDown = false;
                return CONSUMED;
            }
            return PASS;
        }

        final int meta = ev.getMetaState();
        final boolean shift = (meta & KeyEvent.META_SHIFT_ON) != 0;
        final boolean word = (meta & (KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON)) != 0;
        // 只有"要扩选"（Shift）或"要按词移光标"（Ctrl/Alt）才可能有我们的事；
        // 纯方向键一律放行，宿主自己会移光标
        if (!shift && !word) {
            reset();
            return PASS;
        }

        final boolean horizontal = isHorizontal(kc);
        final int dir = kc == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
        sVertical = !horizontal;
        sDeadline = now + WINDOW_MS;

        // 中途加/松 Shift（模式变了）⇒ 重新起一段，锚点取当前光标
        if (sAnchor >= 0 && sSelect != shift) reset();

        if (sAnchor < 0) {                            // 序列开始
            final int caret = currentCaret();
            if (caret < 0) return PASS;
            sAnchor = caret;
            sFocus = caret;
            sDir = dir;
            sWord = word;
            sSelect = shift;
            sActive = false;
        }

        if (sActive && horizontal) {                  // 已接管：自己推进焦点，锚点不动
            int next = step(service, sFocus, dir, sWord);
            if (next == UNKNOWN) next = sFocus + dir;
            if (next < 0) next = 0;
            if (next == sFocus) {                     // 到头了，吞掉即可
                sConsumedDown = true;
                return CONSUMED;
            }
            apply(service, sSelect ? sAnchor : next, next);
            sFocus = next;
            sConsumedDown = true;
            return CONSUMED;
        }

        // 未接管：放行之前先算好自己的期望焦点，等宿主回报时核对
        sExpected = horizontal ? step(service, sFocus, dir, sWord) : UNKNOWN;
        return PASS;
    }

    /** onUpdateSelection 钩子里调用。 */
    static void onSelection(Object service, int start, int end) {
        if (!sEnabled) return;
        final long now = SystemClock.uptimeMillis();

        if (sSelfUpdate && now - sSelfUpdateAt < SELF_MS) {
            sSelfUpdate = false;                       // 我们自己补的：收下，修正夹紧后的焦点
            sSelStart = start;
            sSelEnd = end;
            if (sAnchor >= 0) sFocus = (end == sAnchor) ? start : end;
            return;
        }
        sSelfUpdate = false;
        sSelStart = start;
        sSelEnd = end;

        if (sAnchor < 0) return;
        if (now > sDeadline) {
            reset();
            return;
        }

        final boolean collapsed = (start == end);
        // 宿主回报的焦点：有区间时取"不是锚点的那一端"，塌的就是该点
        final int appFocus = collapsed ? start : (start == sAnchor ? end : start);

        if (sVertical) {                              // 垂直键：宿主挪到哪就补到哪（只处理扩选）
            if (!sSelect) return;
            if (collapsed && start != sAnchor) {
                apply(service, sAnchor, start);
                sFocus = start;
            } else if (!collapsed) {
                sFocus = appFocus;
            }
            return;
        }

        if (!sActive) {
            if (sExpected == UNKNOWN) {               // 算不出来就不敢干预，只记状态
                sFocus = appFocus;
                return;
            }
            // 好宿主：扩选要给"区间且焦点对上"；按词移光标要给"塌的且位置对上"。
            // 注意"要扩选却收到塌的"必须算失败 —— 只比对位置的话，逐字扩选时
            // 我们期望 ±1、坏宿主也正好只挪一格，两边数字相同 ⇒ 会被误判成好宿主。
            final boolean good = sSelect
                    ? (!collapsed && appFocus == sExpected)
                    : (collapsed && appFocus == sExpected);
            if (good) {
                sFocus = appFocus;
                return;
            }
            sActive = true;                           // 不一致 ⇒ 当场接管
            Log.i(TAG, "selshift: 接管 anchor=" + sAnchor + " 宿主="
                    + (collapsed ? "塌的" : "区间") + appFocus + " 期望=" + sExpected
                    + (sSelect ? "（扩选" : "（移光标") + (sWord ? "/按词）" : "/逐字）"));
            if (sSelect) {
                apply(service, sAnchor, sExpected);
            } else {
                apply(service, sExpected, sExpected);
            }
            sFocus = sExpected;
            return;
        }

        // 已接管：宿主若又报回不符合我们跟踪状态的选区，再补一次
        if (sSelect) {
            if (collapsed && start != sFocus) apply(service, sAnchor, sFocus);
        } else {
            if (!collapsed || start != sFocus) apply(service, sFocus, sFocus);
        }
    }

    /**
     * 从 {@code from} 往 {@code dir} 走一步。
     *
     * @return 目标位置；按词但取不到文本/算不出时返回 {@link #UNKNOWN}（调用方不要据此干预）
     */
    private static int step(Object service, int from, int dir, boolean word) {
        if (!word) return from + dir;
        final InputConnection ic = connectionOf(service);
        if (ic == null) return UNKNOWN;

        // 首选：getExtractedText —— 整段文本 + 绝对起点 + 选区绝对偏移，映射最干净
        try {
            final ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (et != null && et.text != null) {
                final int base = et.startOffset;
                final int idx = from - base;
                if (DEV_LOG) {
                    Log.i(TAG, "selshift: extracted base=" + base + " len=" + et.text.length()
                            + " sel=(" + et.selectionStart + "," + et.selectionEnd + ")"
                            + " tracked=(" + sSelStart + "," + sSelEnd + ")"
                            + " from=" + from + " idx=" + idx);
                }
                if (idx >= 0 && idx <= et.text.length()) {
                    final int b = boundary(et.text, idx, dir);
                    if (b != BreakIterator.DONE) {
                        final int target = base + b;
                        return target == from ? UNKNOWN : target;
                    }
                }
            } else if (DEV_LOG) {
                Log.i(TAG, "selshift: extracted null，退回拼窗口");
            }
        } catch (Throwable err) {
            if (DEV_LOG) Log.w(TAG, "selshift: getExtractedText 失败: " + err);
        }

        // 退回：before(相对选区起点 lo) + 选区正文 + after(相对选区终点 hi) 拼绝对连续窗口
        try {
            final int lo = Math.min(sSelStart, sSelEnd);
            final int hi = Math.max(sSelStart, sSelEnd);
            if (lo < 0 || from < lo || from > hi) return UNKNOWN;
            final CharSequence beforeCs = ic.getTextBeforeCursor(WINDOW, 0);
            final CharSequence selCs = ic.getSelectedText(0);
            final CharSequence afterCs = ic.getTextAfterCursor(WINDOW, 0);
            final String before = beforeCs == null ? "" : beforeCs.toString();
            final String sel = selCs == null ? "" : selCs.toString();
            final String after = afterCs == null ? "" : afterCs.toString();
            if (DEV_LOG) {
                Log.i(TAG, "selshift: window lo=" + lo + " hi=" + hi + " from=" + from
                        + " blen=" + before.length() + " sellen=" + sel.length()
                        + " alen=" + after.length());
            }
            if (sel.length() != hi - lo) return UNKNOWN;     // 对不上就别冒险
            final CharSequence window = before + sel + after;
            final int at = before.length() + (from - lo);
            if (at < 0 || at > window.length()) return UNKNOWN;
            final int b = boundary(window, at, dir);
            if (b == BreakIterator.DONE) return UNKNOWN;
            final int target = lo - before.length() + b;     // 窗口下标 → 绝对偏移
            return target == from ? UNKNOWN : target;
        } catch (Throwable err) {
            if (DEV_LOG) Log.w(TAG, "selshift: 拼窗口分词失败: " + err);
            return UNKNOWN;
        }
    }

    /** ICU 词边界（越过空白段），返回 {@code at} 之前/之后的词界。 */
    private static int boundary(CharSequence s, int at, int dir) {
        final BreakIterator bi = BreakIterator.getWordInstance(Locale.getDefault());
        bi.setText(s);
        return dir < 0 ? wordLeft(bi, s, at) : wordRight(bi, s, at);
    }

    /**
     * 向左找一个"词"的起点。ICU 把空白也切成分段，直接取 preceding 会停在空白上
     * （表现：`bar 北京大学` 从「北」往左只跳到空格），所以越过空白段继续找非空白段。
     */
    private static int wordLeft(BreakIterator bi, CharSequence s, int at) {
        int b = at;
        while (b > 0) {
            final int p = bi.preceding(b);
            if (p == BreakIterator.DONE) return 0;
            if (isBlank(s, p, b)) { b = p; continue; }
            return p;
        }
        return b;
    }

    /** 向右找一个"词"的终点，同样越过空白段。 */
    private static int wordRight(BreakIterator bi, CharSequence s, int at) {
        final int len = s.length();
        int b = at;
        while (b < len) {
            final int n = bi.following(b);
            if (n == BreakIterator.DONE) return len;
            if (isBlank(s, b, n)) { b = n; continue; }
            return n;
        }
        return b;
    }

    private static boolean isBlank(CharSequence s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }

    private static InputConnection connectionOf(Object service) {
        return (service instanceof InputMethodService)
                ? ((InputMethodService) service).getCurrentInputConnection() : null;
    }

    private static void apply(Object service, int a, int b) {
        final InputConnection ic = connectionOf(service);
        if (ic == null) return;
        final int lo = Math.max(0, Math.min(a, b));
        final int hi = Math.max(0, Math.max(a, b));
        sSelfUpdate = true;
        sSelfUpdateAt = SystemClock.uptimeMillis();
        // 回主线程再写：在 onUpdateSelection 里直接改选区会踩框架自己的回调节奏
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ic.setSelection(lo, hi);
                Log.i(TAG, "selshift: 补选区 (" + lo + "," + hi + ")");
            } catch (Throwable err) {
                sSelfUpdate = false;
                Log.w(TAG, "selshift: setSelection failed: " + err);
            }
        });
    }
}
