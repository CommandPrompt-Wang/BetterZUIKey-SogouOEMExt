package moe.lovefirefly.bzk.sogouoemext;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

/**
 * Shift+方向键「选区修复」（真机 2026-09-20 定位并修复）。
 *
 * <p><b>病灶</b>：一部分宿主不用浏览器原生行为做 Shift+方向键选中，而是
 * {@code preventDefault()} 之后自己调 {@code Selection.modify("extend", …, "character")}。
 * 真机数据：DOM 事件 {@code shiftKey=true}、裸 contenteditable 原生选中正常，但这条
 * {@code modify("extend")} 在 Android WebView 上**退化成移动光标** —— 每按一下只把光标挪一格
 * （{@code onUpdateSelection(n,n)}，start==end ⇒ 无选中）。DSH 的输入框（Lexical 富文本）
 * 就是这种宿主。
 *
 * <p><b>为什么不能只补一次</b>：实测补回区间 {@code (11,12)} 后，宿主下一按又从**我们补出来的
 * 右端**往左塌回 {@code 11} —— 它把 extend 当 move 用，于是区间永远长不起来。所以判定出
 * 「坏宿主」之后，必须**由我们接管光标推进**（吞掉按键，自己算焦点），锚点保持不变。
 *
 * <p><b>怎么判定坏宿主</b>：一次序列的第一下**放行**（不吞）让宿主自己表现：
 * <ul>
 *   <li>宿主报回带区间的选区（{@code start != end}）⇒ 好宿主（如地址栏原生 EditText），
 *       本次序列一个字都不碰；</li>
 *   <li>宿主报回塌的 caret ⇒ 坏宿主 ⇒ 补回第一下，并从第二下起接管。</li>
 * </ul>
 * 这样对好宿主零影响，只修本来就不工作的宿主。
 *
 * <p>水平键（←/→）自己算焦点；垂直键（↑/↓/Home/End/翻页）算不出屏幕行，继续放行、
 * 由 {@link #onSelection} 把「锚点 → 宿主挪到的位置」补成区间。
 */
final class ShiftArrowRepair {

    private static final String TAG = "BZK-SogouOEMExt";

    /** 不干预：原样放行按键。 */
    static final int PASS = 0;
    /** 已接管：按键不要给宿主。 */
    static final int CONSUMED = 1;

    /** 宿主报回的选区（来自 onUpdateSelection）。 */
    private static volatile int sSelStart = -1, sSelEnd = -1;
    /** 本次序列锚点；-1 = 无序列。 */
    private static volatile int sAnchor = -1;
    /** 我们跟踪的焦点位置。 */
    private static volatile int sFocus = -1;
    /** 本次序列方向：-1 = ←，+1 = →（仅水平键有意义）。 */
    private static volatile int sDir;
    /** 最近一次按键是否垂直类（垂直键由宿主自己挪，我们只补区间）。 */
    private static volatile boolean sVertical;
    /** 序列有效期。 */
    private static volatile long sDeadline;
    /** 第一下已放行、等宿主回报来判定好坏。 */
    private static volatile boolean sPendingVerdict;
    /** 已判定坏宿主，本次序列由我们接管。 */
    private static volatile boolean sActive;
    /** 已吞掉 key down ⇒ 配对的 key up 也要吞。 */
    private static volatile boolean sConsumedDown;
    /** 我们自己 setSelection 引发的回调。 */
    private static volatile boolean sSelfUpdate;
    private static volatile long sSelfUpdateAt;

    private static final long WINDOW_MS = 700L;
    private static final long SELF_MS = 250L;

    private ShiftArrowRepair() {}

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
        sActive = false;
        sPendingVerdict = false;
        sConsumedDown = false;
        sVertical = false;
    }

    /** 宿主报回的选区即为"当前光标"（有区间时取焦点端）。 */
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
        if (!(evArg instanceof KeyEvent)) return PASS;
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

        if ((ev.getMetaState() & KeyEvent.META_SHIFT_ON) == 0) {
            reset();                                  // 纯方向键：宿主自己移光标
            return PASS;
        }

        final boolean horizontal = isHorizontal(kc);
        final int dir = kc == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
        sVertical = !horizontal;
        sDeadline = now + WINDOW_MS;

        if (sAnchor < 0) {                            // 序列第一下：放行，看宿主表现
            final int caret = currentCaret();
            if (caret < 0) return PASS;
            sAnchor = caret;
            sFocus = caret;
            sDir = dir;
            sActive = false;
            sPendingVerdict = true;
            return PASS;
        }

        if (!sActive || !horizontal) return PASS;      // 未接管 / 垂直键：放行让宿主挪

        // 已接管：自己推进焦点，锚点不动
        int next = sFocus + dir;
        if (next < 0) next = 0;
        if (next == sFocus) {                          // 到头了，吞掉即可
            sConsumedDown = true;
            return CONSUMED;
        }
        apply(service, sAnchor, next);
        sFocus = next;
        sConsumedDown = true;
        return CONSUMED;
    }

    /** onUpdateSelection 钩子里调用。 */
    static void onSelection(Object service, int start, int end) {
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

        if (sPendingVerdict) {                         // 第一下之后：好坏宿主在此判定
            sPendingVerdict = false;
            if (start != end) {                        // 好宿主：本次序列全程不插手
                sActive = false;
                Log.i(TAG, "selshift: 好宿主 (" + start + "," + end + ") 本次序列不插手");
                return;
            }
            sActive = true;                            // 坏宿主：补回第一下，之后接管
            Log.i(TAG, "selshift: 坏宿主 anchor=" + sAnchor + " caret=" + start + " ⇒ 接管");
            if (start != sAnchor) {
                apply(service, sAnchor, start);
                sFocus = start;
            }
            return;
        }

        if (start != end) return;                      // 有区间了，不需要我们

        if (sActive && !sVertical) {
            // 已接管还被宿主改回塌的：再补一次我们跟踪的位置
            if (start != sFocus) apply(service, sAnchor, sFocus);
            return;
        }

        // 未接管 / 垂直键：宿主挪到哪就补到哪（区间随宿主推进而增长）
        if (start != sAnchor) {
            apply(service, sAnchor, start);
            sFocus = start;
        }
    }

    private static void apply(Object service, int a, int b) {
        final InputConnection ic = (service instanceof InputMethodService)
                ? ((InputMethodService) service).getCurrentInputConnection() : null;
        if (ic == null) return;
        final int lo = Math.min(a, b);
        final int hi = Math.max(a, b);
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
