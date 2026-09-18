package moe.lovefirefly.bzk.sogouoemext;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 子页面：**选择在输入法框架中显示的语言**。
 *
 * <p>版式与其它子页面（说明页 / 顺序页）同构：{@link com.google.android.material.appbar.MaterialToolbar}
 * + **系统 up 箭头**（{@code ?attr/homeAsUpIndicator}，不自己画返回键）+ 页边距 {@code pad*2}，
 * 底部 {@link MainActivity#applyInsets} 处理系统栏。
 *
 * <p><b>存储格式没变</b>：仍是 {@code order} + {@code divider}（暴露的排前面、divider = 暴露个数）
 * —— 模块侧 {@link LangConfig} 读的就是它，所以改完立即经 {@link ConfigPoke} 广播生效，
 * 不需要重启输入法进程。
 */
public class ExposeLanguagesActivity extends AppCompatActivity {

    private static final String TAG = "BZK-SogouOEMExt";

    private SharedPreferences prefs;
    private LinearLayout listBox;
    private int pad;

    /** 当前勾选的语言（= 暴露为 subtype 的那批）；顺序不在这里，顺序归 BZK。 */
    private final Set<String> exposure = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pad = (int) (16 * getResources().getDisplayMetrics().density);
        prefs = getSharedPreferences(LangConfig.PREFS_NAME, MODE_PRIVATE);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶栏：与其它子页面同构（MaterialToolbar + 系统 up 箭头，子页面得能回去）
        final com.google.android.material.appbar.MaterialToolbar toolbar =
                new com.google.android.material.appbar.MaterialToolbar(this);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (56 * getResources().getDisplayMetrics().density)));
        toolbar.setElevation(4 * getResources().getDisplayMetrics().density);
        toolbar.setTitle("选择在输入法框架中显示的语言");
        toolbar.setTitleCentered(false);
        toolbar.setNavigationIcon(themeUpIndicator());
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        final TextView hint = new TextView(this);
        hint.setText("将 subtype 暴露给系统");
        hint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        hint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(pad * 2, pad, pad * 2, pad);
        root.addView(hint);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(pad * 2, 0, pad * 2, pad * 2);

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(listBox);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        MainActivity.applyInsets(root);

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

    /** 一行：一张卡 + MaterialCheckBox（与首页设置项同规格）；点整行也能切，改动立即落盘。 */
    private View langRow(String id, boolean checked) {
        final com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(this);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, pad / 2);
        card.setLayoutParams(lp);
        card.setRadius(pad * 3 / 4f);
        card.setCardElevation(0f);
        card.setStrokeWidth(Math.max(1, pad / 16));
        card.setStrokeColor(themeColor(com.google.android.material.R.attr.colorOutlineVariant));

        final com.google.android.material.checkbox.MaterialCheckBox cb =
                new com.google.android.material.checkbox.MaterialCheckBox(this);
        cb.setText(LangSpec.label(id));
        cb.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
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
     * 落盘（存储格式与原来首页那份完全一致）。
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

    /** XML 里 {@code app:navigationIcon="?attr/homeAsUpIndicator"} 的代码版（与其它页面同一套外观）。 */
    private android.graphics.drawable.Drawable themeUpIndicator() {
        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(androidx.appcompat.R.attr.homeAsUpIndicator, tv, true);
        if (tv.resourceId != 0) {
            return ContextCompat.getDrawable(this, tv.resourceId);
        }
        // 兜底：appcompat 自带的返回箭头（主题没定义该属性时才用）
        return ContextCompat.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material);
    }

    private int themeColor(int attrRes) {
        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        if (tv.resourceId != 0) return ContextCompat.getColor(this, tv.resourceId);
        return tv.data;
    }
}
