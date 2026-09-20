# 原理与实现（PRINCIPLE）

> 功能、安装与用法看 [README.md](README.md)。本文是它的技术底稿：dex 级逆向结论、踩坑与实测数据。

把**搜狗输入法联想 OEM 版**改造成「框架 subtype 驱动」的输入法，并且**让用户自己决定
把哪些语言暴露成 subtype**。

## 0. 一条规则

界面里一条可拖拽的顺序 + 一条同样可拖拽的**分隔线**：

- **分隔线上方** → 模块把它们做成 framework subtype（按这个顺序），BZK 的快捷键就按这个顺序轮转；
- **分隔线下方** → 不做成 subtype，框架完全不知道它们，只能从搜狗键盘自己切；
- **从下方的语言切出时，落回上方第一项**（靠模块把 marker 停在"最后一项"实现，见 ③.4）。

排序下方还有一个开关：**「只响应系统框架语言切换消息」**（= 屏蔽搜狗原生的切换按键，见 3.5）。
装了 BZK 时会给出配置建议；没装则灰掉并提示安装。

界面在**模块 App 里**，风格与 BZK 完全一致（Material 3 DayNight、`MaterialCardView` 圆角 12dp
+ elevation 1dp、`textAppearanceTitleMedium/BodySmall`、拖拽用 RecyclerView + ItemTouchHelper、
拖动时 elevation 12f/scale 0.98、松手落盘）：

- **首页 = 语言顺序**（`LangOrderActivity`，launcher）：三张卡片（`≡` 手柄 + 语言名 + 副标题
  "暴露为 subtype · 参与轮转" / "不暴露 · 只能手动切"）+ 中间**可拖的分隔线行** +
  底部 ExtendedFAB「原理 / 说明」；
- **第二屏 = 原理 / 说明**（`InfoActivity`，exported=false）：顶部 `← 返回` 按钮 + 正文；
- 两个界面都自己补系统栏 inset（Android 15+ 强制 edge-to-edge，否则内容会被状态栏压住）。

配置写进 libxposed 的 **remote preferences**（`sogou_lang` 组）。模块读配置有三条途径：

1. 键盘视图创建（`setInputView`）；
2. 每次输入会话开始（`onStartInputView` / `onStartInput`）；
3. **每 2 秒轮询一次**（签名没变就什么都不做）—— 这条是必需的：实测前两条并不总会触发，
   导致开关改了却"没生效"。所以现在**改完设置最多 2 秒生效，不需要重启进程**。
   界面在 Xposed 服务未连接时会明确 Toast 提示"改动未保存"，不再静默失败。

## 1. 三种语言与它们的内部命令

OEM 只有三种语言。每种的切换都是搜狗自己的命令 `cta`（`eP` 注册表里的 `-2`），
目标由 `Bundle` 里的 `keyboardEventId` 决定（实测）：

| 语言 | subtype 身份 | 切过去的命令 | 备注 |
|---|---|---|---|
| 拼音 | `zh-CN` / mode `keyboard` | `cta` + `{keyboardEventId=1002}` | |
| 英语 | `en-US` / mode `keyboard` | `cta` + `{}` | **从五笔出发到不了**，要先切拼音 |
| 五笔 | `zh-CN` / mode `wubi` | `cta` + `{keyboardEventId=1005}` | 从英语也能直接到 |

实测序列（探针自动跑，每步读一次内部状态 `LUa.F()`，0=中文 1=英文）：

```
cta+1002 -> 拼音   F=0
cta+{}   -> 英语   F=1
cta+1005 -> 五笔   F=0
cta+1002 -> 拼音   F=0
```

`dta`（`-6`）实测行为不干净（从拼音按它也变英文），**成品不再使用**。

## 2. 为什么必须是 Xposed 模块（补 subtype 的 uid 闸门）

`setAdditionalInputMethodSubtypes` 最终走到
`InputMethodSettings.getNewAdditionalSubtypeMap()`：

```
checkIfPackageBelongsToUid(pmInternal, callingUid, imi.getPackageName())
    → PackageManagerInternal.isSameApp(...)
if (!belongsTo) return currentMap;      // 静默 no-op
```

`isSameApp` **没有特权 UID 特判**：root(0)、system_server(1000) 都会被静默拒绝，
**只有搜狗自己的 uid** 能过。所以补 subtype 只能在注入搜狗进程的模块里做。

## 3. 模块做的四件事

### 3.1 按配置注入 subtype

`SubtypeInjector.apply(ctx, pkg, cfg)`：把"分隔线上方"的语言按顺序传给
`setAdditionalInputMethodSubtypes` + `setExplicitlyEnabledInputMethodSubtypes`，
**按身份幂等**（比对当前 enabled 里的语言序列，一致就不写）。实测 IMMS 会保留传入顺序，
所以 BZK 的 next 顺序 = 用户拖拽顺序。

### 3.2 marker → 搜狗动作

框架每次下发 subtype（`onCurrentInputMethodSubtypeChanged`）→ 模块识别出语言 → 执行上面的
命令。幂等判断：中/英看 `LUa.B().F()`；拼音/五笔看**跟踪到的方案**（见 3.3）。

### 3.3 方案（拼音/五笔）状态靠观测

`LUa.F()` 只有中/英；拼音/五笔在无参 getter 上**测不出来**（diff 时只有两个单调计数器在动）。
所以方案状态由这些观测维护：

- 模块自己发起的切换（自己知道目标）；
- **严格守卫看到搜狗界面的方案命令**（`keyboardEventId=1002` → 拼音，`1005` → 五笔）；
- 读不到时按拼音兜底。

### 3.4 marker 推进（实现"从下方切出回第一项"）

框架只有一个"当前 subtype 指针"（marker）。BZK 的动作就是 next。为了让"从下方语言切出"
落到**上方第一项**，模块在语言/方案变化后把 marker 摆正（`syncMarker`）：

- 真实语言在**上方** → marker 指向它；
- 真实语言在**下方**（例如五笔）→ marker 停在**最后一项**，这样 BZK 的下一次 next 正好绕回第一项。

