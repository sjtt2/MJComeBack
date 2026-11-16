# MJ 彩蛋回归 · 第二轮复审（REVIEW-2）

> 路径说明：本文写作于目录整理之前，文中 `com.mjcomeback.easteregg` 现为 `io.github.sjtt2.mjcomeback`；
> `tools/wiki-modern-api.md` 现在 `docs/wiki-modern-api.md`，`tools/dexstrings.py` 现在 `tools/scratch/dexstrings.py`，
> `tools/emu-*.png` 等调试截图现在 `_scratch/old-debug/`（不入库）。

复审对象：`app/src/main/java/com/mjcomeback/easteregg/*`（9 个类，17:24–17:35 的版本）、
`AndroidManifest.xml`、`build.gradle.kts`、`META-INF/xposed/{module.prop,scope.list,java_init.list}`、
`tools/build_assets.ps1`、`tools/emu-*.png`、`README.md`。

复审方式：**纯静态复审，未改动任何源码**。为了核对框架契约，我另外读了
`tools/libxposed/src/x/io/github/libxposed/api/*`（API 102 源码）、`tools/libxposed/svc/AndroidManifest.xml`
（`libxposed-service` AAR 的 provider 声明）与 `tools/wiki-modern-api.md`。
构建产物 `app/build/outputs/apk/release/app-release.apk` 的时间戳（17:36）晚于最后一次源码改动（17:35），
说明 `build-log.txt` 里那次 `BUILD SUCCESSFUL` 就是当前代码——**编译通过这一条本机可以确认**，
但它同样不能证明运行期行为，下面第 3 节会专门说证据缺口。

**本轮总评：上一轮的 3 个【严重】问题在机制层面都已经改对了（S3 彻底修好，S1/S2 的"取资源/取配置"路径都换成了正确 API），
M1/M2/M3/M7 也有实质改善。但引入了 3 个会导致"S1/S2 症状复发"的新风险（N1/N2/N3），
另有 3 个功能/体验层面的正确性问题（N4/N5/N6）——其中 N6（拼音误触发）和 N5（被输入法挡住）
是这次重写才暴露出来的、用户一定会遇到的。结论仍是"不能就这么发版"，但已经离可发版很近。**

---

## 0. 速览

| # | 严重度 | 现象 | 位置 | 何时出现 |
|:-|:-|:-|:-|:-|
| N1 | 重要 | 变更监听是"弱引用"注册，GC 后设置静默失效（= S2 复发） | `MjSettings.java:42` | 任意时刻，GC 后 |
| N2 | 重要 | 进程名白名单靠猜：私信/评论若在未匹配的子进程，功能静默消失 | `ModuleMain.java:94-101` | 命中非白名单子进程 |
| N3 | 重要 | `MjAssets` 兜底会把**抖音的 APK** 当模块 APK，`init` 失败时 S1 整条复发且只有一行 WARN | `MjAssets.java:39-47, 54-58` | `getModuleApplicationInfo()` 异常/为空 |
| N4 | 重要 | "降采样"上限 900 = 素材原始宽度，1080p 机器上完全不降采样；素材未降采样、无缓存、无取消 | `MjOverlay.java:35-36, 106-123, 141-155` | 任何触发 |
| N5 | 重要 | 叠加层在输入法窗口**之下**，打字时被键盘挡住；Dialog 内触发时被小窗口裁切 | `MjEngine.java:41`、`MjOverlay.java:100` | 任何真实输入场景 |
| N6 | 重要 | 拼音/注音输入法拼写 "mj" 时也会触发（composing text 未过滤） | `ModuleMain.java:219-226` | 中文输入法用户 |
| N7 | 建议 | 未连接服务时设置页仍可写，写进本地 prefs，看着像生效 | `SettingsActivity.java:106-166` | 未连接 LSPosed 时 |
| N8 | 建议 | 窗口被销毁后定时器仍在，音效继续响到 3.15s；无 `OnCompletionListener` | `MjOverlay.java:185-238` | 弹窗/页面被提前关闭 |
| N9 | 建议 | 音效 `materialize` 在主线程；解码队列无上限、无取消、无复用缓存 | `MjOverlay.java:102-104, 107, 188` | 连打 / 首帧 |
| N10 | 建议 | `module.prop` 收尾、README 未同步、A3/A5/A6 残留、debug 签名/proguard 死配置 | 多处 | — |

---

## 1. 与 REVIEW.md 的逐条对照

约定：**✅ 已修**（代码层面已正确）/ **🟡 部分**（方向对，仍有缺口）/ **❌ 未动**

