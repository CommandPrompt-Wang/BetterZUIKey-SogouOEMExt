package moe.lovefirefly.bzk.sogouoemext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

/**
 * 配置即时生效通道：App 发一条显式广播，模块收到就立刻重读配置。
 *
 * <p>广播**不带任何数据**，只当"戳一下" —— 真正读配置还是走原来的
 * {@code ContentProvider + 签名比对}（已经写好且可靠），所以这里零解析、零状态，
 * 收到就 {@link SogouTranslator#pokeReload()}。
 *
 * <p>为什么能绕开包可见性：可见性只限制**发起方**。发广播的是我们 App（清单里
 * {@code <queries>} 声明了搜狗 ✓），而接收端是本模块在搜狗进程里**运行时注册**的，
 * 不需要搜狗 APK 声明任何东西。
 *
 * <p>轮询仍在，但已退成 5 秒兜底（App 不在前台、模块刚重启等情况下补上）。
 */
final class ConfigPoke {

    private static final String TAG = "BZK-SogouOEMExt";
    static final String ACTION = "moe.lovefirefly.bzk.sogouoemext.CONFIG";

    private static volatile boolean sStarted;

    private ConfigPoke() {}

    static void start(final Context ctx) {
        if (sStarted || ctx == null) return;
        // 注意：**注册成功才置 sStarted**。曾经先置真再注册，结果传进来的若是系统
        // Context（包名 android）会抛 SecurityException，却把后续正常注册也堵死了
        // （真机 2026-09-20：poke 一直收不到，开关要等 5 秒轮询）。
        try {
            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    Log.i(TAG, "config poke received -> reload");
                    SogouTranslator.pokeReload();
                }
            };
            final IntentFilter filter = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) {      // targetSdk 34+ 必须显式声明导出
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            sStarted = true;
            Log.i(TAG, "config poke receiver registered");
        } catch (Throwable tr) {
            Log.w(TAG, "config poke receiver failed: " + tr);
        }
    }
}
