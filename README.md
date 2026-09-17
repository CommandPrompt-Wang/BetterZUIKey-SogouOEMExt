<div align="center">

<h1>搜狗输入法联想版增强</h1>
<img src="https://raw.githubusercontent.com/CommandPrompt-Wang/BetterZUIKey-SogouOEMExt/main/img/icon.png" width="120" alt="BetterZUIKey-SogouOEMExt">
<p></p>
<p>
   简体中文
</p>

[![Android](https://img.shields.io/badge/API-27%2B-green)](https://developer.android.com/about/versions/8.1) [![Xposed](https://img.shields.io/badge/Xposed-LSPosed-blue)](https://github.com/LSPosed/LSPosed) [![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/) [![License](https://img.shields.io/badge/License-GPL--3.0-orange)](https://github.com/CommandPrompt-Wang/BetterZUIKey-SogouOEMExt/blob/main/LICENSE)

<p>把联想 OEM 版搜狗输入法的增强模块</p>

</div>

> 君ノ声ガ　聞コエルヨ。
> 
> 你的声音，我能听到呀。

**声明**：本仓库主要部分均为 AIGC，可能有缺陷，欢迎审查和 PR。

<p><sub>应用图标基于搜狗输入法自带图标二次创作；流萤像素画来源未知，如有侵权请联系删除</sub></p>

---

## Le judgement du pécheur / 罪行宣判

### 其一：闭门塞户

联想平板预装的**搜狗输入法联想 OEM 版**里明明有拼音 / 英语 / 五笔三种语言，但它只声明了一个 subtype，因此**框架完全不知道它们**。于是，系统与 [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey) 中那套「切换到下一个输入法语言」不会起任何作用。能且只能通过 Shift 切换输入法语言。

### 其二：粗枝大叶

在汉字输入模式下，输入法只维护了一些的中-英标点映射，但对于列表外的标点，粗暴地全部使用全角输出，包括 `｛｝`、`［］`、`＋`、`－`、`＊`、`＃`等，导致部分要求半角输入的区域必须切换到英文模式。

这个问题已经经过反馈，但没有实质性进展。

另外，很多功能看起来像是半成品：

1. 中文输入时会提供单词建议，却不允许大写字母进入拼音栏触发建议 ¹
2. 软键盘允许括号自动完成，物理键盘却没有此功能
3. PC 端全半角、智能编号等功能均未提供

¹ 当输入“Dance”时，“D” 直接上屏而 “ance”进入拼音栏

------

~~唯一的缺点就是没有广告了~~

本模块通过注入搜狗 IME 进程，用搜狗自己的身份补入 subtype，并接管其语言切换链路；修正英文输入、补全自动完成功能；增加全半角切换……以此将其优化成一个相对可用的输入法。

## 功能特性

- **自定义语言切换**：提供可拖拽顺序的配置界面，自定义切换序列和欲暴露的 subtype
- **严格模式**：屏蔽搜狗原生切换键，语言切换完全由框架管理
  - 这是一个与 [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey) 联动的功能
- **标点管线**：分离中英标点和全角半角状态位，允许独立切换
- **中文态大写字母**：
- **引号 / 括号自动关闭**：两个独立开关共用一份可编辑的匹配列表（默认 18 对）
  - 软键盘：修改搜狗原生配对，改用自定义列表
  - 物理键盘：打字即自动补闭字符，`Ctrl+Shift+9` 可临时切换开关
- **配置热生效** —— 每 2 秒懒检查配置变化，更新配置无需重启输入法
- **与 BetterZUIKey 联动** —— 若安装了 [BetterZUIKey](https://github.com/CommandPrompt-Wang/BetterZUIKey) 会给出配置建议

## 📐 工作原理

模块在搜狗 IME 进程里做四件事：**注入 subtype** / **推进 marker** / **改写提交内容** / **配对与引号**。

```
模块 App（LangOrderActivity）
    ↕ ContentProvider IPC（ConfigProvider · 每 2 秒轮询 + 签名比对）
搜狗 IME 进程（BridgeHook）
    ├── SubtypeInjector   用搜狗自己的 uid 补 subtype（绕开 setAdditionalInputMethodSubtypes 的闸门）
    ├── SogouTranslator   marker 推进 / 语言切换命令 / 快捷键与热键 / 配置热重载
    ├── PunctPipeline     在 commitText 上做「语义层 → 形式层」的标点改写
    └── AutoPairHook      配对三件套：闸门 UU.a · 自定义表 Yja.a · 引号标志位 KG.d
                          · 软键盘：闸门放行，由搜狗按自定义表提交开+闭
                          · 物理键盘：闸门拦住搜狗，改由模块注入闭字符并把光标移进中间
```

- 自定义表若留空则继续走搜狗原有的配对规则
- 中文引号由 `KG.d` 决定，每按一次就翻转 `KG.e`/`KG.f`。模块一次上屏两个字符，就得多替它翻一格，否则下一次按键只吐出一个 `”`
- subtype 顺序 = 框架 enabled subtype 列表顺序
- 若由于版本更新导致内部符号变化则退回合成 Shift / 走默认表，避免造成崩溃
- dex 级逆向、踩坑与实测数据全部整理在 **[PRINCIPLE.md](https://github.com/CommandPrompt-Wang/BetterZUIKey-SogouOEMExt/blob/main/PRINCIPLE.md)**

## 模块安装

0. **前置条件**：已安装 [LSPosed](https://github.com/LSPosed/LSPosed) + 联想 OEM 版搜狗输入法（`com.sohu.inputmethod.sogou.oem`）
1. 在 [Releases](https://github.com/CommandPrompt-Wang/BetterZUIKey-SogouOEMExt/releases) 下载 APK 并安装
2. LSPosed Manager 里启用模块即可 —— 作用域由模块**静态声明**（`module.prop` 里 `staticScope=true`，`scope.list` 只有 `com.sohu.inputmethod.sogou.oem`），无需也无法手动勾选
3. 打开模块 App，拖好语言顺序与分隔线
4. 杀死输入法进程
5. 多次进入/退出编辑以触发键盘弹出

> 若要使用“只响应系统框架语言切换消息”功能，需要 BZK v1.6.1 以上的版本。否则输入法将收不到任何消息。

## 开发构建

```bash
git clone git@github.com:CommandPrompt-Wang/BetterZUIKey-SogouOEMExt.git
cd BetterZUIKey-SogouOEMExt
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/BetterZUIKey-SogouOEMExt-v<versionName>.apk
```

需要 JDK 17 + Android SDK 37（`compileSdk 37` / `minSdk 27` / `targetSdk 36`），以及 [libxposed](https://github.com/libxposed/api)（`xposedminversion=93`）。

- 请自备 `app-sign.keystore` 和 `keystore.properties`

## 使用方法

主页就是语言顺序：拖动卡片改变切换顺序，分隔线上下分别决定接入/不接入框架（不接入则无法进入快捷键轮换）

排序功能下面的开关与热键：

| 功能 | 解释 | 默认值 |
|------|------|--------|
| 智能中文标点 | 更合理的中文标点符号，使用半角的 `+-*#[]` 等符号 | 开 |
| 智能编号 | 中文模式下，任意数字后面的 `。` `）` 改用半角，以形成 `1.` `2)` 这类编号 | 开 |
| 大写字母进拼音栏 | 中文态下 `Shift`+字母也整词进拼音，上屏时按记录还原大小写<br/>切换快捷键：`Ctrl+Shift+9` | 开 |
| 引号/括号自动补全 | 软键盘打 `（` → 自动补 `）` 并把光标移进中间；配对规则来自「编辑匹配列表」 | 关 |
| 物理键盘自动补全 | 物理键盘打 `（` → 模块注入 `）` 并移光标；`Ctrl+Shift+9` 可临时开关 | 关 |
| 全角模式 | 标点与数字全部输出全角（`，` `１`），关闭则半角<br/>切换快捷键：`Shift+Space` | 开 |
| 中英文标点 | 中文态下也输出 ASCII 标点（英文标点模式）<br/>切换快捷键：`Ctrl+.` | 开 |
| 只响应系统框架语言切换消息 | 严格模式：屏蔽搜狗原生切换键，只接受框架信号 | 关 |
| 条目「编辑匹配列表」 | 自定义「前-后」配对串，长度必须是偶数；留空 = 用输入法默认匹配规则 | 18 对建议值 |
| 条目「原样输出斜杠」 | 搜狗把 `/` 和 `\` 都输出成 `、`；可以选一个原样保留 | 关 |

日志：

```bash
adb shell logcat -s BZK-SogouOEMExt

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
- 本模块对工班搜狗无效，只针对联想 OEM 版

开发者不承担因使用本模块造成的输入异常、数据丢失或设备故障的任何责任。

## 项目结构

```
app/src/main/java/moe/lovefirefly/bzk/sogouoemext/
├── BridgeHook.java         # Xposed 入口 + 开发期开关（DEV_*）
├── SogouTranslator.java    # 核心：subtype 注入时机 / marker 推进 / 快捷键与热键 / 配置轮询
├── SubtypeInjector.java     # 用搜狗身份补 subtype（绕开 uid 闸门）
├── PunctPipeline.java       # 标点管线：语义层（中/英/数字/斜杠）→ 形式层（全/半角）
├── AutoPairHook.java       # 引号括号：UU.a 闸门 · Yja.a 自定义表 · KG.d 引号标志位
│                             #   软键盘放行搜狗配对；物理键盘拦住搜狗、改由模块注入闭字符
├── LangConfig.java         # 配置：dump / parseDump / signature，配对串解析成 Map
├── LangSpec.java          # 语言规格常量（顺序、分隔线、默认值）
├── ConfigProvider.java      # ContentProvider：App → 模块 的配置通道（含 UID 白名单）
├── LangOrderActivity.java   # 首页：语言顺序 + 所有开关（launcher）
├── InfoActivity.java         # 「原理 / 说明」页
├── InfoText.java            # 说明文案
├── Sogou*Probe.java       # 开发期探针：命令注册表 / 状态字段 / 按键路径 / 标点提交点 / subtype 写回
└── SogouStateWatch.java   # 开发期探针：每 500ms 采样 LUa.F()，只在变化时打日志
```


## 📄 许可证

GPL-3.0 © 2025–2026 [CommandPrompt-Wang](https://github.com/CommandPrompt-Wang)