推 marker 用的是 IME 公开 API **`InputMethodService.switchToNextInputMethod(true)`**
（实测返回 true 且 marker 真的前进；`setCurrentInputMethodSubtype` 对 IME 自身也返回 false，
所以不能用它）。推进期间 `sSuppress` 屏蔽自己的语言动作 —— marker 动了，语言不跟着动。

### 3.5 严格模式：硬键盘那条路只由 subtype 驱动

界面上由开关控制：**「只响应系统框架语言切换消息」**（配置键 `strict`）。

- 装了 BZK（`getPackageInfo("moe.lovefirefly.betterzuikey")`；模块 App 侧靠 manifest 里的
  `<queries>` 过包可见性）→ 开关可用，并提示：在 BZK 的"输入法增强"里为"搜狗OEM"
  启用 `framework` 模式，然后打开此开关；
- 没装 → 开关灰掉，提示"建议安装以增强功能"；
- 模块侧守卫**常驻安装**，但每次调用都读一次开关 —— 所以改完开关**下次弹键盘就生效**，
  不需要重启搜狗进程。

打开时是**两层拦截**：

**第一层 · 命令级**：给中↔英命令 `cta(-2)` / `dta(-6)` 的执行口 `hP.a(WO, Bundle)` 装守卫，
**按"调用来源"而不是按按键**判定：

| 调用来源 | Bundle | 处理 |
|---|---|---|
| 本模块自己 | `{}` / 带参 | 放行（`ThreadLocal` 标记） |
| **硬键盘那条路**（物理 Ctrl+Shift，以及搜狗映射的其它组合） | `null` | **拦掉** —— 语言只由框架 subtype 决定 |
| 界面按钮（方案键 1002/1005、软键盘中/英键 1007） | `{keyboardEventId=…}` | 放行（"用户手动"那条轴，随后 `syncMarker()` 摆正 marker） |

实测数据（`Bundle` 形状是唯一判据，模块里**没有任何按键硬编码**）：

```
sogou requested command -2 / bundle=null               ← 物理快捷键
sogou requested command -2 / bundle={keyboardEventId=1007}  ← 软键盘中/英键
sogou requested command -2 / bundle={keyboardEventId=1002}  ← 方案键→拼音
sogou requested command -2 / bundle={keyboardEventId=1005}  ← 方案键→五笔
```

拦掉物理那条之后，"自己动作后又冒出两次反方向 subtype 回调"的抖动**消失了**
（那抖动本来就是搜狗自己那次切换回写 subtype 造成的），所以成品**没有任何抑制窗口**，
实测 150ms 间隔的连按也能一一对应。

⚠️ **不要顺手把 `kwa(1002)` / `hwa(1007)` 也拦掉**：它们是软键盘中/英键那条路（`Bundle=null`），
但实测拦截后**软键盘直接弹不出来**（键盘显示也走这条命令）。所以命令级守卫只覆盖 `cta/dta`。

**第二层 · 按键级**（必需）：实测搜狗的 **Ctrl+Space 根本不走 `cta/dta`** —— 既没有
`eP.a(int)` 追踪行、也没有命令级守卫的 blocked 行；它在内部直接切语言并回写 subtype，
命令级守卫看不见。所以在输入法的按键入口
（`coa#onKeyDown/onKeyUp` 与 `InputMethodService#onKeyDown/onKeyUp`）把
**Ctrl+Space / Ctrl+Shift** 直接吞掉：

```
strict: blocked native switch key KEYCODE_SPACE
strict: blocked native switch key KEYCODE_SHIFT_LEFT
```

- 不影响 BZK：它是 system_server 里的 input filter，**先于 IME** 拿到事件，照旧按 subtype 切换；
  我们只是让搜狗看不到这两个组合键（否则就是"一次按键切两次"）；
- 不误伤打字：判定条件是 `metaState` 带 `META_CTRL_ON`，所以 **Shift+字母 大写** 不受影响，
  普通空格也不受影响。

## 3.6 二期：标点管线（功能开关 + 状态位）

**分两层，别混**：

| 层 | 内容 | 位置 | 默认 |
|---|---|---|---|
| **功能开关** | 智能中文标点、全角模式、中英文标点、智能编号、完整的 …… 和 ——；下拉「原样输出斜杠」 | App 界面（写本机 prefs） | 全开（斜杠=关） |

> 下拉与 BZK 同款：`TextInputLayout`（`hintEnabled=false` + `endIconMode=dropdown_menu` +
> `boxBackgroundColor=?attr/colorSurfaceContainerHighest`）+ `MaterialAutoCompleteTextView`
> （`inputType=none`）+ `res/layout/dropdown_item_wrap.xml` 条目。
| **状态位** | 当前是**全角还是半角**、**中文还是英文标点** | **不在界面**：模块自己的 SharedPreferences（搜狗进程 `sogouoemext_state`），快捷键切换并立即落盘 | 半角 + 中文标点 |

- `Shift+Space` → 全角 / 半角（切完弹横幅，状态持久化）
- `Ctrl+.` → 中文标点 / 英文标点（同上）
- 这两个快捷键在这台 OEM 上**没有任何原生行为**（实测无命令追踪、提交内容不变），所以由模块在按键层接管
- 功能开关关闭时对应状态位被忽略：全角模式关 → 恒半角；中英文标点关 → 恒中文标点
- 「完整的 …… 和 ——」**没有状态位也没有快捷键**（用户定）：它就是个纯开关，
  开着各两个、关掉原样放行（搜狗原生单出）

**管线顺序**（语义层决定"是哪个字符"，形式层决定"宽窄"）：

```
斜杠设置（独占 / 与 \，按上一个物理键区分）
  → 英文输入态 或 英文标点状态位 ? ASCII
      : 智能中文标点开 ? 中文标点映射 : 原样
  → 智能编号（数字后的 。/） → 半角 . )）
  → 完整的 …… 和 ——（只在中文标点这一侧：— / … 的连续段归一成 2 个）
  → 全角模式状态位 ? 全角 : 半角
```

**映射表（32 对，实测自本机）**

