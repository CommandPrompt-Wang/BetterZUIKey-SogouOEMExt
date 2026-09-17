package moe.lovefirefly.bzk.sogouoemext;

/** 模块说明文字（首页"说明"页与 LSPosed 入口的 InfoActivity 共用一份）。 */
final class InfoText {

    private InfoText() {}

    static String text() {
        return "搜狗输入法联想版增强\n" +
            "\n" +
            "让搜狗 OEM 变成「框架 subtype 驱动」的输入法。\n" +
            "\n" +
            "① 暴露 subtype\n" +
            "   在搜狗自己的进程/uid 下调 setAdditionalInputMethodSubtypes +\n" +
            "   setExplicitlyEnabledInputMethodSubtypes，补出 中文(zh-CN) / English(en-US)。\n" +
            "   root 与 system_server 都会被 IMMS 的 isSameApp 闸门静默拒绝，\n" +
            "   只有输入法自己的 uid 能通过 —— 所以这一步只能做成 Xposed 模块。\n" +
            "\n" +
            "② 切换交给框架\n" +
            "   有了两个 subtype 之后，框架的 subtype 机制就真的能用了。\n" +
            "\n" +
            "③ 翻译（本模块核心）\n" +
            "   实测搜狗收到 subtype 变化后内部状态一个字段都不动（它不认 subtype），\n" +
            "   所以由本模块把 subtype 变化翻译成搜狗自己的语言切换命令：\n" +
            "     - 注册表链 coa.b→WO、WO.e→eP，命令在 eP.b（SparseArray<hP>，31 条）\n" +
            "     - 语言命令成对：cta(-2) 中→英、dta(-6) 英→中（按类名匹配，不写死 id）\n" +
            "     - 执行 hP.a(WO, Bundle)，必须在主线程（内部 LiveData）\n" +
            "\n" +
            "   幂等：先读搜狗真实语言（LUa.B().F()，0=中 1=英），与目标一致就不动作，\n" +
            "   所以不会出现\"按一次切两次\"。没有任何时间窗口，连按一一对应。\n" +
            "\n" +
            "   严格模式（检测到 BetterZUIKey 时自动开启）：给 cta/dta 的执行口装守卫，\n" +
            "   非本模块发起的执行全部吞掉 —— 物理 Ctrl+Shift 与屏幕中/英键都不再能改语言，\n" +
            "   语言只由框架 subtype 驱动（\"只接受 subtype 的信号\"）。\n" +
            "   代价：屏幕中/英键在严格模式下变成无操作。\n" +
            "\n" +
            "   合成 Shift 单击仍保留为兜底（找不到命令表时使用）：\n" +
            "     - 主线程派发（搜狗内部用 LiveData，后台线程会抛异常）\n" +
            "     - down/up 共享同一 downTime\n" +
            "     - 真实 deviceId / scanCode=42 / source=SOURCE_KEYBOARD\n" +
            "\n" +
            "用法：LSPosed 里启用本模块即可（作用域已静态声明为\n" +
            "com.sohu.inputmethod.sogou.oem，无需也无法手动勾选），\n" +
            "然后切到搜狗一次。日志：logcat -s BZK-SogouOEMExt\n"
        ;
    }
}
