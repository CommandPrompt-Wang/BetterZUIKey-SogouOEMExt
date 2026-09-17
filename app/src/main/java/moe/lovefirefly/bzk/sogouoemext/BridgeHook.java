package moe.lovefirefly.bzk.sogouoemext;

import android.content.Context;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 搜狗 OEM 专用桥接模块。
 *
 * <p>让搜狗变成「框架 subtype 驱动」的输入法：
 * <ol>
 *   <li>{@link SubtypeInjector}：在搜狗自己的进程/uid 下补出 zh-CN / en-US 两个 subtype
 *       （只有输入法自己的 uid 能过 IMMS 的 isSameApp 闸门）；</li>
 *   <li>{@link SogouTranslator}：把框架 subtype 变化翻译成搜狗内部的中英切换。</li>
 * </ol>
 *
 * <p>只需要把搜狗勾进 LSPosed 作用域，<b>不需要</b> system_server 作用域。
 */
public class BridgeHook extends XposedModule {

    private static final String TAG = "BZK-SogouOEMExt";
    private static final String SELF_PKG = "moe.lovefirefly.bzk.sogouoemext";
    private static final String SOGOU_PKG = "com.sohu.inputmethod.sogou.oem";

    /**
     * 严格模式总开关：拦掉搜狗自己的中英切换，语言只由框架 subtype 驱动。
     *
     * <p>日志已证实守卫能命中搜狗自己的切换路径（{@code blocked sogou self switch}）；
     * 这里先置 false 做基准重测，确认基础路径后再打开。
     */
    static final boolean ENABLE_STRICT = true;

    /** 开发期：追踪搜狗请求的命令 id + dump 注册表（定位其它语言路径时打开）。 */
    static final boolean DEV_CMD_TRACE = false;

    /** 开发期：打印每个物理按键（定位 Shift+Space / Ctrl+. 走哪条路）。 */
    static final boolean DEV_KEY_LOG = false;

    /** 开发期：记录引号/括号自动配对的拦截命中（功能 S）。 */
    static final boolean DEV_AUTOPAIR_LOG = false;

    /** 开发期：抓全角标点在哪儿产生（sendKeyChar / InputConnection）。 */
    static final boolean DEV_PUNCT_PROBE = false;

    /** 开发期：采样 LUa.F()（切五笔等不触发 subtype 回调的状态也能看到）。 */
    private static final boolean DEV_STATE_WATCH = false;

    /** 开发期：测试输入法进程能否改框架当前 subtype（BZK 只做 next 方案的关键前提）。 */
    private static final boolean DEV_SUBTYPE_PROBE = false;

    /** 开发期：找方案（拼音/五笔）状态变量 + 测 switchToNextInputMethod。 */
    private static final boolean DEV_SCHEME_PROBE = false;

    /** 开发期：自动跑 拼音→英语→五笔→拼音 命令链并读 F()。 */
    private static final boolean DEV_SEQ_PROBE = false;

    /** 开发期探针开关（枚举命令注册表用），成品关闭。 */
    private static final boolean DEV_PROBE = false;

    /** 开发期：状态读取口探测（会自动执行一次 cta/dta 并 diff，成品必须关闭）。 */
    private static final boolean DEV_STATE_PROBE = false;

    private static final Set<String> sHandled = ConcurrentHashMap.newKeySet();

    public BridgeHook() {
        super();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        final String pkg = param.getPackageName();
        final ClassLoader cl = param.getClassLoader();
        if (pkg == null || SELF_PKG.equals(pkg)) return;
        if (!SOGOU_PKG.equals(pkg)) return;          // 本模块是搜狗特化，其它包不处理
        if (!sHandled.add(pkg)) return;

        Thread t = new Thread(() -> {
            try {
                final Context ctx = systemContext();
                if (ctx == null) {
                    Log.w(TAG, "no system context");
                    return;
                }
                // 真实来源是 App 的本机 prefs（ContentProvider）。remote prefs 由框架托管，
                // 本模块没有任何写入者 → 读出来是全默认值（strict=false 之类），只能当兜底。
                LangConfig cfg = LangConfig.loadFromProvider(ctx.getContentResolver());
                if (cfg == null) cfg = LangConfig.load(this);
                Log.i(TAG, "subtype: " + SubtypeInjector.apply(ctx, pkg, cfg));
                final boolean bzk = hasBetterZUIKey(ctx);
                final boolean strict = ENABLE_STRICT && cfg.strict;
                SogouTranslator.setStrict(strict);
                SogouTranslator.setCommandTrace(DEV_CMD_TRACE);
                Log.i(TAG, "betterzuikey detected=" + bzk + ", strict switch=" + cfg.strict
                        + " -> " + (strict ? "strict (language driven only by subtype)"
                                           : "normal (sogou own chord also switches)"));
                SogouTranslator.install(this, cl, ctx, pkg);
                if (DEV_SUBTYPE_PROBE) {
                    SogouSubtypeProbe.install(this, cl, ctx);
                }
                if (DEV_SCHEME_PROBE) {
                    SogouSchemeProbe.install(this, cl);
                }
                if (DEV_SEQ_PROBE) {
                    SogouSequenceProbe.install(this, cl);
                }
                if (DEV_STATE_WATCH) {
                    SogouStateWatch.install(this, cl);
                }
                if (DEV_STATE_PROBE) {
                    SogouStateProbe.install(this, cl);
                }
                if (DEV_PROBE) {
                    // 开发期探针：枚举 WO.e 命令注册表（类比 cta/dta）。成品默认关闭。
                    SogouCommandProbe.install(this, cl);
                }
            } catch (Throwable tr) {
                Log.w(TAG, "init failed: " + tr);
            }
        }, "sogou-bridge");
        t.setDaemon(true);
        t.start();
    }

    /** BZK 是否安装（搜狗 targetSdk 29，不受 Android 11 包可见性过滤限制）。 */
    private static boolean hasBetterZUIKey(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo("moe.lovefirefly.betterzuikey", 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Context systemContext() throws Exception {
        Object at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null);
        if (at == null) return null;
        return (Context) at.getClass().getMethod("getSystemContext").invoke(at);
    }
}