```
ASCII  : ,./\;:!?()[]<>"'+-*={}|~@#%&^$_`
CHINESE: ，。、、；：！？（）【】《》“‘＋－＊＝｛｝｜～＠＃％＆＾＄＿·
```

三个"本机怪癖"（都实测过）：

| 现象 | 处理 |
|---|---|
| `/` 与 `\` 都产出 `、` | 下拉选 `/` 或 `\` 后：该键原样输出，**另一个键仍出 `、`**（靠上一个物理按键区分） |
| 反引号键输出的是 `·`(U+00B7) | 表里把 `` ` `` 配 `·`，另外兼容 `｀`(FF40) |
| `{` 会被配成对 `｛｝` | 照原样提交（不干预配对） |

**形式层只动 ASCII ↔ FF01–FF5E，且排除 `！？；：，（）`** —— 那些由语义层负责，
否则"半角"会把中文标点也拉成 ASCII，等于绕开语义层的决定。

**「完整的 …… 和 ——」**（`longMarks`，默认开，**纯开关、无快捷键**）：搜狗原生破折号/省略号
**各一个**（`—` U+2014、`…` U+2026），中文排版标准的完整形是**各两个**。所以开启时把
`—`/`…` 的**连续段**归一成 2 个；关闭时**原样放行**（恢复搜狗原生单出）。用"段"而不是
"逐字符翻倍"是为了**幂等**：一次提交里已经有两个（连续两下都进了同一段）不会被翻成四个。
只在中文标点这一侧生效（英文输入态 / 英文标点状态位下原样放行）；命中才新建
StringBuilder，没命中返回 `null` 零额外分配。与 Gboard 侧 `SymbolNorm` 的语义一致。
设置页文案（用户定）：**开启：破折号/省略号各出两个 —— 和 ……；关闭：恢复搜狗原生单出效果**。

**真机实测（1.2.0 构建，用户确认）**：开启态下按一次破折号键出 `——`、省略号键出 `……` ✓
（说明搜狗确实是一次提交一个字符）。

**实现位置**：`android.inputmethodservice.RemoteInputConnection#commitText/setComposingText`
（IME 进程内，用 libxposed 的 `Chain.proceed(Object[])` 替换参数）；按键在 `coa#onKeyDown/onKeyUp`。
启动会打 `punct: table self-check ok (32 pairs)` 自检表；provider 会打 `dump self-check = ok` 自检键名。

**状态提示不用 Toast**：搜狗进程里 `Toast` 会被系统按应用通知设置拦掉
（`NotificationService: Suppressing toast ... by user request`；BZK 能弹是因为它在 system_server）。
改用输入法窗口上的 `PopupWindow` 横幅：贴底居中、离底约屏幕高 12%，1.2 秒消失。

## 3.7 中文态大写字母（`capitalInPinyin`）

**动机**：中文态下搜狗把大写字母**直接上屏**，等于 `Dance` 只有 `ance` 进拼音，
整词候选就没了。开着这个开关时，模块在按键层把大写字母的 Shift **剥掉**再交给搜狗
（于是整词进拼音），然后在**上屏时**按记录把大小写还原。

**只记意图、上屏还原** —— 记的是"这一段组合里每个字母键有没有带 Shift"（`U`/`l`），
上屏走 `commitText` 时套回字母串。**候选栏/拼音栏里那串拼音保持搜狗原样（小写）**：
那是搜狗自绘的（整个 APK 里没有 `android.widget.TextView`），且它的内部缓存
（`IMECoreInterface#updateComposingCache` 的 `UP`）同时喂给内部正则与提交判断，
改显示的收益低、风险高，明确不做。

| 输入 | 上屏 |
|---|---|
| `Shift+D` + `ance` | `Dance` |
| `dancehello`（d/h 大写） | `DanceHello` |
| 无 Shift | 原样不动 |

**三条必须守住的规则**（都踩过）：

1. **只在 `key-down` 记一次** —— 按键钩子同时挂 `onKeyDown`/`onKeyUp`，两边都记会把
   一个字母记成两个（`Dance` → `UllllUllll`），尾部对齐取到全小写，现象是
   "`da` 正常、从 `dan` 起变回小写"。
2. **记录长度必须与字母数完全相等**，否则返回 `null` 一个字不动 —— 残留记录不许乱改。
3. **先还原、再清记录**（`fixCase` 在 `onCompositionEnded()` 之前）；且只有 `commitText`
   才清，空格等不能中途清，否则 `dance hello` 里前一段的大写意图会被吃掉。

## 3.8 引号/括号补全（`autoPair` / `physComplete` / `autoPairTable`）

**动机**：搜狗会把你打的引号/括号自动补上另一半（`{` → `｛｝`），有时反而碍事。
模块在提交链路上拦一下即可关掉，**不需要改它的配对表**。

**咽喉点**（逆向确认，整条查表链只有这一个入口）：

```
UU;->a(InputConnection, CharSequence, int, boolean)Z
  └─ 内层查表：Yja.a(CharSequence)Yja$b   ←  ← _ja.b  ← Wja.b
```

- 该方法返回 `true` = "配对已由我提交"，`false` = "我没配对，你自己提交单字符"
  —— 后者正是原生"没找到配对"的语义，调用方随即按 `length==1` 提交原始序列。

**两个 hook**（`AutoPairHook`，都是懒安装 + 可重试）：

1. **总闸**（`UU.a`）：开关关 → 直接 `false`，跳过整段配对。
   必须挂最外层 —— 即使查表返回 null，外层对 `'［'` 还有硬编码兜底会照样配对。
2. **自定义表**（`Yja.a`）：命中自定义配对串时，造一个 `Yja$b(open, close, open)`
   交回给搜狗，**由它自己提交并定位光标**（复用原生逻辑，比我们自己算绝对光标位置可靠）。
   未命中一律放行，所以自定义表是**叠加**，不是替换。

> 踩坑：dex 描述符 `LUU;` 的运行时类名是 **`UU`**（`L` 是描述符前缀，不是名字的一部分）。
> 写成 `LUU` 会 `ClassNotFoundException`。而 `LYja;` → `Yja` 恰好对，所以当初只有一个 hook 生效。