| 编号 | 上轮结论 | 本轮状态 | 说明 |
|:-|:-|:-|:-|
| **S1** | 宿主里 `context.getAssets()` 拿不到模块素材，功能 100% 失效 | **✅ 逻辑已改对（待真机验证）** | 新增 `MjAssets`，用 `java.util.zip.ZipFile` 直接读模块 APK；解码入参从 `AssetManager` 改成 `ByteBuffer`（`ImageDecoder.createSource(ByteBuffer)`，且用的是 `allocateDirect`，这点写对了）。`getModuleApplicationInfo().sourceDir` 正是 API 102 为这个场景准备的 API（`XposedInterface.java:529-533`）。**但**兜底分支与失败静默见 N3。 |
| **S2** | 设置写私有 SharedPreferences，宿主读 Remote Preferences，四个开关全摆设 | **✅ 逻辑已改对（待真机验证）** | `MjApp`+`MjService`+`XposedServiceHelper` 的骨架正确；Manifest 手写 `<provider … :${applicationId}.XposedService>` 与 service AAR 自带的声明逐字一致，`implementation(files(...service.jar))` + `compileOnly(api.jar)` 的拆法也对（jar 不带 manifest，所以必须手写 provider，这一步没漏）。**但**监听器弱引用（N1）与"未连接仍可写"（N7）会削弱它。 |
| **S3** | `Theme.DeviceDefault.DayNight` 需要 API 29，API 26–28 打开模块 App 必崩 | **✅ 已修** | 改为 `@style/AppTheme` → `Theme.DeviceDefault.Light`（values）/ `Theme.DeviceDefault`（values-night），API 14 起就有，minSdk 26 安全。 |
| **M1** | 全量 hook `View#onAttachedToWindow`，成本/风险远大于收益 | **✅ 已修** | 换成 `Activity#onResume` + `Dialog#show` 的树遍历（`ModuleMain.java:152-203`），成本有界，且与构造 hook 的失效条件不重叠。这是本轮最干净的一处改动。 |
| **M2** | 抖音每个子进程都被注入并挂钩 | **🟡 部分** | `isWantedProcess()` + `detach()` 已加，`detach` 的 `getApiVersion() >= 102` 守卫也是对的（`detach()` 确实带 `@SinceApi(API_102)`，见 `XposedInterfaceWrapper.java:80`）。**但**白名单是猜的，且失败模式是"静默丢功能"而不是"多点开销"→ N2。 |
| **M3** | `autoHotReload=true` 但没实现热重载契约 | **✅ 已修** | `module.prop` 去掉了 `autoHotReload`；且 `onHotReloading()` 的**默认实现就是 `return false`**（`XposedModuleInterface.java:264-267`），service 触发的手动热重载也会被拒绝，`onHotReloaded()` 里"unhook 全部旧 hook"的默认行为不会被执行。旧 watcher 无法回收这一问题因此不再有触发路径。 |
| **M4** | 900×1974 动画全在主线程解码，峰值 20MB+ | **🟡 部分** | 后台线程解码 ✅（`DECODER`，`MjOverlay.java:42-46, 107`）。但 `MAX_TARGET_WIDTH = 900` = 素材原始宽度，1080p 屏幕上 `size.getWidth() > targetWidth` 不成立 → **实际上完全没有降采样**；素材仍 900px/30fps/`-loop 0`；仍无解码缓存与取消 → N4。 |
| **M5** | `repeatCount` 与 `-loop 0` + 3300ms 魔数 | **❌ 未动** | `build_assets.ps1:33` 仍是 `-loop 0`；`PLAYBACK_MS` 从 3300 改成 3150 仍是魔数；且定时器现在**从 `show()` 时刻开始计时**，而动画要等异步解码完成后才开始 → 实际播放时间 = 3150ms − 解码耗时，反而更容易被截断。 |
| **M6** | MediaPlayer 主线程 `prepare()`、无完成回调、音效通道错、`openFd` 隐式依赖 | **🟡 大部分修好** | 通道改成 `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC` ✅；`prepareAsync()` ✅；`openFd` 依赖随 ZipFile 方案一起消失 ✅（`.m4a` 是否被压缩都不再相关，A2 的 `noCompress` 也就不需要了）。仍缺 `OnCompletionListener`，且 `materialize`（zip 读 + 写文件）留在了主线程。 |
| **M7** | `WeakHashMap` 的 value 强引用 key；`show()` 中途失败留半成品 | **✅ 已修（大体）** | 静态 `ACTIVE` Map 整个删掉，改用 `host.setTag(session)` + `findExisting(root)` 反查，引用环消失；`stopped` 守卫覆盖了"解码回来时已经过时"的情况。**但**仍没有监听窗口/控件销毁（N8）。 |
| **M8** | 只 hook `EditText`、缺取证 | **🟡 部分** | 兜底升级成树遍历，对"原生 EditText 输入框"的覆盖率已经很好；但 `tools/dexstrings.py` 的取证仍未做，README 里"任何版本、任何页面都能命中"的断言也没改（A10）。Lynx/Compose 自绘输入框仍不覆盖。 |
| **M9** | 冷却 600ms « 动画 3300ms | **🟡 部分** | 600 → 1500，好很多；但仍短于一次完整播放（3150），且没有 `isShown()/hasFocus()` 过滤，程序化 `setText("mj")` 仍会触发。另有更严重的误触发来源见 N6。 |
| **A1** | `module.prop` 收尾 | **🟡 部分** | 去掉了 `autoHotReload`（正确），但仍 `minApiVersion=101`（实际用了 102 才有的 `detach()`）、仍无 `staticScope=true`、仍无 `exceptionMode=protective`。 |
| **A2** | 素材体积与 `noCompress` | **✅ 不再是问题（体积仍在）** | 改成 ZipFile 读流后 `noCompress` 不再是硬依赖；但 4.6MB 素材和 1080p 不降采样仍是同一个问题（N4）。 |
| **A3** | TextWatcher 每键开销 | **🟡 部分** | `MjSettings` 的 4 个读方法现在都是纯内存读 ✅（`MjSettings.java:28-32, 70-84`，方向完全正确）。`MjKeywords.matchesExtra` 仍每次 `split` 编译正则，`afterTextChanged` 仍每键 `s.toString()`（短文本影响很小，仍建议加长度短路）。 |
| **A4** | 每个 EditText 打一条 INFO 日志 | **✅ 已修** | `LOGGED_CLASSES` 按类名去重（`ModuleMain.java:228-231`）。 |
| **A5** | 4 个构造函数全 hook 是多余的 | **❌ 未动** | `ModuleMain.java:126` 仍遍历 `getDeclaredConstructors()`；1/2/3 参最终 `this(...)` 到 4 参，每次 `new XxxEditText()` 仍走 4 次 hook 桥。只留 4 参那一个即可。 |
| **A6** | DecorView `addView` 的可行性与小坑 | **🟡 部分** | 触摸穿透依旧正确；`setClipChildren(false)`（`:85`）与 `setElevation(1000f)`（`:86`）仍是无效调用；`setImportantForAccessibility(NO)` 仍未加；`android.R.id.content` 与 `WindowManager` 两条更稳的路径仍未采用，`MjEngine.java:41` 仍是裸 `getRootView()` → N5。 |
| **A7** | 静默吞异常 | **🟡 部分** | `ModuleMain` 侧已经全部带上了异常对象（`:76, 109, 136, 142, 164, 169, 183, 188, 223`），很好；`MjEngine.java:44` 仍是 `catch (Throwable ignored) {}`。 |
| **A9** | `MjSettings` 默认值策略 | **🟡 部分** | 宿主里 `getRemotePreferences` 失败会记 `WARN`（`ModuleMain.java:72-78`），够了；建议升级成 `ERROR` 并带上"请检查 LSPosed 是否启用本模块"。 |
| **A8 / A10 / A11** | 构建卫生 / README 不符 / 风控提示 | **❌ 未动** | 仍 release 用 debug 签名、`isMinifyEnabled=false` 让 proguard 规则全是死配置、lint 仍 `abortOnError=false`；README 全文未更新（见 N10）。 |

