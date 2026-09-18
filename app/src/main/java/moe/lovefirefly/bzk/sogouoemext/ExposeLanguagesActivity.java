package moe.lovefirefly.bzk.sogouoemext;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 子页面：**选择在输入法框架中显示的语言**（三个勾选）。
 *
 * <p>从首页那一行卡片进来。搬成独立页面的原因：首页要留给各功能开关，
 * 而"暴露哪些语言"是低频、一次定好的事，混在首页会一直占着最显眼的位置。
 *
 * <p>版式与首页/说明页同一套 Material 3 风格：顶栏（← 返回 + 标题）、
 * 每项一张卡片、页边距 {@code pad*2}，{@link MainActivity#applyInsets} 处理系统栏。
 *
 * <p><b>存储格式没变</b>：仍是 {@code order} + {@code divider}（暴露的排前面，
 * divider = 暴露个数）—— 模块侧 {@link LangConfig} 读的就是它，所以改这个页面
 * 不需要重启输入法进程，改完立即通过 {@link ConfigPoke} 广播 poke。
 */
public class ExposeLanguagesActivity extends AppCompatActivity {

    private static final String TAG = "BZK-SogouOEMExt";

    private SharedPreferences prefs;
    private int pad;
    private LinearLayout listBox;

    /** 当前勾选的语言（= 暴露为 subtype 的那批）；顺序不在这里，顺序归 BZK。 */
    private final Set<String> exposure = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pad = (int) (16 * getResources().getDisplayMetrics().density);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶栏：返回 + 标题（ActionBar 已去掉，自己画一个，顺带解决返回入口）
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad * 3 / 4, pad * 3 / 4, pad, pad / 4);

        final MaterialButton back = new MaterialButton(this);
        back.setText("← 返回");
        back.setOnClickListener(v -> finish());

        final TextView title = new TextView(this);
        title.setText("选择在输入法框架中显示的语言");
        title.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleLarge);
        title.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));
        title.setPadding(pad, 0, 0, 0);

        bar.addView(back);
        bar.addView(title);

        // 三个勾选：每项一张卡（与首页设置项同规格）
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(pad * 2, pad / 2, pad * 2, pad * 2);

        final ScrollView sv = new ScrollView(this);
        sv.addView(listBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bar);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        MainActivity.applyInsets(root);

        // 配置存在本机（模块通过 ContentProvider 读取），与首页共用同一份 prefs
        prefs = getSharedPreferences(LangConfig.PREFS_NAME, MODE_PRIVATE);
        rebuild(LangConfig.load(prefs).exposed());
    }

    private void rebuild(List<String> exposedIds) {
        exposure.clear();
        if (exposedIds != null) {
            for (String id : exposedIds) {
                if (LangSpec.ALL.contains(id)) exposure.add(id);
            }
        }
        if (listBox == null) return;
        listBox.removeAllViews();
        for (String id : LangSpec.ALL) listBox.addView(langRow(id, exposure.contains(id)));
    }

    /** 一行：卡片 + MaterialCheckBox；点整行也能切，改动立即落盘。 */
    private View langRow(String id, boolean checked) {
        final com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(this);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, pad / 2, 0, 0);
        card.setLayoutParams(lp);
        card.setRadius(pad * 3 / 4f);
        card.setCardElevation(0f);
        card.setStrokeWidth(Math.max(1, pad / 16));
        card.setStrokeColor(themeColor(com.google.android.material.R.attr.colorOutlineVariant));

        final com.google.android.material.checkbox.MaterialCheckBox cb =
                new com.google.android.material.checkbox.MaterialCheckBox(this);
        cb.setText(LangSpec.label(id));
        cb.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface));
        cb.setPadding(pad * 3 / 4, pad / 2, pad * 3 / 4, pad / 2);
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener((b, isChecked) -> {
            if (isChecked) exposure.add(id);
            else exposure.remove(id);
            save();
        });

        card.addView(cb);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> cb.toggle());   // 点整行也能切
        return card;
    }

    /**
     * 落盘（存储格式与首页原来那份完全一致）。
     *
     * <p>暴露的按规范顺序排前面，未暴露的接后面，{@code divider} = 暴露个数。
     */
    private void save() {
        if (prefs == null) {
            android.util.Log.w(TAG, "UI: exposure save ignored, prefs unavailable");
            return;
        }
        final List<String> order = new ArrayList<>();
        for (String id : LangSpec.ALL) if (exposure.contains(id)) order.add(id);
        for (String id : LangSpec.ALL) if (!exposure.contains(id)) order.add(id);
        prefs.edit()
                .putString("order", String.join(",", order))
                .putInt("divider", exposure.size())
                .apply();
        sendConfigPoke();
    }

    /** 配置改完立刻戳一下模块（广播不带数据，模块收到就重读）。 */
    private void sendConfigPoke() {
        try {
            final android.content.Intent i = new android.content.Intent(ConfigPoke.ACTION);
            i.setPackage("com.sohu.inputmethod.sogou.oem");
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    private int themeColor(int attrRes) {
        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        if (tv.resourceId != 0) return ContextCompat.getColor(this, tv.resourceId);
        return tv.data;
    }
}