**配对串格式**：若干「前-后」配对**依次排列**，相邻两字符为一组 ——
`[]()` 表示 `[`↔`]`、`(`↔`)`。因此**长度必须为偶数**（按清洗后算），奇数不予保存。
留空 = 使用输入法默认匹配规则（此时 hook 不介入，完全走搜狗自己的表）。

**界面**：首页开关「引号/括号自动补全」（默认关）；「**编辑匹配列表**」条目弹编辑窗口，
hint 与默认值均为建议串（18 对，按分组排三行）：

```
()[]{}（）［］｛｝＜＞
【】《》〈〉「」『』〖〗〔〕
""''“”‘’
```

输入框是**会自动换行的多行框**。串里的换行只是**给人看的分组**：存储与 dump 都**原样保留**，
只在**校验偶数**和**解析成配对表**之前过一遍 `LangConfig.cleanTable()` 去掉 `\r\n`。
自己排版分组、或从别处粘一段带换行的表，都能直接存。三处共用同一个清洗函数
（UI 校验 / 构造配对表 / 签名），免得出现"UI 按清洗后的长度校验、解析却按原始串切两字符"的错位。

> 踩坑：清洗必须在**解析前**做。换行一旦占掉一个配对位，后面的对全部错位 ——
> 例如 `【】《》〈〉` 会变成 `】`↔`《`，而**对数仍然是 18**，只看数量根本发现不了。
> 所以 provider 的 `dump self-check` 除了比签名，还会把 `pairMap` 与"清洗后两字符一组"
> 的结果比一遍：`ok (pairMap=N pairs)` / `PAIRMAP MISMATCH`。

### 物理键盘补全（`physComplete`，功能 9）

物理键盘这一侧**由模块自己注入**闭字符。但注意：**搜狗在物理键盘上其实也会配对**
（实测它分两次单字符 `commitText` 把 `“` `”` 都提交了），所以配对闸门在硬件按键时
**必须一并拦住它**，否则一次按键会配两遍 —— 搜狗补一遍、我们再补一遍，出来三个字符。
判据是"正在处理硬件按键"（软键盘不走 `onKeyDown`，故可区分）：

- **打字即补**：物理键盘打 `（` → 模块紧接着补 `）`，并把光标移到两者之间
- **触发与定位**：在 `commitText` 钩子里，若满足"**正在处理硬件按键**（软键盘不走 `onKeyDown`
  这条路，故可区分）"+"提交文本恰好 1 个字符"+"该字符是列表里的**开字符**" → 先让开字符正常上屏，
  再补闭字符；光标左移一格用 `getTextBeforeCursor(4096,0).length()` 求绝对偏移，
  **仅在未被上限截断时**移动（拿不到就只补字符、不移动，宁可降级也不乱跳）
- **重入护栏**：注入的提交会再次进入同一个 `commitText` 钩子 —— 用 `ThreadLocal` 标志，
  注入期间原样放行，避免被标点管线二次改写

### 中文引号的奇偶（`KG.e` / `KG.f`）

中文引号 `“”` / `‘’` 是**翻转**出来的，不是固定字符：搜狗用 `KG.d(I)I`（键码 → 中文标点）
把 `"`(34) / `'`(39) 映射成开/闭引号，并翻转 `KG` 上的两个 boolean 字段
（`e` 管单引号、`f` 管双引号）：

```java
int d(int code) {
    int i = 开引号表.indexOf((char) code);
    if (i == -1) return code;
    int cp = 闭引号表.codePointAt(i);
    if (cp == 0x2018) { if (!this.e) cp = 0x2019; this.e ^= 1; }        // ‘ → ’
    else if (cp == 0x201C) { if (!this.f) cp = 0x201D; this.f ^= 1; }   // “ → ”
    return cp;
}
```

**我们替它把配对补全了（一次上屏两个字符），就必须替它多翻一格** —— 否则下一次按键落在
"闭"的奇偶上，只吐出一个 `”`（实测 bug：`“”` 之后按一次得到 `”`）。补偿的判据三条缺一不可：

1. 上屏的这个开引号**来自 `KG.d`**（引号键翻转出来的），不是符号页直接敲的 —— 后者没翻标志位，补翻就错了；
2. 这一下**真的会配对**：物理键盘看功能 9，软键盘看 S；
3. 自定义表里确实有它的闭字符。

> 别拿配对闸门的返回值当判据：物理路径上搜狗只提交单字符，它照样返回 `true`（踩过）。
> 补偿方式就是**再用 `KG.d(34)` / `KG.d(39)` 调一次**、借它翻标志位的副作用，返回的字符丢掉。

### 两个开关 + 热键

| 层 | 内容 | 默认 |
|---|---|---|
| 功能开关（UI） | **S「引号/括号自动补全」** = 软键盘侧 | 关 |
| 功能开关（UI） | **9「物理键盘自动补全」** = 物理键盘侧 | 关 |
| 状态位（不在 UI） | **`Ctrl+Shift+9`** 临时切换物理键盘那一侧，持久化 + 横幅 | 开 |

- 两个开关**各自独立**，**共用同一份匹配列表**
- 「**编辑匹配列表**」是首页的一个条目（点击弹编辑窗口：偶数校验 / 填入建议项 / 留空=用输入法默认规则）

### 匹配列表：内部用 Map 而不是扫字符串

`autoPairTable`（原始串）仍是 UI / dump / 签名 的存储格式，但 `LangConfig` 在构造时把它解析成
**`Map<开字符, 闭字符>`**：

- 查表 O(1)；**方向性由结构保证** —— 闭字符根本不是 key，所以"打闭字符却补出开字符"不会发生
  （旧实现是双向扫描，打 `}` 会产出 `}{`，已修）
- 每 2 个字符一组，末尾落单字符忽略；重复开字符按"首次出现生效"（`putIfAbsent`）

**已知边界**：物理补全依赖"提交发生在硬件按键处理期间"，若某输入法把提交延后到该窗口之外，
则不会注入（宁可不补也不误补）。抓取配置失败时默认两个开关均为开、状态位为开。