---

## 2. 本轮新发现的问题

### N1【重要】Remote Preferences 的变更监听是"弱引用"注册——GC 之后设置静默失效，等于 S2 复发

**位置**：`MjSettings.java:38-48`

```java
public static void bind(SharedPreferences p) {
    prefs = p;
    if (p != null) {
        try {
            p.registerOnSharedPreferenceChangeListener((sp, key) -> reload());   // ← 42 行
        } catch (Throwable t) { … }
    }
    reload();
}
```

**原因**：`SettingsActivity` 在未连接服务时用的是 `Context#getSharedPreferences()`，它的实现
`android.app.SharedPreferencesImpl` 把监听器存在 **`WeakHashMap<OnSharedPreferenceChangeListener, Object>`** 里
（`mListeners`）。这里传的是一个**没有任何强引用的 lambda 实例**，注册完就只剩弱引用 →
下一次 GC 后监听器被回收，`reload()` 再也不会被调用，`MjSettings` 的缓存就永久停在旧值。

后果：

* 模块 App 内：改了"启用彩蛋"开关，`MjSettings.enabled()` 仍返回旧值 → 下面那个"试一试"输入框
  行为与开关不一致（看起来像开关坏了）；
* 宿主进程里：`MjSettings.bind(getRemotePreferences(...))` 注册的是框架侧的实现。框架实现是否用弱引用
  我们**无法从本地 AAR 证实**，但一旦命中，症状就是"设置改了不生效，重启抖音才生效"——
  这正是上一轮 S2 的用户可见症状，只是从"永远不生效"变成"GC 之后不生效"。同一份代码在两处用了同一个模式，
  没有理由留这个赌。

**修复**：监听器提成静态常量强引用，并顺手补上"同一个 prefs 不重复注册、换了 prefs 先反注册"：

```java
private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER =
        (sp, key) -> reload();

public static synchronized void bind(SharedPreferences p) {
    SharedPreferences old = prefs;
    if (old == p) return;                       // 重复 bind 直接返回（SettingsActivity.build() 会反复调用）
    if (old != null) {
        try { old.unregisterOnSharedPreferenceChangeListener(LISTENER); } catch (Throwable ignored) {}
    }
    prefs = p;
    if (p != null) {
        try { p.registerOnSharedPreferenceChangeListener(LISTENER); }
        catch (Throwable t) { Log.w(TAG, "register listener failed: " + t); }
    }
    reload();
}
```

顺带说明：`SettingsActivity.build()` 里有 `if (prefs != boundPrefs) { boundPrefs = prefs; MjSettings.bind(prefs); }`
（`:79-82`），而 `MjService.remotePrefs()` 每次都新建一个实例，所以这个"防重复"判断挡不住重复注册，
必须在 `bind()` 内部做。

另外，宿主侧只读这一点代码是对的——`XposedInterface#getRemotePreferences` 的文档明确写了
"**those are read-only in hooked apps**"（`XposedInterface.java:536`），宿主里只 `getXxx()` 不做 `edit()`，正确。

