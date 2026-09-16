package moe.lovefirefly.bzk.sougouext;

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

    private static final String TAG = "SogouOemBridge";
    private static final String SELF_PKG = "moe.lovefirefly.bzk.sougouext";
    private static final String SOGOU_PKG = "com.sohu.inputmethod.sogou.oem";

    public static final String AUTHORITY = "moe.lovefirefly.bzk.sougouext.config";
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
        android.util.Log.i("SogouOemBridge", "provider: dump self-check = "
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

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;                       // 只读
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
