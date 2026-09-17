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
| **功能开关** | 智能中文标点、全角模式、中英文标点、智能编号；下拉「原样输出斜杠」 | App 界面（写本机 prefs） | 全开（斜杠=关） |

> 下拉与 BZK 同款：`TextInputLayout`（`hintEnabled=false` + `endIconMode=dropdown_menu` +
> `boxBackgroundColor=?attr/colorSurfaceContainerHighest`）+ `MaterialAutoCompleteTextView`
> （`inputType=none`）+ `res/layout/dropdown_item_wrap.xml` 条目。
| **状态位** | 当前是**全角还是半角**、**中文还是英文标点** | **不在界面**：模块自己的 SharedPreferences（搜狗进程 `sogouoemext_state`），快捷键切换并立即落盘 | 半角 + 中文标点 |

- `Shift+Space` → 全角 / 半角（切完弹横幅，状态持久化）
- `Ctrl+.` → 中文标点 / 英文标点（同上）
- 两个快捷键在这台 OEM 上**没有任何原生行为**（实测无命令追踪、提交内容不变），所以由模块在按键层接管
- 功能开关关闭时对应状态位被忽略：全角模式关 → 恒半角；中英文标点关 → 恒中文标点

**管线顺序**（语义层决定"是哪个字符"，形式层决定"宽窄"）：

```
斜杠设置（独占 / 与 \，按上一个物理键区分）
  → 英文输入态 或 英文标点状态位 ? ASCII
      : 智能中文标点开 ? 中文标点映射 : 原样
  → 智能编号（数字后的 。/） → 半角 . )）
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

## 6. 开发期开关（`BridgeHook`）

| 常量 | 默认 | 作用 |
|---|---|---|
| `ENABLE_STRICT` | `true` | 严格模式的编译期总闸（关掉则界面开关无效；运行期开关见配置 `strict`） |
| `DEV_PROBE` / `DEV_STATE_PROBE` | `false` | 枚举命令注册表 / diff 状态字段（后者会自己切语言） |
| `DEV_CMD_TRACE` | `false` | 记录搜狗请求的命令 id + dump 注册表 |
| `DEV_CB_TRACE` | `false` | 打印每次 subtype 回调 hash |
| `DEV_STATE_WATCH` | `false` | 每 500ms 采样 `LUa.F()` |
| `DEV_SUBTYPE_PROBE` | `false` | 测 IME 进程能否写回 subtype（结论：不能） |
| `DEV_SCHEME_PROBE` | `false` | 找方案状态变量 + 测 `switchToNextInputMethod` |
| `DEV_SEQ_PROBE` | `false` | 自动跑 拼音→英语→五笔→拼音 命令链并读 `F()` |
| `DEV_KEY_LOG` | `false` | 打印每个物理按键（定位快捷键走哪条路） |
| `DEV_PUNCT_PROBE` | `false` | 打印提交到 InputConnection 的原始内容（定位全角转换点） |

## 7. 已知边界

- 依赖搜狗 OEM 29496052 的内部符号：`cta`/`dta`/`LUa`、`coa.b`/`WO.e`/`eP.b`、
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
