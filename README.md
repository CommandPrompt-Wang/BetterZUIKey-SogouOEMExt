# Sogou OEM Bridge

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

## 3.6 二期：标点管线（三个开关）

搜狗把中文态下的标点**硬编码**转成中文/全角（shared_prefs 84 键、MMKV 二十多个库都没有开关），
而且发生在我们之前 —— 到 `commitText` 时字符串已经是 `，＋｛` 了。所以只能在这个点上做改写。

**管线（顺序很重要）：`{开关3;开关1，3 优先于1} → 开关2`**

1. 语义层决定"是哪个字符"，形式层决定"它的宽窄形态"，所以形式层必须最后套；
2. 开关1「智能中文标点」开 = 按映射表归一到中文标点（搜狗本来就做）；关 = 按键盘标点；
3. 开关3「中英文标点切换」（快捷键 **Ctrl+.**）= 按键盘显示的标点输出（ASCII），**优先于开关1**；
4. 开关2「全角模式」（快捷键 **Shift+Space**）= 标点/字母/数字/空格全角，关则半角。
   形式层**只动 ASCII 与 FF01–FF5E**，且排除语义层负责的 `！？；：，（）` —— 否则"半角模式"
   会把中文标点也拉成 ASCII，等于绕开开关1/3 的决定。

**映射表（32 对，实测自本机）**

```
ASCII  : ,./\;:!?()[]<>"'+-*={}|~@#%&^$_`
CHINESE: ，。、、；：！？（）【】《》“‘＋－＊＝｛｝｜～＠＃％＆＾＄＿·
```

三个"本机怪癖"（都实测过）：

| 现象 | 处理 |
|---|---|
| `/` 与 `\` 都产出 `、` | 反向映射靠**上一个物理按键**消歧：按 `/` 得 `/`，按 `\` 得 `\` |
| 反引号键输出的是 `·`(U+00B7)，不是 `` ` `` 也不是 `｀` | 表里把 `` ` `` 配 `·`，另外兼容 `｀`(FF40) |
| `{` 会被配成对 `｛｝` | 照原样提交（不干预配对） |

**快捷键与状态**：Shift+Space / Ctrl+. 在这台 OEM 上**没有任何原生行为**（实测：无命令追踪、
提交内容不变），所以由模块在按键层接管；切换出来的状态是**运行期内存态**（不落盘，
UI 上的开关是持久默认值）。

**实现位置**：`android.inputmethodservice.RemoteInputConnection#commitText/setComposingText`
（IME 进程内，可用 libxposed 的 `Chain.proceed(Object[])` 替换参数）；按键在
`coa#onKeyDown/onKeyUp`。启动时会打一行 `punct: table self-check ok (32 pairs)` 自检表。

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
- 代价（已知并接受）：GBoard 这种多 subtype 输入法会按 中文 → Alphabet → 日本語 的顺序循环。
- Ctrl+Shift **永远不会切换输入法**：只有本输入法内没有下一个 subtype 时才什么都不做。

## 5. 用法

1. 装模块 APK，LSPosed 作用域勾 `com.sohu.inputmethod.sogou.oem`（**不需要** system 作用域）。
2. 打开模块 App → **设置语言顺序 / 暴露哪些 subtype** → 拖好顺序和分隔线。
3. 弹出一次键盘（配置在键盘起来后 500ms 应用，避免打断 IME 初始化）。
4. 日志：`adb shell logcat -s SogouOemBridge`

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

## 包名 / 签名 / 仓库

| 项 | 值 |
|---|---|
| 包名 | `moe.lovefirefly.bzk.sougouext` |
| 签名 | 同一套签名配置（`app-sign.keystore` + `keystore.properties`，两者都**不入库**；证书 SHA-256 `***REMOVED***`） |
| 仓库 | `git@github.com:CommandPrompt-Wang/BetterZUIKey-SougouOEMExt.git` |

换过包名后注意：LSPosed 里要**重新启用**这个模块并勾选作用域
`com.sohu.inputmethod.sogou.oem`；语言顺序配置存在模块包名对应的 remote preferences 里，
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
| `LSPOSED_REPO_TOKEN` | 可选。镜像到 `Xposed-Modules-Repo/moe.lovefirefly.bzk.sougouext` 用的 PAT；**不填则自动跳过该步** |

keyAlais 在 workflow 里写死为 `betterzuikey.sign`（同一套签名配置 / alias）。

**版号与 tag（模仿 BZK）**

- 版本写在 `app/build.gradle.kts` 的 `versionCode` / `versionName`（当前 `1` / `1.0.0`）；
- APK 命名：`BetterZUIKey-SougouOEMExt-v<versionName>.apk`；
- LSPosed 镜像 tag：`<versionCode>-<versionName>`（当前 `1-1.0.0`）。

> nightly 那个 workflow 是按 BZK 原样镜像的（`dev` + `[Nightly]` 前缀）；本仓库目前只有 `main`，
> 想让它跑就需要建一个 `dev` 分支。

## 许可

GPL-3.0
