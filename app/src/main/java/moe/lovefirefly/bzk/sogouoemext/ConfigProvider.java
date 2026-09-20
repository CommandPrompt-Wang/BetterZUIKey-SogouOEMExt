package moe.lovefirefly.bzk.sogouoemext;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 把模块 App 的配置暴露给**搜狗进程里的模块**读取。
 *
 * <p>为什么不用 libxposed 的 remote preferences：那套要靠 `XposedService`（管理器服务）绑定，
 * 实测在部分 LSPosed 发行版/魔改 ROM 上根本绑不上（本机 `pm list packages | grep lsposed` 为空），
 * 于是 App 侧的改动写不进去。ContentProvider 不依赖任何框架服务，永远可用。
 *
 * <p>只读：写入由 App 自己写本地 SharedPreferences。
 */
public class ConfigProvider extends ContentProvider {

    private static final String TAG = "BZK-SogouOEMExt";
    private static final String SELF_PKG = "moe.lovefirefly.bzk.sogouoemext";
    private static final String SOGOU_PKG = "com.sohu.inputmethod.sogou.oem";

    /** 状态位镜像（模块在搜狗进程里通过 insert 写；App 只读）—— App 读不到搜狗进程的私有 prefs。 */
    public static final String STATE_PREFS = "sogouext_state_mirror";

    public static final String AUTHORITY = "moe.lovefirefly.bzk.sogouoemext.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");
    public static final String COLUMN = "config";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
            @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        if (!isCallerAllowed()) {
            android.util.Log.w(TAG, "provider: reject uid=" + Binder.getCallingUid());
            return new MatrixCursor(new String[]{COLUMN});
        }
        final SharedPreferences sp = getContext()
                .getSharedPreferences(LangConfig.PREFS_NAME, Context.MODE_PRIVATE);
        final MatrixCursor c = new MatrixCursor(new String[]{COLUMN});
        c.addRow(new Object[]{LangConfig.dump(sp)});
        android.util.Log.i("BZK-SogouOEMExt", "provider: dump self-check = "
                + LangConfig.dumpRoundTripCheck(sp));
        return c;
    }

    private boolean isCallerAllowed() {
        final int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        final Context ctx = getContext();
        if (ctx == null) return false;
        final String[] pkgs = ctx.getPackageManager().getPackagesForUid(uid);
        if (pkgs == null) return false;
        for (String p : pkgs) {
            if (SELF_PKG.equals(p) || SOGOU_PKG.equals(p)) return true;
        }
        return false;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    /**
     * 状态位镜像：模块（搜狗进程）在热键切换状态位时调这里，把 fullwidth / enPunct /
     * physComplete、hardKbd 四个布尔值写给 App —— App 侧读不到搜狗进程的私有 prefs。
     */
    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        if (!isCallerAllowed()) {
            android.util.Log.w(TAG, "provider: reject insert uid=" + Binder.getCallingUid());
            return null;
        }
        if (values == null) return null;
        final SharedPreferences sp = getContext()
                .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        final SharedPreferences.Editor e = sp.edit();
        int n = 0;
        for (String k : new String[]{"fullwidth", "enPunct", "physComplete", "hardKbd"}) {
            if (values.containsKey(k)) {
                e.putBoolean(k, Boolean.TRUE.equals(values.getAsBoolean(k)));
                n++;
            }
        }
        // 硬键盘状态是"会自己变"的（物理键、CSP 都会改），不像全角/标点只由 App 的长按决定。
        // App 侧刷新逻辑是"写过 want 就优先显示 want"，所以镜像真状态时必须把过期的 want 清掉，
        // 否则状态行会永远停在用户上次长按的值上（真机踩过）。
        if (values.containsKey("hardKbdAtMs")) {
            final Long at = values.getAsLong("hardKbdAtMs");
            e.putLong("hardKbdAtMs", at == null ? 0L : at);
            n++;
        }
        if (values.containsKey("hardKbdHotkey")) {
            final String hk = values.getAsString("hardKbdHotkey");
            e.putString("hardKbdHotkey", hk == null ? "" : hk);
            n++;
        }
        if (values.containsKey("hardKbd")) {
            getContext().getSharedPreferences(LangConfig.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove("wantHardKbd").apply();
        }
        if (n > 0) e.apply();       // ⚠ 必须最后统一提交：早提交会把后面 put 的内容丢掉（踩过）
        android.util.Log.i(TAG, "provider: state mirrored (" + n + ") " + values);
        return uri;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] args) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
            @Nullable String[] selectionArgs) {
        return 0;                          // 只读
    }
}