---

### N2【重要】进程名白名单靠猜：命中失败时是"私信里没反应"，而不是"多花点资源"

**位置**：`ModuleMain.java:94-101`

```java
private boolean isWantedProcess() {
    String name = processName;
    if (name == null) return true;
    int idx = name.indexOf(':');
    if (idx < 0) return true;
    String suffix = name.substring(idx + 1).toLowerCase(Locale.ROOT);
    return suffix.contains("im") || suffix.contains("main");
}
```

**原因**：上一轮 M2 的问题是"所有子进程都装 hook"，代价是多点 CPU；这次的修法把失败模式**反过来了**：
只要抖音把私信/群聊放在一个名字里既没有 `im` 也没有 `main` 的子进程（例如 `:ugc`、`:session`、
`:social`、`:aws`… 名字随版本变化），模块就会在那里 `detach()`，**用户在私信里输入 mj 完全没反应，
而且日志里只有一行 INFO "skip …"，极易被忽略**。仓库里没有任何证据说明抖音私信运行在哪个进程。

**修复建议**（按稳妥程度排序）：

1. **先取证再收紧**：`ModuleMain.onModuleLoaded` 已经打印了 `loaded in <processName>`，
   在 LSPosed 日志里把抖音全量跑一遍（评论、私信、群聊、直播、小程序），把所有进程名列出来；
   `adb shell ps -A | grep aweme` 也能一次看全。
2. **把逻辑反过来写**（黑名单而不是白名单），这样"猜错"的代价变成"多挂一个进程"，而不是"功能消失"：

```java
private static final String[] SKIP_PROCESS_KEYS = {
        "push", "pushservice", "sandbox", "miniapp", "device", "abtest", "isolated", "webview"
};

private boolean isWantedProcess() {
    String name = processName;
    if (name == null) return true;
    int idx = name.indexOf(':');
    if (idx < 0) return true;                       // 主进程
    String suffix = name.substring(idx + 1).toLowerCase(Locale.ROOT);
    for (String key : SKIP_PROCESS_KEYS) {
        if (suffix.contains(key)) return false;     // 明确没用的进程才跳过
    }
    return true;                                    // 其余一律保留（含私信等未知进程）
}
```

3. 把 `detach()` 的分支从 `log(INFO)` 提到 `log(DEBUG)` 之外，改成在**非目标包**时 INFO、在
   **目标包的子进程**被跳过时保持 INFO 但带上判定依据（`suffix`），方便用户贴日志。

---

### N3【重要】`MjAssets` 的兜底分支会把"抖音自己的 APK"当成模块 APK，`init` 失败时 S1 整条复发

**位置**：`MjAssets.java:39-47`（`init` 静默失败）、`MjAssets.java:49-62`（`zip()` 兜底）

```java
public static void init(ApplicationInfo moduleInfo) {
    try {
        if (moduleInfo != null) moduleApkPath = moduleInfo.sourceDir;
    } catch (Throwable t) { Log.w(TAG, "init failed: " + t); }        // ← 失败只留一行 WARN
}

private static ZipFile zip(Context host) throws IOException {
    …
    String path = moduleApkPath;
    if (path == null && host != null) path = host.getApplicationInfo().sourceDir;  // ← 宿主进程里这是抖音的 APK
    …
    zip = new ZipFile(path);
}
```

**原因**：`host` 在宿主进程里是**抖音的 Context**（`MjEngine.java:39` 传的是 `editText.getContext()`）。
一旦 `getModuleApplicationInfo()` 抛异常/返回空（框架实现差异、非 LSPosed 的 zygisk 式模块、
`sourceDir` 指向不可读路径……），`moduleApkPath` 就是 null，兜底会 `new ZipFile(/data/app/…/抖音/base.apk)`，
然后 `getEntry("assets/mj/mj_drop.webp")` 返回 null → `IOException: asset not found` →
`MjOverlay.decode` 抛异常 → `MjOverlay.java:111` 只 `Log.w` 一行 → **动画和音效都没了，
和上一轮 S1 的表现一模一样，且自测（模块 App 内预览）依然正常**。这就是最危险的那种"回退到旧 bug"。

另外两点小问题：

* `new ZipFile(抖音的 365MB base.apk)` 本身就要解析中央目录（几十 ms），是把贵重资源用错地方；
* `init()` 里 `catch` 住了异常但没记 `ERROR`、没记下 `sourceDir`，事后无从判断走的是哪条路。

**修复建议**：宿主进程里**只认** `getModuleApplicationInfo()`，不要拿宿主 APK 兜底；把"哪条路径"打进日志。

```java
private static volatile String moduleApkPath;   // 宿主进程：来自框架
private static volatile boolean fromFramework;

public static void init(ApplicationInfo info) {
    if (info == null || info.sourceDir == null) {
        Log.e(TAG, "getModuleApplicationInfo() gave no apk path; module assets unavailable");
        return;
    }
    moduleApkPath = info.sourceDir;
    fromFramework = true;
    Log.i(TAG, "module apk = " + info.sourceDir);
}

private static String path(Context ctx) throws IOException {
    String p = moduleApkPath;
    if (p != null) return p;
    if (fromFramework) throw new IOException("module apk path unknown");   // 宿主：绝不回退
    // 只有模块 App 自己会走到这里（ModuleMain 不会在模块 App 进程里跑）
    return ctx.getApplicationInfo().sourceDir;
}
```

