package moe.lovefirefly.bzk.sogouoemext;

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

/** 说明页（首页的"原理 / 说明"按钮打开）：与首页同一套 Material 3 风格。 */
public class InfoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int pad = (int) (16 * getResources().getDisplayMetrics().density);

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
        title.setText("原理 / 说明");
        title.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleLarge);
        title.setPadding(pad, 0, 0, 0);

        bar.addView(back);
        bar.addView(title);

        final TextView tv = new TextView(this);
        tv.setText(InfoText.text());
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyMedium);
        tv.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(pad * 3 / 2, pad / 2, pad * 3 / 2, pad * 2);

        final ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        root.addView(bar);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        MainActivity.applyInsets(root);
    }

    private int themeColor(int attrRes) {
        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        if (tv.resourceId != 0) return ContextCompat.getColor(this, tv.resourceId);
        return tv.data;
    }
}