**实现位置**：`AutoPairHook`（`UU.a` 总闸 / `Yja.a` 自定义表 / 物理注入），
按键与提交接线在 `SogouTranslator`（`installKeyGuards` / `installPunctuationRewrite`）。

## 3.9 选区接管与按词（`shiftArrowRepair`）

**动机**：部分宿主把“扩选”当成“移动光标”。DSH 的 composer 是 Lexical，它 preventDefault 之后调
`Selection.modify("extend", …, "character")`，而这条 API 在 Android WebView 上退化成移动光标，于是物理键盘
`Shift`+方向键选不了字；Edge 地址栏那种原生 EditText 是正常的。

**怎么定性的**（只读证据，不猜）：键事件确实到得了输入法（`onKeyDown kc=DPAD_LEFT meta=0x41`）；
裸 contenteditable 能选、照着 Lexical 的调用方式模拟就不能；宿主回调全是 `onUpdateSelection(n,n)`（塌的）。

**判定：逐次核对，不做“第一下定性”**（`ShiftArrowRepair`）

- 每次放行水平方向键之前，先自己算一个“期望焦点”；宿主回报的焦点与它一致就继续不插手，不一致就当场接管，
  之后吞掉按键，自己用 `InputConnection.setSelection(anchor, focus)` 落选区（锚点不动）；
- 好宿主的判据分两档：**扩选要“有区间且焦点对上”，移光标要“塌的且位置对上”**。“要扩选却收到塌的”
  一律算失败，否则单独按 `Shift` 时我们期望 ±1，坏宿主也正好挪一格，两边数字相同就漏过去了；
- 反例（踩过）：按“第一下有没有区间”定性，会被 Lexical 反向给出的**假区间**（`(25,26)`）骗过去，
  结果是反向永远不接管。

**三档口径**（都已真机验证）：

| 按键 | 行为 | 步长 |
|---|---|---|
| `Shift`+←/→ | 逐字扩选 | ±1 |
| `Ctrl` / `Alt`+←/→ | 按词移动光标 | ICU 分词 |
| `Ctrl+Shift`+←/→ | 按词扩选 | ICU 分词 |

- 分词用公开 API `android.icu.text.BreakIterator.getWordInstance(Locale)`，它内置中日韩词典，
  和系统原生 Ctrl+方向键是同一套引擎；**不要**用 `android.text.WordIterator`，那是 @hide；
- ICU 把空白也切成分段，`preceding` / `following` 会停在空格上，必须越过空白段去找非空白段，
  否则 `bar 北京大学` 从“北”往左只跳到空格；
- 取文本首选 `getExtractedText()`（整段文本 + `startOffset` + 选区绝对偏移，映射最干净）。退回拼窗口时，
  `getTextBeforeCursor` 相对的是选区起点、`getTextAfterCursor` 相对的是选区终点，必须
  `before + getSelectedText() + after` 三段拼才是绝对连续窗口，少了中间那段会整体错位；
- 垂直键（↑/↓/Home/End/翻页）算不出屏幕行，放行让宿主挪，再在 `onUpdateSelection` 里把
  “锚点 → 宿主挪到的位置”补成区间；
- `isSelfUpdate()`：我们自己补的那次选区回调要跳过 `AutoPairHook.onSelectionChanged`，
  否则会被 closeSkip 当成“用户点了别处”。

**开关 `shiftArrowRepair`**（默认开）：关掉就完全不动方向键。

**同一处顺带修的智能编号**：`123` 打完手动把光标挪走再打 `。`，原来还是输出半角 `.`。判据原来只看
“上一次上屏的末字符是不是数字”，光标这件事完全没参与。现在：每次经手 `commitText` / `setComposingText`
置 pending，紧随的 `onUpdateSelection` 记成基线；之后出现**没有 pending 的**光标变化就是用户挪的，
编号状态随之失效（与 closeSkip 同一套判据）。

> ⚠️ **最大的坑（两次把用户输入法搞坏，务必记住）**：在按键钩子里**包装 `chain.proceed()` 的返回值**
> （哪怕只是加一行日志）会让物理键盘字母上不了屏、搜狗工具栏消失，只剩软键盘可用。
> 定位方式是 `git stash` 回干净版立刻恢复。按键钩子只能做入口埋点，**绝对不要碰返回值与链路**。

## 3.10 解除快捷键设置限制（`unlockHotkeyLimit`）

**动机**：搜狗“外接键盘设置”录快捷键时，`Alt` / `Shift` 系一律被拦，弹的是真 Toast，资源里四条
`不支持设置Alt|Shift+字母|符号组合键`。可底层的组合键模型本来就认它们。

**入口是怎么找到的**（这一招值得复用）：那四条是**资源 id**，代码里只有数字常量，硬反汇编太慢。
改成在搜狗进程挂一个**只读**的 `Toast.makeText` 观察点，捕获一次调用栈：

```
Ysa.afterTextChanged → _sa.a(String)Z → _sa.k → GA.d → GA.f → LA.a → Toast.makeText
```

栈里紧挨 `afterTextChanged`（框架接口方法名，稳定）的那个 App 帧就是总闸 `_sa.a(String)Z`。
`HotkeyLimitUnlock` 按签名 `(String)Z` 反射挂钩，**代码里不出现混淆名**（名字从栈里取）。

**开关 `unlockHotkeyLimit`**（默认关）：开则校验一律返回 true。放开之后冲突、被上层应用抢占都要自行判断，
而且 `Shift`+字母 本身是大写切换键。

**⚠️ 边界（实测，重要）**：这个开关只解决“设置页不让设”，**不解决“运行时没执行”**。本机上硬键盘热键
本身就不触发（连搜狗原生支持的 `Ctrl+Q` 也不触发），而按键确实到得了输入法
（`onKeyDown kc=KEYCODE_H meta=0x12`）。想真正用上 `Alt` / `Shift`+字母，只能由模块自己在
`installKeyGuards` 里加映射。

## 3.11 软硬键盘状态机（`hardKbdLock`）

