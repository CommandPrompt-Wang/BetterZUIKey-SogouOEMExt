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

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

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

    private static final String TAG = "SogouOemBridge";

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

        smartSwitch = addSwitch(strictBox, "智能中文标点",
                "按映射表输出中文标点：. → 。   , → ，   / 与 \\ → 、   [ → 【   < → 《 …"
                + "（搜狗本来就做，这里做归一；关掉则按键盘标点）");
        fullSwitch = addSwitch(strictBox, "全角模式",
                "快捷键 Shift+Space。开启后标点/字母/空格变全角；关闭则半角（不影响中文标点）");
        enSwitch = addSwitch(strictBox, "中英文标点切换",
                "快捷键 Ctrl+. 。开启后按键盘显示的标点输出（ASCII），优先于\"智能中文标点\"");
        numSwitch = addSwitch(strictBox, "智能编号",
                "数字后面的 。/） 自动用半角 . / )（方便 1.  2) 这类编号）；"
                + "只作用于紧跟数字的那一下，后续字符照常");

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
        bindPunctSwitches(LangConfig.parse(null, LangSpec.DEFAULT_DIVIDER));
        rebuildFromModel(LangConfig.defaultOrder(), LangSpec.DEFAULT_DIVIDER);

        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService service) {
                prefs = service.getRemotePreferences(LangConfig.GROUP);
                android.util.Log.i(TAG, "UI: xposed service bound, remote prefs ready");
                runOnUiThread(() -> {
                    final LangConfig cfg = LangConfig.parse(
                            prefs.getString("order", LangSpec.DEFAULT_ORDER),
                            prefs.getInt("divider", LangSpec.DEFAULT_DIVIDER));
                    rebuildFromModel(cfg.order, cfg.divider);
                    bindStrictSwitch(cfg.strict);
                    bindPunctSwitches(cfg);
                });
            }

            @Override public void onServiceDied(XposedService service) {
                prefs = null;
                android.util.Log.w(TAG, "UI: xposed service died, remote prefs unavailable");
            }
        });
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
        strictSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            if (prefs == null) {
                // 服务还没绑上：必须明说，否则用户以为存了
                android.util.Log.w(TAG, "UI: strict toggle ignored, xposed service not bound");
                Toast.makeText(this, "Xposed 服务未连接，改动未保存（请重开本 App 再试）",
                        Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putBoolean("strict", isChecked).apply();
            android.util.Log.i(TAG, "UI: strict saved = " + isChecked);
            Toast.makeText(this, "已保存（下次弹出键盘生效）", Toast.LENGTH_SHORT).show();
        });
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

    /** 标点三个开关（与 BZK 无关，任何时候都能用）。 */
    private void bindPunctSwitches(LangConfig cfg) {
        bindBoolSwitch(smartSwitch, "smartPunct", cfg.smartPunct);
        bindBoolSwitch(fullSwitch, "fullwidth", cfg.fullwidth);
        bindBoolSwitch(enSwitch, "enPunct", cfg.enPunct);
        bindBoolSwitch(numSwitch, "smartNumbering", cfg.smartNumbering);
    }

    private void bindBoolSwitch(MaterialSwitch sw, String key, boolean checked) {
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((v, isChecked) -> {
            if (prefs == null) {
                android.util.Log.w(TAG, "UI: " + key + " toggle ignored, service not bound");
                Toast.makeText(this, "Xposed 服务未连接，改动未保存（请重开本 App 再试）",
                        Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putBoolean(key, isChecked).apply();
            android.util.Log.i(TAG, "UI: " + key + " saved = " + isChecked);
            Toast.makeText(this, "已保存（最多 2 秒生效）", Toast.LENGTH_SHORT).show();
        });
    }

    private boolean hasBetterZUIKey() {
        try {
            getPackageManager().getPackageInfo(BZK_PKG, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
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