再补一条：`MjOverlay.java:111` 的 `Log.w("decode … failed")` 建议改成带 `t` 的 `ERROR`（A7 的残留），
否则 S1 类问题在真机上依旧只能靠猜。

---

### N4【重要】内存/卡顿只解决了一半：900px 的"上限"恰好等于素材原始宽度

**位置**：`MjOverlay.java:35-36, 141-155`；素材 `app/src/main/assets/mj/*.webp`（1.97MB / 2.67MB）

```java
private static final int MAX_TARGET_WIDTH = 900;
private static final int MIN_TARGET_WIDTH = 540;
…
int screenWidth = context.getResources().getDisplayMetrics().widthPixels;   // 1080
return Math.max(540, Math.min(900, 1080));                                  // = 900
…
if (targetWidth > 0 && size.getWidth() > targetWidth) decoder.setTargetSize(…);  // 900 > 900 不成立
```

**原因**：素材本身是 900 宽（`build_assets.ps1:32` 的 `scale=900`），而 `MAX_TARGET_WIDTH` 也是 900。
绝大多数手机屏幕宽度 ≥ 900px，于是 `min(900, screenWidth) == 900 == size.getWidth()`，
`setTargetSize` **一次都不会被调用**——这一轮的降采样在主力机型上等于没做。单帧内存仍是
900×1974×4 ≈ **7.1MB**，叠加 `AnimatedImageDrawable` 的多帧缓冲、`MjAssets.read()` 的 `byte[2.67MB]`
和 `ByteBuffer.allocateDirect(2.67MB)`（`:142-146`），单次触发在抖音进程里多出 **10–15MB**，
与上一轮 M4 的结论基本一致。

而且新方案还有两处放大：

* **没有复用**：每次触发都重新从 zip 解压 2.67MB + 重新 `decodeDrawable`（A/M4 的第 4 条建议未采纳）；
  direct buffer 只能等 GC 回收，连打几次会有多个 2.67MB 同时悬着（native 内存压力尤其明显）；
* **没有取消**：`DECODER` 是单线程无界队列（`:42-46`），每次触发都排队一次完整解码，
  即使对应 `session` 早就 `stopped` 了——解码前应先判一次（`if (session.stopped) return;`）。

**修复建议**（三件事，按收益排序）：

1. 素材层面：`scale=900` → `scale=720`（或 640）+ `-r 24`，`-loop 0` → `-loop 1`（配合 `setRepeatCount(0)`，
   避免 M5 那个"文件元数据说无限循环"的隐患）。两个 webp 合计能从 4.6MB 压到 1MB 级，解码时间和内存同比降 40%+；
2. 运行期：`MAX_TARGET_WIDTH` 改 720，或干脆按控件实测宽度（`onSizeChanged` 后再解码，首帧晚一点但更省）；
3. 缓存 + 取消：

```java
private static final Map<String, ImageDecoder.Source> SOURCES = new ConcurrentHashMap<>();

private static ImageDecoder.Source source(Context ctx, String asset) throws IOException {
    ImageDecoder.Source cached = SOURCES.get(asset);
    if (cached != null) return cached;
    byte[] data = MjAssets.read(ctx, "mj/" + asset + ".webp");
    ByteBuffer buffer = ByteBuffer.allocateDirect(data.length);
    buffer.put(data);
    buffer.flip();
    ImageDecoder.Source created = ImageDecoder.createSource(buffer);
    SOURCES.put(asset, created);
    return created;
}
```
（`Source` 要长期持有，就必须同时持有那个 `ByteBuffer`，否则 native 侧拿到的地址会随 GC 失效——
所以缓存的是 `Source`，不是 `byte[]`。）

---

### N5【重要】叠加层在输入法窗口之下：打字时被键盘挡住；Dialog 内触发时被小窗口裁切

**位置**：`MjEngine.java:41`（`View root = editText.getRootView()`）、`MjOverlay.java:100`（`root.addView(host)`）

**原因**：`getRootView()` 返回的是**输入框所在那个窗口**的 DecorView。而有两种常见情况都不理想：

1. **输入法窗口永远在 Application 窗口之上**。彩蛋是在"用户正在打字"时触发的，此时键盘必然展开，
   叠加层被挡住下半部分。**`tools/emu-trigger1.png` / `emu-trigger2.png` / `emu-zip1.png` 三张自测截图
   里都能直接看到这只蜘蛛侠被键盘拦腰截断**——这不是模拟器特有的，是所有真机的必然结果
   （原版彩蛋是"发送成功后播一次"，那时键盘已经收起）。
2. **抖音的评论/私信输入面板是 Dialog / 底部弹层**，`getRootView()` 得到的是那个**小窗口**的 decor，
   MATCH_PARENT 的叠加层就被裁在弹层范围内（A6 的结论仍然成立，本轮没有处理）。

**修复建议**（1 与 2 一起解决）：

