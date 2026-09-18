package moe.lovefirefly.bzk.sogouoemext;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;


/**
 * 模块首页（设置页）。
 *
 * <p>语言部分**只有勾选**：每个语言一个 checkbox = 是否把它做成 subtype **暴露给框架**。
 * 未勾选的语言不进 subtype 列表，BZK / 系统框架都切不到，只能从搜狗键盘手动切。
 *
 * <p><b>顺序不在这里排</b> —— 轮转顺序归 BZK（BZK → 输入法适配管理 → 长按这条输入法拖动排序）。
 * 这里只决定"哪些语言能被切到"。存储格式仍是 order + divider（暴露的排前面），
 * 所以新旧模块代码读到的语义一致 —— 改这个页面**不需要重启**。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BZK-SogouOEMExt";

    private static final String BZK_PKG = "moe.lovefirefly.betterzuikey";

    /** 勾选中的语言（= 暴露为 subtype 的那批）。顺序不在这里 —— 顺序归 BZK。 */
    private final java.util.Set<String> exposure = new java.util.LinkedHashSet<>();
    private LinearLayout langBox;
    private MaterialSwitch strictSwitch;
    private TextView strictHint;
    private MaterialSwitch smartSwitch;
    private MaterialSwitch fullSwitch;
    private MaterialSwitch enSwitch;
    private MaterialSwitch numSwitch;
    private MaterialSwitch longSwitch;
    private MaterialSwitch capSwitch;
    private MaterialSwitch autoPairSwitch;
    private MaterialSwitch physPairSwitch;
    private TextView editPairTableRow;
    private com.google.android.material.textfield.MaterialAutoCompleteTextView slashField;

    private SharedPreferences prefs;
    private int pad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pad = (int) (16 * getResources().getDisplayMetrics().density);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        final TextView title = new TextView(this);
        title.setText("暴露给框架的语言");
        title.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_HeadlineSmall);
        title.setPadding(pad * 2, pad * 2, pad * 2, pad / 2);


        // 语言部分：只有勾选（每个语言一行 MaterialCheckBox）
        langBox = new LinearLayout(this);
        langBox.setOrientation(LinearLayout.VERTICAL);
        langBox.setPadding(0, pad / 2, 0, pad / 2);

        final LinearLayout strictBox = new LinearLayout(this);
        strictBox.setOrientation(LinearLayout.VERTICAL);
        strictBox.setPadding(pad * 2, pad / 2, pad * 2, 0);

        strictSwitch = new MaterialSwitch(this);
        strictSwitch.setText("只响应系统框架语言切换消息");
        strictSwitch.setPadding(0, pad / 4, 0, pad / 4);

        strictHint = new TextView(this);
        strictHint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        strictHint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        strictHint.setPadding(0, 0, 0, pad / 2);

        strictBox.addView(strictSwitch);
        strictBox.addView(strictHint);
        // 斜杠下拉：关 / / / \ （语义层，随后仍受全半角影响）
        final TextView slashLabel = new TextView(this);
        slashLabel.setText("原样输出斜杠");
        slashLabel.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
        slashLabel.setPadding(0, 0, pad / 2, 0);
        slashLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));   // 左标签占满剩余宽度 → 下拉右对齐

        final com.google.android.material.textfield.TextInputLayout til =
                new com.google.android.material.textfield.TextInputLayout(this);
        til.setHintEnabled(false);
        til.setEndIconMode(
                com.google.android.material.textfield.TextInputLayout.END_ICON_DROPDOWN_MENU);
        til.setBoxBackgroundColor(themeColor(
                com.google.android.material.R.attr.colorSurfaceContainerHighest));
        til.setMinimumWidth((int) (160 * getResources().getDisplayMetrics().density));

        slashField = new com.google.android.material.textfield.MaterialAutoCompleteTextView(this);
        slashField.setInputType(android.text.InputType.TYPE_NULL);
        slashField.setFocusable(true);
        slashField.setClickable(true);
        slashField.setDropDownWidth(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        til.addView(slashField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView slashHint = new TextView(this);
        slashHint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        slashHint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        slashHint.setText("搜狗把 / 和 \\ 都输出成 、。在此选择想原样保留的字符。");

        final LinearLayout slashRow = new LinearLayout(this);
        slashRow.setOrientation(LinearLayout.HORIZONTAL);
        slashRow.setGravity(Gravity.CENTER_VERTICAL);
        slashRow.setPadding(0, pad / 2, 0, 0);
        slashRow.addView(slashLabel);
        slashRow.addView(til);
        strictBox.addView(slashRow);
        strictBox.addView(slashHint);

        // 三个功能开关（默认全开）。注意：它们只决定"这个功能是否启用"，
        // 具体当前是中文/英文标点、全角/半角属于"状态位"，由快捷键切换并持久化，不在界面显示。
        smartSwitch = addSwitch(strictBox, "智能中文标点",
                "使用更合理的中文标点映射（+ - # 等按半角处理）");
        fullSwitch = addSwitch(strictBox, "全角模式",
                "在全角/半角之间切换。快捷键：Shift+Space");
        enSwitch = addSwitch(strictBox, "中英文标点",
                "允许中文模式下在中英标点之间切换；英文输入状态下标点恒为英文。快捷键：Ctrl+.");

        final TextView hotkeyHint = new TextView(this);
        hotkeyHint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        hotkeyHint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hotkeyHint.setText("当前状态（快捷键切换，自动记住，不在界面显示）："
                + "Shift+Space 全角/半角，Ctrl+. 中文标点/英文标点");
        hotkeyHint.setPadding(0, 0, 0, pad / 2);
        strictBox.addView(hotkeyHint);

        capSwitch = addSwitch(strictBox, "大写字母进拼音栏",
                "中文输入时大写字母也进入拼音串，便于英文补全或迅速输入。\n"
                + "注意：拼音栏仍会显示为小写，上屏时会根据实际输入情况转换大小写。");

        numSwitch = addSwitch(strictBox, "智能编号",
                "数字后面的 。和） 自动用半角 . 和 )（方便 1.  2) 这类编号）；"
                + "只作用于紧跟数字的那一下，后续字符照常");

        longSwitch = addSwitch(strictBox, "完整的 …… 和 ——",
                "开启：破折号/省略号各出两个 —— 和 ……；关闭：恢复搜狗原生单出效果");

        // S：软键盘补全（搜狗原生行为，模块只做开关）
        autoPairSwitch = addSwitch(strictBox, "引号/括号自动补全",
                "软键盘：关闭后打引号、括号不再自动补另一半（只出单个字符）。");

        // 9：物理键盘补全（模块自己注入）—— 与 S 各自独立，共用同一份匹配列表
        physPairSwitch = addSwitch(strictBox, "物理键盘自动补全",
                "物理键盘：打引号、括号时自动补上另一半并把光标移到中间。\n"
                + "快捷键 Ctrl+Shift+9 可临时开关。");

        // 匹配列表编辑入口：两个开关共用，独立成条目
        editPairTableRow = new TextView(this);
        editPairTableRow.setText(R.string.edit_pair_table);
        editPairTableRow.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
        editPairTableRow.setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary));
        editPairTableRow.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        editPairTableRow.setClickable(true);
        editPairTableRow.setFocusable(true);
        editPairTableRow.setOnClickListener(v -> showAutoPairDialog());
        strictBox.addView(editPairTableRow);

        final ExtendedFloatingActionButton fab = new ExtendedFloatingActionButton(this);
        fab.setText("原理 / 说明");
        fab.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, InfoActivity.class)));
        final LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.CENTER_HORIZONTAL;
        flp.setMargins(0, pad / 2, 0, pad);

        // 内容全部放进 ScrollView：屏幕矮的时候，下面的开关不会再挡住列表
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(title);
        content.addView(langBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(strictBox);

        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(fab, flp);          // FAB 固定在视口底部
        setContentView(root);
        applyInsets(root);

        bindStrictSwitch(false);
        bindPunctSwitches(LangConfig.defaults());
        rebuildFromModel(LangConfig.defaults().exposed());

        // 配置存在本机（模块通过 ContentProvider 读取），不需要任何框架服务
        prefs = getSharedPreferences(LangConfig.PREFS_NAME, MODE_PRIVATE);
        // 功能开关默认全开：strict 首次运行按"是否装了 BZK"给默认值
        if (!prefs.contains("strict")) {
            final boolean bzk = hasBetterZUIKey();
            prefs.edit().putBoolean("strict", bzk).apply();
            sendConfigPoke();
            android.util.Log.i(TAG, "UI: seed strict=" + bzk + " (first run)");
        }
        final LangConfig cfg0 = LangConfig.load(prefs);
        rebuildFromModel(cfg0.exposed());
        bindStrictSwitch(cfg0.strict);
        bindPunctSwitches(cfg0);

    }

    /**
     * 开关 + 建议文案。
     *
     * <p>没装 BZK 时灰掉（并建议安装）；装了则保持可点，并把"建议怎么配"写清楚。
     */
    private void bindStrictSwitch(boolean checked) {
        final boolean bzk = hasBetterZUIKey();
        strictSwitch.setEnabled(bzk);
        strictSwitch.setAlpha(bzk ? 1f : 0.45f);
        strictHint.setText(bzk
                ? "检测到BetterZUIKey，建议在它的\u201c输入法增强\u201d中为\u201c搜狗OEM\u201d"
                  + "启用\u201cframework\u201d模式，然后打开此开关，"
                  + "以让BetterZUIKey完全接管此选项"
                : "未检测到BetterZUIKey，建议安装以增强功能");
        strictSwitch.setOnCheckedChangeListener(null);
        strictSwitch.setChecked(checked && bzk);
        strictSwitch.setOnCheckedChangeListener((v, isChecked) -> putBool("strict", isChecked));
    }

    /** 新建一个带说明的开关，挂在给定容器里。 */
    private MaterialSwitch addSwitch(LinearLayout parent, String title, String hintText) {
        final MaterialSwitch sw = new MaterialSwitch(this);
        sw.setText(title);
        sw.setPadding(0, pad / 2, 0, pad / 4);

        final TextView tv = new TextView(this);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        tv.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setText(hintText);
        tv.setPadding(0, 0, 0, pad / 4);

        parent.addView(sw);
        parent.addView(tv);
        return sw;
    }

    /**
     * 「编辑匹配列表」→ 编辑自定义配对串。
     *
     * <p>串是若干「前-后」配对依次排列（相邻两字符为一组），所以长度必须为偶数。
     * 留空表示使用输入法默认匹配规则（此时模块不介入，完全走搜狗自己的表）。
     */
    private void showAutoPairDialog() {
        final int dp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                getResources().getDisplayMetrics());

        // 说明
        final TextView desc = new TextView(this);
        desc.setText(R.string.autopair_desc);
        desc.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        desc.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));

        // 输入框：hint 与默认值都用建议串（建议串本身按分组带换行，正好多行显示）。
        // 多行框按回车能插换行，而且换行本身就是分组手段 → **原样存储**，
        // 只在"校验"和"解析"时过 LangConfig.cleanTable 去掉换行再数配对。
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(LangConfig.SUGGEST_PAIR_TABLE);
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setHorizontallyScrolling(false);
        // 原样回填：用户自己排的分组要留着
        input.setText(prefs.getString("autoPairTable", LangConfig.SUGGEST_PAIR_TABLE));
        input.setSelection(input.getText().length());

        // 「填入建议项」：左对齐，放在输入框下方（对话框底部按钮区由 取消/保存 占用）
        final TextView suggest = new TextView(this);
        suggest.setText(R.string.autopair_suggest);
        suggest.setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary));
        suggest.setPadding(0, dp * 10, 0, 0);
        suggest.setClickable(true);
        suggest.setFocusable(true);
        suggest.setOnClickListener(v -> {
            input.setText(LangConfig.SUGGEST_PAIR_TABLE);
            input.setSelection(input.getText().length());
        });

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp * 24, dp * 4, dp * 24, 0);
        box.addView(desc);
        box.addView(input);
        box.addView(suggest);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.autopair_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)   // 占位，校验逻辑在 onShow 里接
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    // 校验用清洗后的串（去掉分组换行），存储存原样
                    final String raw = input.getText().toString().trim();
                    if (LangConfig.cleanTable(raw).length() % 2 != 0) {
                        Toast.makeText(this, R.string.autopair_odd_error,
                                Toast.LENGTH_SHORT).show();
                        return;                                  // 奇数不保存
                    }
                    prefs.edit().putString("autoPairTable", raw).apply();
        sendConfigPoke();
                    Toast.makeText(this, "已保存（立即生效）", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }));

        dialog.show();
    }

    private void bindPunctSwitches(LangConfig cfg) {
        bindBoolSwitch(smartSwitch, "smartPunct", cfg.smartPunct);
        bindBoolSwitch(fullSwitch, "fullwidth", cfg.fullwidth);
        bindBoolSwitch(enSwitch, "enPunct", cfg.enPunct);
        bindBoolSwitch(numSwitch, "smartNumbering", cfg.smartNumbering);
        bindBoolSwitch(longSwitch, "longMarks", cfg.longMarks);
        bindBoolSwitch(capSwitch, "capitalInPinyin", cfg.capitalInPinyin);
        bindBoolSwitch(autoPairSwitch, "autoPair", cfg.autoPair);
        bindBoolSwitch(physPairSwitch, "physComplete", cfg.physComplete);
        bindSlashSpinner(cfg.slashMode);
    }

    /**
     * 斜杠下拉：0=关 1=/ 2=\ 。
     *
     * <p>绑定期间忽略回调（Spinner 会在设置选中项后自己回调一次，否则一进界面就弹"已保存"）。
     */
    /** 斜杠下拉（BZK 同款：TextInputLayout + MaterialAutoCompleteTextView + dropdown_item_wrap）。 */
    private void bindSlashSpinner(int mode) {
        final String[] items = {"关", "/", "\\"};
        slashField.setAdapter(new android.widget.ArrayAdapter<>(
                this, R.layout.dropdown_item_wrap, items));
        slashField.setText(items[Math.max(0, Math.min(mode, 2))], false);
        slashField.setOnItemClickListener((parent, view, position, id) -> {
            if (position == prefs.getInt("slashMode", 0)) return;
            putInt("slashMode", position);
        });
    }

    private void bindBoolSwitch(MaterialSwitch sw, String key, boolean checked) {
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((v, isChecked) -> putBool(key, isChecked));
    }

    private boolean hasBetterZUIKey() {
        try {
            getPackageManager().getPackageInfo(BZK_PKG, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 统一的保存入口（写本机，模块最多 2 秒后来读）。 */
    private void putBool(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
        sendConfigPoke();
        android.util.Log.i(TAG, "UI: " + key + " saved = " + value);
        Toast.makeText(this, "已保存（立即生效）", Toast.LENGTH_SHORT).show();
    }

    private void putInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
        sendConfigPoke();
        android.util.Log.i(TAG, "UI: " + key + " saved = " + value);
        Toast.makeText(this, "已保存（立即生效）", Toast.LENGTH_SHORT).show();
    }

    /** 配置改完立刻戳一下模块：广播不带数据，模块收到就重读（不再等轮询）。 */
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

    /** Android 15+ 强制 edge-to-edge：把系统栏高度补成内边距。 */
    static void applyInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                final android.graphics.Insets bars =
                        insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
    }

    // ------------------------------------------------------------------
    // 模型：只有"暴露 / 不暴露"—— 顺序归 BZK
    // ------------------------------------------------------------------

    /** 按配置重建勾选列表（行顺序固定用 {@link LangSpec#ALL}，与轮转顺序无关）。 */
    private void rebuildFromModel(List<String> exposedIds) {
        exposure.clear();
        if (exposedIds != null) {
            for (String id : exposedIds) {
                if (LangSpec.ALL.contains(id)) exposure.add(id);
            }
        }
        if (langBox != null) {
            langBox.removeAllViews();
            for (String id : LangSpec.ALL) langBox.addView(langRow(id, exposure.contains(id)));
        }
    }

    /** 一行：MaterialCardView + MaterialCheckBox；点整行也能切，改动立即落盘。 */
    private View langRow(String id, boolean checked) {
        final com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(this);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad * 3 / 4, pad * 3 / 8, pad * 3 / 4, pad * 3 / 8);
        card.setLayoutParams(lp);
        card.setRadius(pad * 3 / 4);
        card.setCardElevation(pad / 16f);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(pad * 3 / 4, pad / 2, pad * 3 / 4, pad / 2);

        final com.google.android.material.checkbox.MaterialCheckBox cb =
                new com.google.android.material.checkbox.MaterialCheckBox(this);
        cb.setText(LangSpec.label(id));

        cb.setChecked(checked);
        cb.setOnCheckedChangeListener((b, isChecked) -> {
            if (isChecked) exposure.add(id); else exposure.remove(id);
            save();
        });

        row.addView(cb);
        card.addView(row);
        card.setOnClickListener(v -> cb.toggle());
        return card;
    }

    /**
     * 落盘。
     *
     * <p><b>存储格式没变</b>：还是 `order` + `divider` —— 暴露的排前面（规范顺序），
     * 未暴露的接在后面，`divider` = 暴露个数。这样模块侧（读 order/divider 的
     * {@code LangConfig}）与外界看到的语义完全一致，改这个页面**不需要重启**。
     */
    private void save() {
        if (prefs == null) {
            android.util.Log.w(TAG, "UI: exposure save ignored, prefs unavailable");
            Toast.makeText(this, "配置未就绪，改动未保存（请重开本 App 再试）",
                    Toast.LENGTH_LONG).show();
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

}
