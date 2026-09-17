package moe.lovefirefly.bzk.sougouext;

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
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;


/**
 * 首页：语言顺序（可拖拽的顺序 + 可拖拽的分隔线）。
 *
 * <p><b>分隔线上方</b>的语言会被模块做成 subtype 暴露给框架（参与 next 轮转，顺序即此顺序）；
 * <b>下方</b>的不做成 subtype，只能从搜狗键盘手动切；从下方切出时回到上方第一项。
 *
 * <p>视觉与交互跟 BZK 的应用模板列表一致：Material 3 主题、MaterialCardView 行、
 * RecyclerView + ItemTouchHelper 长按拖动、拖动时 elevation/scale 反馈、松手才落盘。
 */
public class LangOrderActivity extends AppCompatActivity {

    private static final String TAG = "BZK-SogouOEMExt";

    private static final int TYPE_LANG = 0;
    private static final int TYPE_DIVIDER = 1;

    private static final class Entry {
        final int type;
        final String lang;

        Entry(int type, String lang) {
            this.type = type;
            this.lang = lang;
        }
    }

    private static final String BZK_PKG = "moe.lovefirefly.betterzuikey";

    private final List<Entry> items = new ArrayList<>();
    private Adapter adapter;
    private TextView hint;
    private MaterialSwitch strictSwitch;
    private TextView strictHint;
    private MaterialSwitch smartSwitch;
    private MaterialSwitch fullSwitch;
    private MaterialSwitch enSwitch;
    private MaterialSwitch numSwitch;
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
        title.setText("切换语言顺序");
        title.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_HeadlineSmall);
        title.setPadding(pad * 2, pad * 2, pad * 2, pad / 2);

        hint = new TextView(this);
        hint.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        hint.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setPadding(pad * 2, 0, pad * 2, pad / 2);

        final RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setClipToPadding(false);
        rv.setPadding(0, pad / 2, 0, pad / 2);
        // 整页可滚动：列表按内容高度撑开，自身不滚动（3 项，拖动仍可用）
        rv.setNestedScrollingEnabled(false);
        adapter = new Adapter();
        rv.setAdapter(adapter);
        attachDrag(rv);

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
        content.addView(hint);
        content.addView(rv, new LinearLayout.LayoutParams(
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
        rebuildFromModel(LangConfig.defaultOrder(), LangSpec.DEFAULT_DIVIDER);

        // 配置存在本机（模块通过 ContentProvider 读取），不需要任何框架服务
        prefs = getSharedPreferences(LangConfig.PREFS_NAME, MODE_PRIVATE);
        // 功能开关默认全开：strict 首次运行按"是否装了 BZK"给默认值
        if (!prefs.contains("strict")) {
            final boolean bzk = hasBetterZUIKey();
            prefs.edit().putBoolean("strict", bzk).apply();
            android.util.Log.i(TAG, "UI: seed strict=" + bzk + " (first run)");
        }
        final LangConfig cfg0 = LangConfig.load(prefs);
        rebuildFromModel(cfg0.order, cfg0.divider);
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
                    Toast.makeText(this, "已保存（最多 2 秒生效）", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }));

        dialog.show();
    }

    private void bindPunctSwitches(LangConfig cfg) {
        bindBoolSwitch(smartSwitch, "smartPunct", cfg.smartPunct);
        bindBoolSwitch(fullSwitch, "fullwidth", cfg.fullwidth);
        bindBoolSwitch(enSwitch, "enPunct", cfg.enPunct);
        bindBoolSwitch(numSwitch, "smartNumbering", cfg.smartNumbering);
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
        android.util.Log.i(TAG, "UI: " + key + " saved = " + value);
        Toast.makeText(this, "已保存（最多 2 秒生效）", Toast.LENGTH_SHORT).show();
    }

    private void putInt(String key, int value) {
        prefs.edit().putInt(key, value).apply();
        android.util.Log.i(TAG, "UI: " + key + " saved = " + value);
        Toast.makeText(this, "已保存（最多 2 秒生效）", Toast.LENGTH_SHORT).show();
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
    // 模型 ↔ 列表
    // ------------------------------------------------------------------

    private void rebuildFromModel(List<String> order, int divider) {
        items.clear();
        for (int i = 0; i < order.size(); i++) {
            if (i == divider) items.add(new Entry(TYPE_DIVIDER, null));
            items.add(new Entry(TYPE_LANG, order.get(i)));
        }
        if (divider >= order.size()) items.add(new Entry(TYPE_DIVIDER, null));
        if (adapter != null) adapter.notifyDataSetChanged();
        updateHint();
    }

    private void save() {
        if (prefs == null) {
            android.util.Log.w(TAG, "UI: order save ignored, xposed service not bound");
            Toast.makeText(this, "Xposed 服务未连接，改动未保存（请重开本 App 再试）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final List<String> order = new ArrayList<>();
        int divider = 0;
        boolean seenDivider = false;
        for (Entry e : items) {
            if (e.type == TYPE_DIVIDER) {
                seenDivider = true;
            } else {
                order.add(e.lang);
                if (!seenDivider) divider++;
            }
        }
        prefs.edit()
                .putString("order", String.join(",", order))
                .putInt("divider", divider)
                .apply();
        Toast.makeText(this, "已保存（下次弹出键盘生效）", Toast.LENGTH_SHORT).show();
    }

    private void updateHint() {
        final List<String> labels = new ArrayList<>();
        final List<String> aboveIds = new ArrayList<>();
        int divider = 0;
        boolean seen = false;
        for (Entry e : items) {
            if (e.type == TYPE_DIVIDER) {
                seen = true;
            } else {
                labels.add(LangSpec.label(e.lang));
                if (!seen) {
                    aboveIds.add(e.lang);
                    divider++;
                }
            }
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("长按拖动排序；分隔线自己也能拖。\n");
        sb.append("上方（暴露为 subtype、按此顺序轮转）：")
                .append(divider == 0 ? "（空）" : String.join(" → ", labels.subList(0, divider)))
                .append('\n');
        sb.append("下方（不暴露，只能手动切）：")
                .append(divider >= labels.size() ? "（空）"
                        : String.join("、", labels.subList(divider, labels.size())));
        if (aboveIds.contains(LangSpec.PINYIN) && aboveIds.contains(LangSpec.WUBI)) {
            sb.append("\n\n注意：拼音和五笔都是中文方案，而搜狗软键盘只有中/英"
                    + "（五笔仅物理键盘带工具栏时可用）。"
                    + "两个中文方案同时放在上方时，在软键盘上按快捷键会看不出变化。");
        }
        hint.setText(sb.toString());
    }

    private int dividerIndex() {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).type == TYPE_DIVIDER) return i;
        }
        return items.size();
    }

    // ------------------------------------------------------------------
    // 拖动（与 BZK 的模板列表同一套）
    // ------------------------------------------------------------------

    private void attachDrag(RecyclerView rv) {
        final ItemTouchHelper helper = new ItemTouchHelper(
                new ItemTouchHelper.SimpleCallback(
                        ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
                    @Override
                    public boolean onMove(@NonNull RecyclerView view,
                            @NonNull RecyclerView.ViewHolder src,
                            @NonNull RecyclerView.ViewHolder target) {
                        final int from = src.getAdapterPosition();
                        final int to = target.getAdapterPosition();
                        if (from == to || from < 0 || to < 0) return false;
                        items.add(to, items.remove(from));
                        adapter.notifyItemMoved(from, to);
                        return true;
                    }

                    @Override
                    public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
                    }

                    @Override
                    public boolean isLongPressDragEnabled() {
                        return true;
                    }

                    @Override
                    public void onChildDraw(@NonNull android.graphics.Canvas canvas,
                            @NonNull RecyclerView view, @NonNull RecyclerView.ViewHolder holder,
                            float dX, float dY, int actionState, boolean isCurrentlyActive) {
                        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                            holder.itemView.setElevation(12f);
                            holder.itemView.setScaleX(0.98f);
                            holder.itemView.setScaleY(0.98f);
                        }
                        super.onChildDraw(canvas, view, holder, dX, dY, actionState, isCurrentlyActive);
                    }

                    @Override
                    public void clearView(@NonNull RecyclerView view,
                            @NonNull RecyclerView.ViewHolder holder) {
                        holder.itemView.setElevation(0f);
                        holder.itemView.setScaleX(1f);
                        holder.itemView.setScaleY(1f);
                        super.clearView(view, holder);
                        updateHint();
                        save();
                    }
                });
        helper.attachToRecyclerView(rv);
    }

    // ------------------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_DIVIDER) return new Holder(dividerRow());
            return new Holder(langRow());
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            final Entry e = items.get(position);
            final TextView name = h.itemView.findViewById(android.R.id.text1);
            final TextView sub = h.itemView.findViewById(android.R.id.text2);
            if (name == null || e.lang == null) return;
            name.setText(LangSpec.label(e.lang));
            if (sub != null) {
                final boolean above = position < dividerIndex();
                sub.setText(above ? "暴露为 subtype · 参与轮转"
                        : "不暴露 · 只能手动切（从它切出时回到上方第一项）");
            }
        }

        @Override public int getItemCount() {
            return items.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        Holder(View v) { super(v); }
    }

    /** 一行：MaterialCardView + 拖动柄 + 标题 + 副标题（与 BZK 的 item_template_row 同构）。 */
    private View langRow() {
        final MaterialCardView card = new MaterialCardView(this);
        final RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad * 3 / 4, pad * 3 / 8, pad * 3 / 4, pad * 3 / 8);
        card.setLayoutParams(lp);
        card.setRadius(pad * 3 / 4);
        card.setCardElevation(pad / 16f);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad * 3 / 4, pad * 3 / 4, pad * 3 / 4, pad * 3 / 4);

        final TextView handle = new TextView(this);
        handle.setText("≡");
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        handle.setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary));
        handle.setPadding(0, 0, pad * 3 / 4, 0);

        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        final TextView name = new TextView(this);
        name.setId(android.R.id.text1);
        name.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleMedium);

        final TextView sub = new TextView(this);
        sub.setId(android.R.id.text2);
        sub.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        sub.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));

        texts.addView(name);
        texts.addView(sub);

        row.addView(handle);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
        return card;
    }

    /** 分隔线那一行：拖动柄 + 一条细线 + 说明（同样可拖，拖它就是在改"上/下"的分界）。 */
    private View dividerRow() {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad * 3 / 4, pad * 3 / 4, pad * 3 / 4, pad * 3 / 4);

        final TextView handle = new TextView(this);
        handle.setText("≡");
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        handle.setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary));
        handle.setPadding(0, 0, pad * 3 / 4, 0);

        final View line = new View(this);
        line.setBackgroundColor(themeColor(com.google.android.material.R.attr.colorOutlineVariant));
        row.addView(handle);
        row.addView(line, new LinearLayout.LayoutParams(0, Math.max(1, pad / 16), 1f));

        final TextView tag = new TextView(this);
        tag.setText("  以下不参与轮转");
        tag.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        tag.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        row.addView(tag);
        return row;
    }
}