**症状**：物理键盘态下点输入框会弹软键盘，而软键盘一出现，硬键盘容器页就被销毁，引擎两道闸全灭，
所有硬键盘热键（含 `Alt`+字母）失效。搜狗原生的“软硬键盘切换”只是临时开关一下软键盘，
下次触摸文本框照旧弹。

**机制**（实测 + 反汇编，别再从头挖）：

```
点文本框 → coa.a(EditorInfo,Z) → iP.a(2)  = KeyboardScheduler.startKeyboard(2)，启动软键盘页
                                   ↓
      硬键盘容器页 rua onDestroy → Lta.a(false) → 引擎两道闸全灭（Lta.b() / iP.f()）→ 热键失效

物理键 → qua.onKeyDown → qua.d() = startHardKeyboard → Lta.c(true) + wo.G() → 硬键盘页重建，热键武装
CSP 硬→软 → 命令 -3 = jua = SwitchSoftKeyboard → Lta.b(false) + iP.a(3)
```

| 角色 | 真身 | 关键成员 |
|---|---|---|
| 页容器 | `iP`（`WO.k()`） | `a(I)` = startKeyboard；`f()` = 硬键盘页活着 |
| 硬键盘页 | `rua` | `G()` = onCreate → `Lta.a(true)`；`H()` = onDestroy → `Lta.a(false)` |
| 软键盘页 | `Jra` | `G()` / `H()` → `Gra.a(Z)` |
| 模式条件 | `Lta` | 字段 c = 引擎第一闸（`a(Z)` 写 / `b()` 读）；d = StartFromHardKey（`c(Z)` 写） |
| 进硬键盘 | `qua.d()` | 唯一调用者是 `qua.onKeyDown/Up` |
| 回软键盘 | 命令 -3 = `jua` | `Lta.b(false)` + `iP.a(3)` |

> **类名坑**：dex 描述符 `LiP;` 的运行时类名是 **`iP`**（`Lmua;`→`mua`、`Loua;`→`oua`、`LPra;`→`Pra`），
> 写成 `LiP` 会 CNFE。另外不要靠名字找页容器：`sWo`（`WO` 实例）调 `k()` 就是它。
> `iP.a(3)` **不是**“切硬键盘页”，它是“按当前条件重启调度器”，`qua.d` 与 `jua` 都用它。

**状态机**：状态只有一位 `sModeHard`，只认这几条转移。处理器一律“先改状态、再放行搜狗的动作”，
所以不需要任何时间窗：

| 转移 | 触发 |
|---|---|
| → 硬键盘 | `Lta.c(true)` = StartFromHardKey（`qua.d()` 调） |
| → 硬键盘 | 软键盘态下碰物理键盘（打字、CSP 的 P）。软键盘态下 `qua.d` 走另一分支，钩不到信号，必须从按键侧判 |
| → 软键盘 | 命令 -3 `jua`（工具栏“软键盘”按钮，硬键盘态按 CSP 也走它） |
| → 软键盘 | 同一个框 2 秒内第二次“要软键盘”（`InputMethodImpl.showSoftInput`，`DOUBLE_TAP_MS = 2000`） |
| 按方向 | 插件条目长按（`applyWant`） |

**执行只在搜狗自己的写点上取舍**：

- 硬键盘态：吞掉“输入开始那次” `iP.a(2)`，软键盘页就不起来。这一步很干净，没建任何东西；
- 软键盘态：**不拦**硬键盘页的条件位写入。拦了会让搜狗的页面状态机不一致，软键盘页被拆掉之后再也起不来
  （踩过），改为 250ms 后用它的切软动作夺回（限流 800ms）；
- 硬→软 的驱动 = `Lta.c(true)` + 重启调度器（`page.a(3)`）+ `requestShowSelf(0)`，
  也就是搜狗 `startHardKeyboard` 那套。

**⛔ 三条不能违反的硬约束**：

1. 绝不在框架层拒绝显示（`onShowInputRequested → false`，或吞掉 `showSoftInput`）。窗口被收起后，
   搜狗自己的 `requestShowSelf(0)` 也会被拒，结果是工具栏与候选窗永久消失、输入被阻塞；
2. 不要动 CapsLock，搜狗会把它同步回系统并坏输入（撤过两次）；
3. `iP.a(3)` 不是“切硬键盘页”，别拿它当硬键盘入口。

**已证伪、别再走**：把 `iP.f()` 伪造成恒 true（热键确实常驻了，但 `f()` 同时被热键动作本身用来选页，
于是 CSP 把软键盘锁死、`Alt+H` 只能开不能关）；拦条件位写入；时间窗放行；动 CapsLock；在框架层拒绝显示。

**开关 `hardKbdLock`**（1.2.2 起默认关）：装完不主动接管，需要在设置页打开；长按那一行可以应急切换方向。

## 3.12 热键名怎么读、状态怎么镜像到 App

**为什么要读**：规则弹窗要显示“你在搜狗里绑的那个热键”，而它存在 MMKV 里：
`files/mmkv/HardKeyboardRepository`。

**MMKV 文件布局**（踩过，按这个来）：

- 头 4 字节 = 当前有效数据长度 `actualSize`；有效条目只在 `[4, actualSize)` 内，超出部分是**陈旧数据**；
- 同一个键在有效区内**最后一份**才是当前值，只取“全文件最后一份”会拿到陈旧空值；
- 条目格式 `[len+1][len][value]`；
- MMKV 官方 API 的工厂方法被 R8 改名成单字母 `c(String,int)`（`getString` 没被改），
  走文件解析更省事，而且已经真机验证。

**镜像**：模块把 `hardKbd`（现在是硬键盘还是软键盘）、`hardKbdHotkey`（热键名，没设过则为空）、
`hardKbdAtMs`（时间戳）写进 `sogouext_state_mirror`，App 读它显示状态行。

**为什么要异步拉**：状态在搜狗进程里，模块平时每 5 秒才周期读一次配置。用户刚在搜狗里改完设置
（比如清空热键），App 里看到的还是旧的，所以进前台要主动 poke 一次：