```java
// MjEngine：优先解析 Activity 的 DecorView；Dialog 场景退化为 Dialog 的 decor
private static ViewGroup resolveRoot(EditText et) {
    Context c = et.getContext();
    while (c instanceof ContextWrapper) {
        if (c instanceof Activity) {
            Window w = ((Activity) c).getWindow();
            if (w != null) return (ViewGroup) w.getDecorView();
            break;
        }
        Context base = ((ContextWrapper) c).getBaseContext();
        if (base == null || base == c) break;
        c = base;
    }
    View r = et.getRootView();
    return r instanceof ViewGroup ? (ViewGroup) r : null;
}
```

键盘遮挡的正解是两条（建议都做）：

* **把触发时机改成"发送/清空"**：`TextWatcher` 里记 pending，等 `s.length() == 0` 再播（键盘通常已收起），
  顺带把 M9 的"连打重启动画"一起解决，也更贴原版；
* 仍要在打字过程中显示的话，用 `WindowInsets`/`OnApplyWindowInsetsListener` 把叠加层高度限制在
  键盘之上（`root.setOnApplyWindowInsetsListener`），或把叠加层加到
  `android.R.id.content` 并给底部留出 IME 高度的 padding。

---

### N6【重要】拼音输入法拼写 "mj" 也会触发（composing text 未过滤）

**位置**：`ModuleMain.java:219-226`

```java
@Override public void afterTextChanged(Editable s) {
    try { MjEngine.onTextChanged(editText, s == null ? "" : s.toString()); }
    …
}
```

**原因**：`MjKeywords.isMjChain` 判定"整段都是 mj 配对"，而**中文/日文输入法在拼写过程中会把 composing 串
直接写进 `EditText`**（带下划线的那一段）。用户想打"米家/美金/梦洁"之类，拼音串正好是 `mj` 的瞬间，
`afterTextChanged` 收到的就是 `"mj"` —— 于是彩蛋被触发，而用户根本没打算发这条消息。
这在中文环境的抖音里是**高频**场景（原版彩蛋判定的是"发送消息"这一动作，不存在这个问题）。

**修复建议**：只要还有 composing 区段就不判定（一行代码，`BaseInputConnection.getComposingSpanStart` 是公开静态方法）：

```java
@Override public void afterTextChanged(Editable s) {
    if (s == null) return;
    if (s instanceof Spannable
            && android.view.inputmethod.BaseInputConnection.getComposingSpanStart((Spannable) s) >= 0) {
        return;     // 输入法还在拼写（拼音/注音），不是用户想要的内容
    }
    if (s.length() < 2) return;                     // A3 的长度短路
    if (!MjSettings.enabled()) return;
    MjEngine.onTextChanged(editText, s.toString());
}
```

---

### N7【建议】未连接服务时设置页仍可写：写进本地 prefs，看起来像生效

**位置**：`SettingsActivity.java:106-166`（四个控件直接 `prefs.edit()…apply()`）、`:51-62, 68-72`

状态栏已经提示"⚠️ 未连接 LSPosed 服务：设置仅在本页预览生效"，比上一轮"完全骗人"好得多，
但**控件依然是可编辑的**，用户点开开关、输入关键词，写入的是模块 App 的私有 prefs（`localPrefs()`），
宿主永远读不到。建议照上一轮的建议走到底：

```java
private void applyEnabled(boolean connected) {
    // build() 里把每个控件的 setEnabled(connected) 一起设置
}
```
即未连接时禁用全部输入控件（保留预览按钮），状态栏文案同步改成"未能连接到 LSPosed，
请先在 LSPosed 中启用本模块并重开本页"。另外 `prefs.edit()…apply()` 的四处写入建议包一层
`try/catch(Throwable)`：服务在后台断开时，`RemotePreferences` 的 editor 可能抛异常，
而这里是在 UI 线程的点击回调里，未捕获就是 Activity 崩溃。

---

### N8【建议】Session 生命周期仍缺两层保护：窗口销毁 & 播放完成

**位置**：`MjOverlay.java:185-238`

1. **没有 `addOnAttachStateChangeListener`**：弹层/页面在 3.15s 内被关闭时，`host` 随窗口一起消失，
   但 `host.postDelayed` 排的 Runnable 仍然会在原定时刻执行，**音效会继续响到定时器触发**
   （M7 那条"宿主若提前把整个窗口拆掉，音效会在页面切换后继续响"仍未解决）：
   ```java
   host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
       @Override public void onViewDetachedFromWindow(View v) { session.stop(); }
       @Override public void onViewAttachedToWindow(View v) { }
   });
   ```
2. **没有 `OnCompletionListener`**（M6 残留）：素材比 3150ms 短时播放器空转，长时被硬切：
   ```java
   created.setOnCompletionListener(mp -> {
       try { mp.release(); } catch (Throwable ignored) {}
       if (player == mp) player = null;
   });
   ```
3. 附带一条设计层面的说明：叠加层没有"全局单实例"约束。若同时存在两个窗口（例如 Activity + 弹层）
   各触发一次，两段动画和两路音效会**叠在一起**。若在意，可用一个 `static WeakReference<Session> LAST`
   做"新播放先停旧的"（跨 root 生效）。

---

### N9【建议】素材 IO 与解码队列：主线程 `materialize`、无上限队列、无复用

