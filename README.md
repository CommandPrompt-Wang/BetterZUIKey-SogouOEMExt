<div align="center">

<h1>搜狗输入法联想版增强</h1>
<img src="img/icon.png" width="120" alt="BetterZUIKey-SougouOEMExt">
<p></p>
<p>
   简体中文
</p>

[![Android](https://img.shields.io/badge/API-27%2B-green)](https://developer.android.com/about/versions/8.1) [![Xposed](https://img.shields.io/badge/Xposed-LSPosed-blue)](https://github.com/LSPosed/LSPosed) [![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/) [![Version](https://img.shields.io/badge/Version-1.1.0-blue)](https://github.com/CommandPrompt-Wang/BetterZUIKey-SougouOEMExt/releases) [![License](https://img.shields.io/badge/License-GPL--3.0-orange)](LICENSE)

<p>把联想 OEM 版搜狗输入法改造成「框架 subtype 驱动」，语言切换交还给系统</p>

</div>

> 一条分隔线，决定哪些语言交给框架轮转，哪些只留给输入法自己。

**声明**：本仓库主要部分均为 AIGC，可能有缺陷，欢迎审查和 PR。

<p><sub>应用图标基于搜狗输入法自带图标二次创作，角色形象来源未知，如有侵权请联系删除</sub></p>

---

## 🤔 为啥做这个？

联想平板预装的**搜狗输入法联想 OEM 版**里明明有拼音 / 英语 / 五笔三种语言，但**框架完全不知道它们**——它只声明了一个 subtype。于是：

- 系统与 [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey) 那套「切换到下一个输入法语言」的快捷键按下去没反应（框架里没有下一个 subtype 可切）；
- 搜狗自己的切换键只认它内部那套状态，和框架各说各话；
- 你没法让某个语言出现在轮转里，也没法让它彻底不出现。

补 subtype 的正规 API `setAdditionalInputMethodSubtypes` 帮不上忙：它最终会撞上 `InputMethodSettings.getNewAdditionalSubtypeMap()` 里的 **uid 闸门**（`isSameApp`），root 和 system_server 一样被静默拒绝，**只有搜狗自己的 uid 能写**。

所以本模块注入搜狗 IME 进程，用搜狗自己的身份把 subtype 补进去，并接管它的语言切换链路。

## ✨ 功能特性

- **语言顺序完全由你决定** —— 一条可拖拽顺序 + 一条同样可拖拽的分隔线
  - 线上：暴露成 framework subtype，按这个顺序参与轮转
  - 线下：框架不知道它们，只能从搜狗键盘自己切
  - 从线下切出时**落回线上第一项**
- **严格模式** —— 硬键盘那条路只认 subtype 信号，屏蔽搜狗原生切换键
- **标点管线** —— 语义层（中文标点 / 英文标点 / 智能数字 / 斜杠模式）+ 形式层（`Shift+Space` 全角半角状态位）
- **中文态大写字母** —— 拼音输入里按 `Shift` 直接出大写
- **引号 / 括号自动关闭** —— 两个独立开关共用一份可编辑的匹配列表（默认 18 对）
  - 软键盘侧：闸掉搜狗原生配对，改用你的列表
  - 物理键盘侧：打字即补闭字符并把光标移到中间，`Ctrl+Shift+9` 可临时开关
- **改完最多 2 秒生效** —— 配置每 2 秒轮询一次（签名没变就什么都不做），不用重启输入法
- **与 BetterZUIKey 联动** —— 装了 BZK 会给出配置建议；本模块**不需要** system 作用域

## 📐 工作原理

模块在搜狗 IME 进程里做四件事：**注入 subtype** / **推进 marker** / **改写提交内容** / **拦截配对**。

```
模块 App（LangOrderActivity）
    ↕ ContentProvider IPC（ConfigProvider · 每 2 秒轮询 + 签名比对）
搜狗 IME 进程（BridgeHook）
    ├── SubtypeInjector   用搜狗自己的 uid 补 subtype（绕开 setAdditionalInputMethodSubtypes 的闸门）
    ├── SogouTranslator   marker 推进 / 语言切换命令 / 快捷键与热键 / 配置热重载
    ├── PunctPipeline     在 commitText 上做「语义层 → 形式层」的标点改写
    └── AutoPairHook      软键盘走 UU.a 闸门，物理键盘在 commitText 里补闭字符
```

- subtype 顺序 = 框架 enabled subtype 列表顺序；BZK 侧只做「在本输入法内前进到下一个 subtype」，**不需要知道任何配置**
- 一切都是**降级不崩**：内部符号变了就退回合成 Shift / 走默认表，不会把输入法搞挂
- dex 级逆向、踩坑与实测数据全部整理在 **[PRINCIPLE.md](PRINCIPLE.md)**

## 📦 模块安装

0. **前置条件**：已安装 [LSPosed](https://github.com/LSPosed/LSPosed) + 联想 OEM 版搜狗输入法（`com.sohu.inputmethod.sogou.oem`）
1. 在 [Releases](https://github.com/CommandPrompt-Wang/BetterZUIKey-SougouOEMExt/releases) 下载 APK 并安装
   - 目前还没有正式 release，也可以按下面的「开发构建」自己编一个
2. LSPosed Manager 里启用模块，作用域**只勾** `com.sohu.inputmethod.sogou.oem`（**不需要** system）
3. 打开模块 App，拖好语言顺序与分隔线
4. 弹一次键盘（配置在键盘起来后 500ms 应用，避免打断 IME 初始化）
5. 核对 subtype：`settings get secure enabled_input_methods | tr ':' '\n' | grep -i sogou`（顺序即你拖的顺序）

> 使用 BZK 的输入法快捷键需要 BZK 的新版（统一 `onlyCurrentIme=true` 的 next subtype），并**软重启 system_server**。

## 🔧 开发构建

```bash
git clone git@github.com:CommandPrompt-Wang/BetterZUIKey-SougouOEMExt.git
cd BetterZUIKey-SougouOEMExt
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/BetterZUIKey-SougouOEMExt-v1.1.0.apk
```

需要 JDK 17 + Android SDK 37（`compileSdk 37` / `minSdk 27` / `targetSdk 36`），以及 [libxposed](https://github.com/libxposed/api)（`xposedminversion=93`）。

- 签名同一套签名配置（`app-sign.keystore` + `keystore.properties`，两者都不入库）
- 换过包名/重装后，LSPosed 里要**重新启用**模块并勾作用域；语言顺序配置存在按包名区分的 remote preferences 里，会回到默认值

## 📖 使用方法

主页就是语言顺序：拖**卡片**排顺序，拖**中间那行分隔线**决定谁进框架；排序下方的开关是「只响应系统框架语言切换消息」（严格模式）。底部的「原理 / 说明」（ExtendedFAB）是内置帮助文档。

| 位置 | 效果 |
|------|------|
| 分隔线**上方** | 暴露为 framework subtype，按此顺序轮转 |
| 分隔线**下方** | 不暴露，框架不知道，只能从搜狗键盘手动切 |
| 从下方切出 | 落回上方第一项 |

其余开关与热键：

| 层 | 内容 | 默认 |
|------|------|------|
| 功能开关 | 智能中文标点 / 智能编号 / 大写字母进拼音栏 | 开 |
| 功能开关 | **引号/括号自动关闭**（软键盘侧） | 开 |
| 功能开关 | **物理键盘自动补全**（物理键盘侧） | 开 |
| 功能开关 | 全角模式 / 中英文标点 / 只响应系统框架语言切换消息 | 关 |
| 状态位 | `Shift+Space` 切全/半角，`Ctrl+Shift+9` 切物理键盘补全（不在界面显示，自动记住） | 记忆上次 |
| 条目 | 「编辑匹配列表」配对串（偶数校验，留空 = 用输入法默认规则） | 18 对建议值 |
| 条目 | 「原样输出斜杠」选 `/` 或 `\` 原样输出（搜狗默认把两者都变成 `、`） | 关 |

日志：

```bash
adb shell logcat -s SogouOemBridge

config -> wubi,pinyin,en|2 | applied rotation=[wubi, pinyin] (was [pinyin, wubi])
sync marker: real=en cur=null -> want=pinyin
marker repositioned to pinyin in 1 step(s)
punct: ｛ -> { [half] [cn]
provider: dump self-check = ok (pairMap=18 pairs)
```

## ⚠️ 免责声明

这是一个 LSPosed 模块，直接 hook 输入法的输入链路与提交链路。使用前请：

- 先读内置的「原理 / 说明」，理解每个开关的含义再动手
- 不当配置可能导致**切不到某个语言**、标点/配对行为异常
- 本模块对官方搜狗无效，只针对联想 OEM 版（依赖其内部符号）

开发者不承担因使用本模块造成的输入异常、数据丢失或设备故障的任何责任。

## 📂 项目结构

```
app/src/main/java/moe/lovefirefly/bzk/sougouext/
├── BridgeHook.java          # Xposed 入口 + 开发期开关（DEV_*）
├── SogouTranslator.java     # 核心：subtype 注入时机 / marker 推进 / 快捷键与热键 / 配置轮询
├── SubtypeInjector.java     # 用搜狗身份补 subtype（绕开 uid 闸门）
├── PunctPipeline.java       # 标点管线：语义层（中/英/数字/斜杠）→ 形式层（全/半角）
├── AutoPairHook.java        # 引号括号：UU.a 闸门（软键盘）+ commitText 注入（物理键盘）
├── LangConfig.java          # 配置：dump / parseDump / signature，配对串解析成 Map
├── LangSpec.java            # 语言规格常量（顺序、分隔线、默认值）
├── ConfigProvider.java      # ContentProvider：App → 模块 的配置通道（含 UID 白名单）
├── LangOrderActivity.java   # 首页：语言顺序 + 所有开关（launcher）
├── InfoActivity.java        # 「原理 / 说明」页
├── InfoText.java            # 说明文案
└── Sogou*Probe.java         # 探针：命令注册表 / 状态字段 / 按键路径 / 标点提交点
```

## 📄 许可证

GPL-3.0 © 2025–2026 [CommandPrompt-Wang](https://github.com/CommandPrompt-Wang)