- `fetchStateAsync()`：先置占位，再 `sendConfigPoke()`，然后每 250ms 轮询 `hardKbdAtMs`，
  时间戳 `>=` 本次请求时刻算到货，超过 5 秒显示“获取超时”；
- 状态行与弹窗**各用各的标志**，进前台只刷状态行，点 [查看切换规则] 只刷弹窗，
  因为纯查看的动作不该动下面的状态行；
- 弹窗用自带 `TextView`（`setView`）而不是 `setMessage`，后者在显示过程中改文本不可靠。

**顺带修的坑**：`ConfigProvider.insert` 里把 `e.apply()` 写在中间，后面的 `putString` 会全丢
（热键名永远不落盘）。**apply() 必须放在所有 put 之后。**

## 4. BZK 侧（另一侧的配合）

`IMEDispatcher.switchCurrentImeSubtype()` 现在**统一**为"在本输入法内前进到下一个 subtype"：

```kotlin
synchronized(ImfLock) {
    switchToNextInputMethodLocked(/* onlyCurrentIme = */ true, userData)
    // 没有下一个 subtype 就什么都不做 —— 绝不切到别的输入法
}
```

- 顺序 = 框架 enabled subtype 列表顺序，也就是各输入法自己声明/注入的顺序
  （搜狗那边由本模块按用户拖拽的顺序注入）→ **BZK 不需要知道任何配置**；
- 旧版那套"按 locale 找另一种语言"（`pickTargetSubtype` / `isEnSubtype` / `isZhLocale` /
  `setSubtypeLocked`）已**删除**：它只有中↔英两态，遇到"拼音/五笔都是 zh-CN"或"英文不在
  subtype 里"就会选错、甚至因为 `onlyCurrentIme=false` 跳到别的输入法；
- Ctrl+Shift **永远不会切换输入法**：只有本输入法内没有下一个 subtype 时才什么都不做。

## 5. 用法

1. 装模块 APK，LSPosed 作用域勾 `com.sohu.inputmethod.sogou.oem`（**不需要** system 作用域）。
2. 打开模块 App → **设置语言顺序 / 暴露哪些 subtype** → 拖好顺序和分隔线。
3. 弹出一次键盘（配置在键盘起来后 500ms 应用，避免打断 IME 初始化）。
4. 日志：`adb shell logcat -s BZK-SogouOEMExt`

   ```
   config -> wubi,pinyin,en|2 | applied rotation=[wubi, pinyin] (was [pinyin, wubi])
   sync marker: real=en cur=null -> want=pinyin
   marker repositioned to pinyin in 1 step(s)
   marker pinyin (f=0 scheme=wubi) / executed command -2 (cta) args={keyboardEventId=1002 }
   strict: blocked sogou chord switch (zh->en)
   ```

5. 核对 subtype：`settings get secure enabled_input_methods | tr ':' '\n' | grep -i sogou`
   （顺序即用户拖拽顺序）。

## 6. 开发期开关

| 常量 | 所在类 | 默认 | 作用 |
|---|---|---|---|
| `ENABLE_STRICT` | `BridgeHook` | `true` | 严格模式的编译期总闸（关掉则界面开关无效；运行期开关见配置 `strict`） |
| `DEV_PROBE` / `DEV_STATE_PROBE` | `BridgeHook` | `false` | 枚举命令注册表 / diff 状态字段（后者会自己切语言） |
| `DEV_CMD_TRACE` | `BridgeHook` | `false` | 记录搜狗请求的命令 id + dump 注册表 |
| `DEV_KEY_LOG` | `BridgeHook` | `true` | 打印每个物理按键（定位 Shift+Space / Ctrl+. 走哪条路）。**1.2.2 发布件里也是 true**，下个版本要关掉 |
| `DEV_AUTOPAIR_LOG` | `BridgeHook` | `false` | 记录引号/括号自动配对的拦截命中 |
| `DEV_PUNCT_PROBE` | `BridgeHook` | `false` | 打印提交到 InputConnection 的原始内容（定位全角转换点） |
| `DEV_STATE_WATCH` | `BridgeHook` | `false` | 每 500ms 采样 `LUa.F()` |
| `DEV_SUBTYPE_PROBE` | `BridgeHook` | `false` | 测 IME 进程能否写回 subtype（结论：不能） |
| `DEV_SCHEME_PROBE` | `BridgeHook` | `false` | 找方案状态变量 + 测 `switchToNextInputMethod` |
| `DEV_SEQ_PROBE` | `BridgeHook` | `false` | 自动跑 拼音→英语→五笔→拼音 命令链并读 `F()` |
| `DEV_CB_TRACE` | `SogouTranslator` | `false` | 打印每次 subtype 回调 hash |
| `DEV_TOAST_TRACE` | `HotkeyLimitUnlock` | `false` | 打印捕获到的校验器调用栈（定位“解除快捷键设置限制”用） |
| `DEV_LOG` | `ShiftArrowRepair` | `false` | 选区接管的详细日志（分词窗口、期望焦点核对） |

## 7. 已知边界

- 依赖搜狗 OEM **`29496052` / `1.0.android_pad_lenovo_2024.20260130165252`**（联想 `TB710FU`，Android 16）的内部符号：`cta`/`dta`/`LUa`、`coa.b`/`WO.e`/`eP.b`、
  `LUa.F()`、`hP.a(WO, Bundle)`。变了只降级不崩（命令匹配不到 → 退回合成 Shift；状态读不到 → 每次都切）。
- 分隔线下方放**英语**时，框架里就没有英文可切，快捷键永远到不了英文（"不暴露"的必然结果）。
- **五笔只在物理键盘（搜狗会弹工具栏）下可用**：软键盘只有中/英，没有五笔布局
  （那两条命令的类名就是 `/hardKeyboardMode/...`）。所以把**拼音和五笔同时放在上方**时，
  软键盘上按快捷键会"看不出变化"（两个都是中文、且软键盘没有五笔布局）。界面会在这种配置下给出提示。
- 改配置会**替换 subtype 集合**：如果被移除的那个正是当前 subtype，框架会重建输入法窗口
  （已把应用时机推迟到键盘起来之后，降低影响）。