**位置**：`MjOverlay.java:102-104, 107, 188`

* `Session.playSound()` 在主线程执行 `MjAssets.materialize()`——内部是 zip 解压 32–38KB
  再写进抖音的 cache 目录。绝对量很小（毫秒级），但这是**上一轮专门批评过的"主线程 IO"**，
  移走成本极低：把 `materialize` 也丢进 `DECODER`，`prepareAsync` 在解码线程启动即可（MediaPlayer 允许任意线程创建）。
* `DECODER` 是 `newSingleThreadExecutor` 且无队列上限：连打 `mj` 会堆一串完整解码任务，
  建议在解码前判 `session.stopped`，并用"同一 asset 只保留最新请求"的合并策略。
* `mj_egg/` 目录只在 `out.length() != data.length` 时重写，逻辑可用但建议加注释；
  另外建议在 `MjAssets.init` 时清一次旧文件，避免模块换素材后残留同名旧文件（长度恰好相同时不会被覆盖）。

---

### N10【建议】收尾项（大多是上一轮遗留）

* **`module.prop`**：建议改成
  ```properties
  minApiVersion=102
  targetApiVersion=102
  staticScope=true
  exceptionMode=protective
  ```
  `detach()` 是 API 102 才有的（`XposedInterfaceWrapper.java:80`）；虽然 `getApiVersion() >= 102`
  守卫能避免 `NoSuchMethodError`（ART 的方法解析是惰性的），但声明 101 而实际依赖 102 的语义会误导使用者。
  `staticScope=true` + `TARGET_PACKAGES` 双重过滤，避免用户把它勾到无关应用上白跑一遍。
* **README 未同步**（A10 全量残留）：仍写着"模块 App 内可以：开关彩蛋…"（现在要靠 LSPosed 服务才成立）、
  "更低版本只在日志里提示"（现在设置页也会有提示）；完全没有提到
  `app/libs/libxposed-{api,service}-102.jar` 是构建必需品、进程过滤策略、以及 N5/N6 这两条新的已知限制。
  建议补一节"已知限制"：键盘遮挡、软件渲染只出首帧、拼音拼写会触发、热重载不受支持。
* **A5**：只 hook `EditText` 的 4 参构造（`Context, AttributeSet, int, int`）。
* **A6 残留**：`host.setClipChildren(false)` / `host.setElevation(1000f)` 删掉或补 background；
  加 `host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)`。
* **A8**：release 仍用 debug 签名（至少在 README 顶部写清）；`isMinifyEnabled=false` 让
  `proguard-rules.pro` 的 `-adaptresourcefilecontents`/`-keep` 全是死配置，将来开混淆时
  `java_init.list` 必须被正确改写；lint 仍全关。
* **A11**：hook 面比上一轮小了很多（少了 `View#onAttachedToWindow`），这对降低特征面是好事，
  但 README 仍无"可能被客户端风控识别"的提示。

---

## 3. 验证证据缺口（这一条建议放在修 N1–N6 之前）

`tools/` 下的自测截图我看过了：

* `emu-trigger1.png` / `emu-trigger2.png` / `emu-zip1.png` —— **全部是模块 App 自己的设置页**，
  触发的是"试一试"输入框；
* `emu-zip1.png` 的状态栏明确显示 **"⚠️ 未连接 LSPosed 服务"**。

也就是说：

| 路径 | 有无证据 |
|:-|:-|
| `MjAssets.read()` 用 ZipFile 读模块 APK | ✅ 有（`emu-zip1.png`，但走的是"模块 App 自己 APK"的兜底分支） |
| 宿主进程里 `getModuleApplicationInfo().sourceDir` + ZipFile | ❌ 无 |
| `XposedServiceHelper` 绑定 + `getRemotePreferences` 双向读写 | ❌ 无（截图里根本没连上服务） |
| 抖音里实际注入、`EditText` 构造 hook、`Activity#onResume` 树扫描 | ❌ 无 |
| `USAGE_MEDIA` 音效真的出声、`AnimatedImageDrawable` 在抖音窗口里真的动 | ❌ 无 |

这正是上一轮 S1 能够"自测通过、真机全废"的同一个盲区：**模块 App 内的预览走的是
`path = ctx.getApplicationInfo().sourceDir` 这一支，而宿主走的是 `init()` 那一支**，
两条代码路径完全不同。建议：

1. 在 `MjAssets` 里把"素材来自哪条路径"打成一条 INFO（见 N3 的补丁），真机跑一次就能确认；
2. 在 `ModuleMain.onPackageReady` 打印 `getRemotePreferences(...).getAll()` 的键集合（值不必打），
   一眼就能看出宿主读到的配置是否为空；
3. 改完设置后**不重启抖音**，看彩蛋是否立刻按新设置生效——这是 N1 的唯一有效验证方式。

---

## 4. 值得保留的改动（本轮做得对的地方）

* `MjAssets` 用 `ZipFile` + `getModuleApplicationInfo()` 取模块 APK：**方向完全正确**，
  只用公开 API、不依赖包可见性、也不碰 hidden API，比上一轮建议的 `createPackageContext`/`addAssetPath`
  两条路都干净；
* 解码改成 `ImageDecoder.createSource(ByteBuffer)` 且明确用 `allocateDirect`——这点很关键，
  native 侧要的是直接缓冲区，用 `allocate()` 会失败；