- 严格模式下屏幕上的中/英键不再能改语言（"只接受 subtype 信号"的代价）。
- 依赖 BZK 的新版（统一 `onlyCurrentIme=true` 的 next subtype），**需要软重启 system_server**。
- 合成 Shift 兜底仍在代码里（命令路径不可用时使用）。
- **硬键盘热键在本机运行时不触发**：能设、能存，但按下去没反应（连搜狗原生的 `Ctrl+Q` 也一样）。
  “解除快捷键设置限制”只解决设置页那道拦截，不解决这件事，详见 3.10。
- 软硬键盘状态机依赖的内部符号是 `iP` / `Lta` / `Gra` / `jua` / `qua` 与 `WO.k()`，
  热键名依赖 `files/mmkv/HardKeyboardRepository` 的文件布局，冲突修复依赖录制器类 `Ysa`。
  这些名字一个都不能写死，全部运行时定位；定位失败时只降级（对应开关不生效），不会崩。
- 软硬键盘那套要**搜狗进程里**的页状态配合：空输入框点击、同一焦点内连点，输入法收不到任何信号，
  这两条路状态机看不到，只能靠其它入口（工具栏按钮、插件长按）。

## 8. 构建

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk（约 8 MB：Material 3 + Kotlin 运行时）
```

> 注意：Material 3 / 现代 AndroidX 的传递依赖里有 Kotlin（`lifecycle-process` 的
> `ProcessLifecycleInitializer` 需要 `kotlin.collections.CollectionsKt`），
> **不能**像早期纯 Java 版那样把 `kotlin-stdlib` 排除掉，否则一启动就
> `RuntimeException: Unable to get provider androidx.startup.InitializationProvider`。
> 想压体积就用 release + R8（`minifyEnabled`），或把界面改成自绘卡片。

**纯逻辑单测（不碰设备）**：`proj/sogou-jvmtest/run.sh` —— 把 `PunctPipeline` / `LangConfig`
连同 android.jar 与 libxposed 的 api aar 用 `javac` 编到 JVM 上跑（`SharedPreferences`
用动态代理造假、`ConfigProvider` 用替身），覆盖标点管线与**配置键往返**。
改这两个文件后先跑它：`dump` / `parseDump` 键名写歪这类 bug（历史上真踩过）在这里就拦住。

### 图标资源（自适应图标）

生成脚本在仓库外：`proj/sogou-icon/make_icons.py <源图> <res 目录>`，
源图 = 搜狗图标二次创作（斜置橙方 + 右下角流萤），144×144、内容 138×138（右下满幅）。

**关键坑：真机会把自适应前景绕中心放大 1.5×**，108dp 画布只有**中间 72dp 可见**，
外圈 18dp 是出血区。所以前景要按 `72/108`（= 画布中央 2/3）画，**不是**按 68dp「安全区」：

- 按 68/108 画 ⇒ 整体偏小、四周留白，右下角的角色自然**贴不到右下角** ✗
- 按 72/108 画 ⇒ 源图正好铺满可见区（432 画布上是 288px，居中、四周各 72px），
  源图右下的角色就正好贴在可见区右下角 ✓

判据（不靠肉眼）：前景内容 bbox 应顶到画布 `72..359` 的边界，即 `-trim` 出 `+83+83` 左右。
旧版 `ic_launcher.png`（API<26，48dp 基准）就是源图本身，两者现在视觉一致。

同一条规律在 BZK Gboard 模块的 `ANALYSIS.md` §24 有实测数据（白键盘宽/图标宽
复刻 0.289 → 真机 0.434，正好 ×1.50）。

## 包名 / 仓库

| 项 | 值 |
|---|---|
| 包名 | `moe.lovefirefly.bzk.sogouoemext` |
| 仓库 | `git@github.com:CommandPrompt-Wang/BetterZUIKey-SogouOEMExt.git` |

换过包名后注意：LSPosed 里要**重新启用**这个模块（作用域由模块静态声明，无需也无法手动勾选）；
语言顺序配置存在模块包名对应的 remote preferences 里，
新包名会是**默认配置**，需要重新拖一次。

## CI（与 BZK 同一套）

| workflow | 触发 | 做什么 |
|---|---|---|
| `android-ci.yml` | PR → `main` | `./gradlew assembleDebug` |
| `nightly-build.yml` | push 到 `dev` 且 commit 以 `[Nightly]` 开头 | 用 CI 密钥签名，上传 artifact |
| `release-apk.yml` | GitHub Release 发布 | 签名打包 → 传到 Release → 镜像到 LSPosed 官方仓库 |

**需要填的 secrets**

| 名字 | 内容 |
|---|---|
| `SIGNING_KEYSTORE` | `app-sign.keystore` 的 base64（本地已生成 `app-sign.keystore.b64`，`cat` 出来即可；该文件不入库） |
| `SIGNING_PASS` | keystore 密码（`keystore.properties` 里的 `storePassword` / `keyPassword`，两者相同） |
| `LSPOSED_REPO_TOKEN` | 可选。镜像到 `Xposed-Modules-Repo/moe.lovefirefly.bzk.sogouoemext` 用的 PAT；**不填则自动跳过该步** |

keyAlias 在 workflow 里写死（需与 keystore 内的 alias 一致）。

**版号与 tag（模仿 BZK）**

- 版本写在 `app/build.gradle.kts` 的 `versionCode` / `versionName`；
- APK 命名：`BetterZUIKey-SogouOEMExt-v<versionName>.apk`；
- LSPosed 镜像 tag：`<versionCode>-<versionName>`。

> nightly 那个 workflow 是按 BZK 原样镜像的（`dev` + `[Nightly]` 前缀）；本仓库目前只有 `main`，
> 想让它跑就需要建一个 `dev` 分支。

## 许可

GPL-3.0 © 2025–2026 [CommandPrompt-Wang](https://github.com/CommandPrompt-Wang)

**声明**：本仓库主要部分均为 AIGC，可能有缺陷，欢迎审查和 PR。

- 功能 / 安装 / 用法：[README.md](README.md)