* `MjService`/`MjApp`/`SettingsActivity` 的"服务可用 → 用 remote prefs；不可用 → 降级本地 + 明确提示"
  这个三段式结构，比"假装能写"好得多；
* `execute` 把图片解码搬到后台 + 主线程只 `setImageDrawable`/`start`（M4 的核心诉求）；
* 删掉静态 `ACTIVE` Map 改用 View tag（M7 的引用环、`WeakHashMap` 误用一起解决）；
* `Activity#onResume` + `Dialog#show` 树遍历取代 `View#onAttachedToWindow`（M1）；
* `MjSettings` 的"缓存 + 变化监听"结构（A3 的核心诉求，只剩 N1 那个弱引用坑）；
* `module.prop` 去掉 `autoHotReload` 并依赖默认的 `onHotReloading() == false`（M3）；
* `LOGGED_CLASSES` 去重、`ModuleMain` 里所有 catch 都带上了异常对象（A4/A7）。

---

## 5. 修复顺序与验收清单

**建议顺序**：N6（一行代码，用户体验最直接）→ N5（触发时机改成"发送/清空" + root 解析）→
N1（弱引用监听器）→ N3（宿主不回退）→ N2（黑名单 + 取证）→ N4（素材 720/24fps + 缓存 + 取消）→
N8/N9/N10 → 最后再动 M5/A5/A6 这些细节。

**验收清单**（在上一轮清单基础上更新，重点补上"证据缺口"）：

- [ ] 真机（抖音最新版 + 一台 API 28、一台 API 34）在**评论区**输入 `mj`：动画 + 音效都出现
      （这是 S1 的唯一有效验收，模块 App 内的预览不算）；
- [ ] 同上，**私信 / 群聊 / 回复弹窗**各测一次（验证 N2 的进程过滤没有把 IM 进程过滤掉，
      同时在 LSPosed 日志里确认各页面所在的 `processName`）；
- [ ] 日志里能看到 `module apk = /data/app/…/com.mjcomeback.easteregg-…/base.apk`（验证 N3 走的是框架路径）；
- [ ] 打开 LSPosed 服务后，设置页状态变成"✅ 已连接"，改任何一个开关**不重启抖音**就生效，
      并且**等待 1 分钟 / 触发一次 GC 后再改一次仍然生效**（验证 N1）；
- [ ] 中文输入法下用拼音打 `mj`（选"米家"之类）确认**不触发**彩蛋（验证 N6）；
- [ ] 打字时确认动画不被键盘挡住（或改成发送后才播）（验证 N5）；
- [ ] 连续输入 4 次 `mj`，确认动画不会"闪一下重来"（M9），也不会出现两段音效叠加（N8-3）；
- [ ] API 26–28 设备打开模块 App 不崩（S3 回归验证）；
- [ ] 弹层在 1 秒内被关掉时，音效随之立即停止（验证 N8-1）；
- [ ] `adb shell dumpsys meminfo <抖音包名>` 观察触发前后 PSS 增量（N4，目标 < 8MB）；
- [ ] 录屏/软件渲染场景下确认动画可见（M5-2 仍未验证）。

---

## 6. 总体结论

**结论：机制层面已经"能工作"，但仍不满足发版条件——差的不是架构，而是 4 处会静默失效的细节和 2 处用户必现的体验问题。**

1. **上一轮 3 个【严重】问题的根因都改对了**：素材改从模块 APK 用 ZipFile 读、配置改走
   `XposedService#getRemotePreferences`、主题降到 API 14 就有的 `Theme.DeviceDefault.Light`。
   这是本次重写最重要的成果，应当保留。
2. **但 S1/S2 都有了"复发路径"**：N1（弱引用监听器，GC 后设置失效）与 N3（`init` 失败时回退去读抖音的 APK）
   都会把用户带回"看起来能用、其实没生效"的状态，而且**依然只有一行 WARN 日志**。
   这两条必须在发版前修掉——它们正是上一轮 S1/S2 让人误判的同一个原因。
3. **N2 把"多一点开销"换成了"静默丢功能"**：M2 的优化方向对，但白名单是猜的，
   必须先取证（日志里的 `loaded in …`）或改成黑名单，否则私信里没反应会被当成"模块坏了"。
4. **N5/N6 是这轮新暴露的、用户 100% 会遇到的问题**：键盘挡住彩蛋（截图里已可见）、
   拼音拼写 `mj` 误触发。这两条不改，"回归彩蛋"的观感会明显不如原版。
5. **N4 表明 M4 的优化在主力机型上等于没做**（900 上限 = 素材原始宽度），
   素材本身也没有降采样，属"改了但没生效"的典型，建议连素材一起重出一版。
6. **验证方式必须先补**：现有全部证据都在模块 App 进程里，而宿主路径、服务绑定、真实抖音注入
   一条都没有证据。把 N3 里那条 `Log.i(TAG, "module apk = …")` 加上，是最低成本的自证手段。

按 N6 → N5 → N1 → N3 → N2 → N4 的顺序修完，并跑通第 5 节清单里的前 5 条，
这个模块就可以进入"可以发给朋友试用"的状态了。
