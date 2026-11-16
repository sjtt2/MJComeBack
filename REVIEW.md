# MJ 彩蛋回归 · LSPosed 模块代码审查报告

审查对象：`app/src/main/java/com/mjcomeback/easteregg/*`、`AndroidManifest.xml`、`build.gradle.kts`、
`META-INF/xposed/{module.prop,java_init.list,scope.list}`、libxposed API 102 源码（`tools/libxposed/src`）、
`tools/wiki-modern-api.md`、`README.md`、`tools/build_assets.ps1`。

审查方式：纯静态审查（未修改任何源码）。`build-log.txt` 显示 `:app:assembleRelease` 构建成功——
但**构建成功 ≠ 运行正确**，下面 3 条【严重】问题全部属于"编译期完全通过、运行期必炸/必失效"的类型。

---

## 0. "编译能过但运行期会炸"清单（速览）

| # | 现象 | 位置 | 触发条件 |
|:-|:-|:-|:-|
| S1 | 动画/音效 **永远** 不出现，只有一条 `WARN` 日志 | `MjOverlay.java:108-123` | 任何情况下（宿主进程） |
| S2 | 设置页 4 个开关/选项对宿主 **完全无效** | `MjSettings.java:29-31`、`SettingsActivity.java:30` | 任何情况下 |
| S3 | 模块 App 启动即 `Resources$NotFoundException` 崩溃 | `AndroidManifest.xml:10` | API 26 / 27 / 28 |
| M3 | 热重载后模块彻底失效 + 旧 TextWatcher 泄漏 | `module.prop:3`、`ModuleMain.java:88-99` | 模块 App 升级 / 触发 hot reload |
| M2 | 抖音每个子进程都被注入并挂 hook | `ModuleMain.java:50-64` | 任何情况下 |

---

## 一、【严重】

### S1 宿主进程里 `context.getAssets()` 拿到的是抖音的 AssetManager，模块素材永远找不到

**位置**：`MjOverlay.java:108-112`（`decode`）、`MjOverlay.java:118`（`openFd`）、`MjOverlay.java:93`

```java
private static Drawable decode(Context context, String asset) throws Exception {
    ImageDecoder.Source source =
            ImageDecoder.createSource(context.getAssets(), "mj/" + asset + ".webp"); // ← context 是宿主 Context
```

**原因**：`MjEngine.java:38` 传入的 `context` 是 `editText.getContext()`，即**抖音 App / 抖音 Dialog 的 Context**。
它的 `AssetManager` 只挂载了抖音自己的 APK。模块 APK 里的 `assets/mj/*.webp`、`assets/mj/*.m4a`
在宿主进程中**根本不存在**——LSPosed 不会把模块的 assets 合并进宿主的 AssetManager。

后果：

* `ImageDecoder.createSource(...)` 抛 `FileNotFoundException` → 被 `MjOverlay.java:89-91` 吞掉，只留一行 `WARN`；
* `session.drawable == null` → `MjOverlay.java:95` 的 `instanceof AnimatedImageDrawable` 不成立 → **动画永不播放**；
* `playSound()` 的 `openFd` 同样失败 → **音效永不播放**；
* 但 `SettingsActivity.preview()`（`SettingsActivity.java:171-174`）里 `context` 是模块 App 自己，
  `getAssets()` 是模块 APK → **预览功能一切正常**。这就是为什么开发阶段"看起来能跑"。

这是本模块的致命问题：**功能在目标 App 里 100% 失效，而自测（预览）100% 通过。**

**修复建议**：素材必须从"模块自己的 APK"读取。两条路径，推荐第 1 条（无 hidden API 风险）：

```java
// ModuleMain.java —— 在 onPackageReady 里取一次模块 APK 路径，传给 MjOverlay
private static volatile String moduleApkPath;
...
moduleApkPath = getModuleApplicationInfo().sourceDir;   // 框架 API，安全
MjOverlay.setModuleAssets(new String[]{moduleApkPath});
```

```java
// MjOverlay.java
private static volatile AssetManager moduleAssets;
private static volatile String[] modulePaths;

static void setModuleAssets(String[] apkPaths) { modulePaths = apkPaths; }

/** 宿主进程里拿到"模块 APK"的 AssetManager。 */
private static AssetManager moduleAssets(Context host) {
    AssetManager am = moduleAssets;
    if (am != null) return am;
    // 1) 首选：借用模块自身包的 Context，普通 API，无 hidden API 风险
    try {
        Context c = host.createPackageContext("com.mjcomeback.easteregg", 0);
        am = c.getAssets();
        moduleAssets = am;
        return am;
    } catch (Throwable ignored) { }
    // 2) 兜底：反射 AssetManager#addAssetPath(模块 APK 路径)
    //    注意：addAssetPath 是 @hide，部分 ROM 的 hidden-API 策略会拦，需 try/catch 兜底
    try {
        am = AssetManager.class.getDeclaredConstructor().newInstance();
        AssetManager.class.getMethod("addAssetPath", String.class).invoke(am, modulePaths[0]);
        moduleAssets = am;
        return am;
    } catch (Throwable t) {
        Log.w(TAG, "module assets unavailable: " + t);
        return null;
    }
}

private static Drawable decode(AssetManager am, String asset, int targetWidth) throws Exception {
    if (am == null) return null;
    ImageDecoder.Source source = ImageDecoder.createSource(am, "mj/" + asset + ".webp");
    return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
        Size s = info.getSize();                       // 见 M4：按控件宽度降采样，省内存
        if (targetWidth > 0 && targetWidth < s.getWidth()) {
            int h = Math.max(1, Math.round(s.getHeight() * (targetWidth / (float) s.getWidth())));
            decoder.setTargetSize(targetWidth, h);
        }
    });
}

private static MediaPlayer playSound(AssetManager am, String asset) {
    if (am == null) return null;
    AssetFileDescriptor afd = am.openFd("mj/" + asset + ".m4a");  // ← 用模块 am，而不是 context.getAssets()
    ...
}
```

> 说明：`createPackageContext(pkg, 0)` 对"已安装的模块 App"通常可用；若某些 ROM/用户卸载了模块 App 后仍注入
> （不可能，但有边界情况）会抛 `NameNotFoundException`，所以保留 `addAssetPath` 兜底并全程判空。
> 判空是必须的：宁可不动画，也不要在宿主里抛异常。

---

### S2 设置页写的是模块 App 的私有 SharedPreferences，宿主读到的是 LSPosed Remote Preferences——所有设置项都是摆设

**位置**：`SettingsActivity.java:30`、`63-65`、`71-72`、`93-96`、`109-123`；`MjSettings.java:5-31`；`ModuleMain.java:56`

```java
// SettingsActivity.java:30
prefs = getSharedPreferences(MjSettings.PREFS_NAME, MODE_PRIVATE);   // ← 模块 App 私有 prefs
...
// ModuleMain.java:56（宿主进程）
MjSettings.setRemotePrefs(getRemotePreferences(MjSettings.PREFS_NAME)); // ← 框架 DB
```

**原因**：`XposedInterface#getRemotePreferences(group)`（`XposedInterface.java:543`）返回的是
**LSPosed 数据库**里的那份配置（`tools/wiki-modern-api.md:25`："Remote Preferences | Modern | 存储位置 LSPosed database"）。
它与模块 App 的 `/data/data/com.mjcomeback.easteregg/shared_prefs/mj_config.xml` 是**两个完全不同的存储**。

而模块 App 要写 Remote Preferences，必须按官方指引（`wiki-modern-api.md:11`）通过
**`libxposed/service`（XposedService）**拿到可写实例，与框架通信；直接 `getSharedPreferences(...)`
写的是自己的私有目录，宿主永远读不到。

因此现状：`enabled()` 永远返回默认 `true`、`sound()` 永远 `true`、`mode()` 永远 `MODE_ALTERNATE`、
`extraKeywords()` 永远 `""`。设置页 4 个控件 + 1 个输入框**全部无效**，
而且 `MjSettings.java:5-8` 的注释（"模块 App 自己则读写本地同名 SharedPreferences"）把这个错误固化成了"设计"。
README 第 65 行"模块 App 内可以：开关彩蛋、开关音效、选择播放顺序、设置附加关键词"是不成立的。

**修复建议**：

```kotlin
// app/build.gradle.kts —— 模块 App 侧需要"真实"依赖（不是 compileOnly）
dependencies {
    compileOnly(files("libs/libxposed-api-102.jar"))       // 宿主进程用，框架提供
    implementation("io.github.libxposed:service:102.0.0")  // 模块 App 用，必须打进 APK
}
```

```java
// SettingsActivity.java
private SharedPreferences prefs;
private boolean built;

@Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(new FrameLayout(this));      // 占位，等服务绑定后再真正构建
    XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
        @Override public void onServiceBind(XposedService service) {
            // 这才是与宿主共享的那份配置（可写；宿主侧能收到 change listener）
            prefs = service.getRemotePreferences(MjSettings.PREFS_NAME);
            if (!built) { built = true; setContentView(buildContent()); }
        }
        @Override public void onServiceDied(XposedService service) {
            prefs = null;   // 框架断开时降级为只读/禁用 UI，不要让 buildContent() NPE
        }
    });
}
```

同时把 `MjSettings` 改成"缓存 + 变化监听"，避免每次按键都穿透读一次（见 A3）：

```java
static void bind(SharedPreferences remote) {
    remotePrefs = remote;
    remote.registerOnSharedPreferenceChangeListener((p, k) -> refreshCache());
    refreshCache();
}
```

> 注：`libxposed/service` 的类名/方法签名请以实际 aar 为准（`wiki-modern-api.md:11` 指向该项目）；
> 关键是**不要**再用 `Context#getSharedPreferences` 冒充 remote prefs。
> 如果暂时不想引入 service 依赖，退而求其次的做法是：把设置项**彻底从 UI 里去掉**，
> 不要提供一个骗人的设置页。现在的状态是最糟的：用户以为改了，其实没改。

---

### S3 `android:theme="@android:style/Theme.DeviceDefault.DayNight"` 需要 API 29，而 `minSdk = 26`

**位置**：`AndroidManifest.xml:10`（`android:theme="@android:style/Theme.DeviceDefault.DayNight"`）；`app/build.gradle.kts:12`（`minSdk = 26`）

**原因**：`Theme.DeviceDefault.DayNight` 是 **API 29 (Android 10)** 才加入的框架样式。
本机 SDK 的权威清单可以佐证：

```
D:\tools\android-sdk\platforms\android-34\data\api-versions.xml:2766
  <field name="Theme_DeviceDefault_DayNight" since="29"/>
```

编译期因为 `compileSdk = 34` 一切正常；但设备是 API 26/27/28 时，系统资源表里没有这个 style id，
`ActivityThread` 应用 `<application android:theme>` 时会在 `Resources.Theme#applyStyle` 处解析失败
（典型表现 `android.content.res.Resources$NotFoundException: Resource ID #0x...`，具体 id 由 aapt 分配，
不同构建可能不同），**模块 App 一打开就崩**——而这一档设备恰好又是你 `MjOverlay.isSupported()` 里
"动画不可用"、最需要显示提示的那一档。建议在 API 28 的机器上实测确认（见验收清单）。

同样是"编译过、运行炸"，而且 `lint { abortOnError = false }`（`build.gradle.kts:37-40`）把它盖住了。

**修复建议**（二选一）：

```xml
<!-- 方案 A（推荐，零成本）：降到 API 14 就有的主题 -->
android:theme="@android:style/Theme.DeviceDefault"
```
```kotlin
// 方案 B：把 minSdk 提到 29，并删掉 MjOverlay.isSupported() 这层兼容代码
minSdk = 29
```

另外建议把 lint 打开（至少 `NewApi` / `UnusedResources` 不 abort，但保留报告）：

```kotlin
lint { abortOnError = false; checkReleaseBuilds = true; warningsAsErrors = false }
```
最好在 CI 里跑一次 `lintVitalRelease`。

---

## 二、【重要】

### M1 全量 hook `View#onAttachedToWindow`：成本/风险远大于收益

**位置**：`ModuleMain.java:111-129`

```java
Method m = View.class.getDeclaredMethod("onAttachedToWindow");
hook(m).intercept(chain -> { Object result = chain.proceed(); ... if (self instanceof EditText) ... });
```

**原因**：

1. `View#onAttachedToWindow` 是**引导类里最热的生命周期方法之一**，进程内**每一个 View**（不是每个 EditText）
   上屏都会进一次 hook 桥（ART instrumentation trampoline），并且会阻止该方法被内联。
   抖音一次滑动会 attach/detach 大量 View，这会带来持续的可测量开销（低端机上体现为滑动掉帧），
   收益却只是"给 EditText 补挂 watcher"这一件小事。
2. `EditText` 的 4 个构造函数已经被 hook 了（`ModuleMain.java:84-106`），而 hook 是在
   `onPackageReady`（Application 创建之前）安装的，**构造 hook 已经覆盖了全进程所有 EditText**；
   这个"兜底"路径在正常情况下永远不会命中，是纯成本。
3. 冲突面大：`View#onAttachedToWindow` 是各类"键盘/防误触/悬浮窗/内存优化"模块的常见 hook 点。
   虽然 libxposed 的链式模型允许多个模块共存，但你在任何一个模块抛异常/PASSTHROUGH 时都会被牵连，
   而且你的 hook 排在谁的后面取决于 priority，不确定性高。

**修复建议**：换成"有界成本"的树遍历兜底——`Activity.onResume` + `Dialog.show` 各走一次 recursion，
零热路径开销，还能覆盖"hook 安装前就已创建的输入框"：

```java
private void installTreeScans() {
    hook(Activity.class.getDeclaredMethod("onResume")).intercept(chain -> {
        Object r = chain.proceed();
        try { if (chain.getThisObject() instanceof Activity)
                 scan(((Activity) chain.getThisObject()).getWindow().getDecorView()); }
        catch (Throwable ignored) {}
        return r;
    });
    hook(Dialog.class.getDeclaredMethod("show")).intercept(chain -> {
        Object r = chain.proceed();
        try { if (chain.getThisObject() instanceof Dialog) {
                 Dialog d = (Dialog) chain.getThisObject();
                 if (d.getWindow() != null) scan(d.getWindow().getDecorView());
             } } catch (Throwable ignored) {}
        return r;
    });
}

private void scan(View v) {
    if (v instanceof EditText) installWatcher((EditText) v);
    if (v instanceof ViewGroup) {
        ViewGroup g = (ViewGroup) v;
        for (int i = 0, n = g.getChildCount(); i < n; i++) scan(g.getChildAt(i));
    }
}
```

实在要保留 attach hook，至少把 `getThisObject()` 判断改成 `instanceof TextView` 并把整段
包在 `if (MjSettings.enabled())` 里（现在 enabled() 也读不到，见 S2）。

---

### M2 抖音的每个子进程都会被注入并挂钩（push / sandbox / miniapp …）

**位置**：`ModuleMain.java:50-64`（`onPackageReady` 只按包名过滤）、`scope.list:1-3`

**原因**：`scope.list` 是按**包名**授权的，框架会把这个包的**所有常规进程**都注入模块
（`package-info.java:49-55` 明确说了这一点，并建议对不需要的进程调用 `detach()`）。
抖音有 `:push`、`:pushservice`、`:sandbox`、`:miniapp` 等一堆进程，它们都会执行
`hookEditTextConstructors()` + `hookViewAttach()`，白付一份 hook 与内存成本，还可能干扰小程序沙箱。
`ModuleMain.java:50-64` 也没有在任何非目标场景下调用 `detach()`（`XposedInterfaceWrapper.java:82`）。

**修复建议**：

```java
private String processName;

@Override public void onModuleLoaded(ModuleLoadedParam param) {
    processName = param.getProcessName();          // 只有这里能拿到进程名
}

@Override public void onPackageReady(PackageReadyParam param) {
    if (!isTarget(param.getPackageName()) || !isWantedProcess()) {
        detach();                                   // 不再接收后续回调，省资源
        return;
    }
    ...
}

private boolean isWantedProcess() {
    // 主进程 + IM 相关进程；其余（push / sandbox / miniapp…）一律跳过
    return processName == null
            || processName.equals(processName.split(":")[0])
            || processName.contains("im");
}
```

---

### M3 `autoHotReload=true` 但没实现热重载契约：一次热重载 = 模块报废 + 旧 watcher 泄漏

**位置**：`module.prop:3`（`autoHotReload=true`）；`ModuleMain.java:88-99`（hook 无 id、`HookHandle` 被丢弃）；`ModuleMain.java:135`（`addTextChangedListener` 无对应 remove）

**原因**（API 102 已实现 hot reload，`XposedModuleInterface.java:236-298`）：

1. `onPackageReady` **不会**在热重载后重放（`XposedModuleInterface.java:174-179`：
   "Hot reload does not automatically replay this callback or package lifecycle callbacks"）；
2. 默认的 `onHotReloaded()` 会 **unhook 掉所有旧 hook**（`XposedModuleInterface.java:296-298`）；
3. 于是：抖音正在运行时升级模块 App（或框架侧触发 hot reload）→ 旧 hook 全被摘掉，新代码里
   `hooked` 是 `false`，但没人再调用 `installHooks()` → **输入 mj 再也没有任何反应，直到用户杀进程重开**；
4. 更糟：`addTextChangedListener` 挂上的 `TextWatcher` **无法被热重载移除**。它由旧 classloader 里的
   匿名类实现，并被宿主 `EditText.mListeners` 强引用 → 旧 generation 的 classloader / `ModuleMain` 实例
   永远不可回收（这也违反 `XposedModuleInterface.java:248-253` 对热重载的硬性要求：
   "clear references to module objects stored by system or app classes"）；若新 generation 再挂一次
   watcher，就会**一次输入触发两次动画**。
5. 另外 `hook(ctor)` 的返回 `HookHandle` 被丢弃、也没有 `setId(...)`，因此**没法用 `replaceHook` 做平滑迁移**
   （`XposedInterface.java:307-329` 正是为此设计的）。

**修复建议**（短期最省事）：先把 `autoHotReload` 删掉，并把拒绝显式化：

```properties
# module.prop
minApiVersion=101
targetApiVersion=102
staticScope=true
# 不写 autoHotReload，避免默认值语义变化把模块搞死
```
```java
@Override public boolean onHotReloading(HotReloadingParam param) {
    return false;   // 明确拒绝：本模块的 watcher 无法安全回收
}
```

（中长期）若要支持热重载，需要：hook 全部 `setId(...)` + 保存 `HookHandle`；
用 `Map<EditText, TextWatcher>` 记录 watcher，在 `onHotReloading` 里逐个 `removeTextChangedListener` 并清空静态集合：

```java
private static final Map<EditText, TextWatcher> WATCHERS = new WeakHashMap<>();

private void installWatcher(EditText et) {
    synchronized (WATCHERS) {
        if (WATCHERS.containsKey(et)) return;
        TextWatcher w = new Watcher(et);
        WATCHERS.put(et, w);
        et.addTextChangedListener(w);
    }
}

@Override public boolean onHotReloading(HotReloadingParam param) {
    synchronized (WATCHERS) {
        for (Map.Entry<EditText, TextWatcher> e : WATCHERS.entrySet()) {
            EditText et = e.getKey();
            if (et != null) et.removeTextChangedListener(e.getValue());
        }
        WATCHERS.clear();
    }
    return true;    // 只有做到这一步，返回 true 才是安全的
}
```

---

### M4 900×1974 动画全在主线程解码，峰值内存 ~20MB+，首帧必卡

**位置**：`MjOverlay.java:88`（`decode` 在主线程）、`MjOverlay.java:86-105`；素材 `assets/mj/mj_swing.webp` = 2.67MB、`mj_drop.webp` = 1.97MB

**原因**：

* `Session` 的 `decode` 由 `MjEngine` 的 `editText.post(...)` 在主线程执行，`ImageDecoder.decodeDrawable`
  要解析 2MB+ 的动画 WebP 头 + 解首帧（900×1974 RGBA = **约 7.1MB/帧**），
  低端机上单次 50–200ms 的掉帧是必现的；
* `AnimatedImageDrawable` 若未设 target size，会按原始 900×1974 解码，动画期间要维护多帧缓冲，
  峰值在 15–25MB 量级；抖音进程本身已经是内存大户（且字节有自己的内存监控/降级逻辑），
  在一个正在滑动的抖音进程里凭空多出 20MB 是实打实的 OOM/被降级风险；
* 素材是"3 秒 30fps 全分辨率"——对一个"贴屏 3 秒的特效"来说明显过大。

**修复建议**：

1. 按控件实际宽度降采样（代码见 S1 里的 `decoder.setTargetSize`），例如取
   `Math.min(screenWidthPx, 720)`；
2. 后台线程解码，主线程只做 `setImageDrawable` + `start`；
3. 素材层面直接降到 540–720px 宽、24fps（`build_assets.ps1:32-33` 里 `scale=900` → `scale=720`、加 `-r 24`），
   两个素材合计能从 4.6MB 压到 1MB 级，解码时间和内存同比降 40%+；
4. 对同一个 asset 做"按需预解码"：第一次触发后把 `ImageDecoder.Source` 或素材字节缓存起来，
   避免每次触发都重新解析容器。

---

### M5 `AnimatedImageDrawable` 的使用细节：repeatCount 与 3300ms 魔数不匹配、依赖硬件加速

**位置**：`MjOverlay.java:95-99`（`setRepeatCount(0)` / `start()`）、`MjOverlay.java:34`（`PLAYBACK_MS = 3300L`）；素材生成 `tools/build_assets.ps1:33`（`-loop 0`）

**原因**：

1. `setRepeatCount(n)` 的语义是"首播之后再重复 n 次"，`0` = 只播一次——这与代码意图一致；
   但**容器里写的是 `-loop 0`（无限循环）**，`AnimatedImageDrawable` 解码时的初始 repeatCount
   取自文件元数据。一旦某版本/某 ROM 上 `setRepeatCount(0)` 的边界处理与文档不一致（历史上这个 API
   的 off-by-one 有过争议），动画就会在 `3300ms` 被移除前**又从头播一次**，肉眼就是一次明显跳帧。
   而素材实际时长约 3.0s、定时器 3.3s，这 300ms 的空档正好放大这个问题。
2. `AnimatedImageDrawable` 的逐帧推进依赖 `Choreographer` + **窗口硬件加速**合成。
   抖音/app 的正常窗口是硬件加速的，没问题；但在软件渲染路径下（`View#setLayerType(LAYER_TYPE_SOFTWARE)`、
   某些 ROM 的窗口降级、录屏/投屏时强制软件合成）**只会画第一帧**——即"看不见动画，只剩一张静止图"。
   这条必须在真机上跑一遍验证，别只在预览里看得见就认为 OK。
3. `setRepeatCount(0)` 与 `stop()` 的配合是正确的（`Session.stop()` 里对 `Animatable` 调 `stop()`），这点没问题。

**修复建议**：

```java
// 用"素材实际时长"驱动移除，别用魔数；并对 repeat 语义只依赖一种解释
private static final long PLAYBACK_MS = 3000L;   // 用 ffprobe 实测两段素材的时长后填精确值

animated.setRepeatCount(0);      // 只播一次（配合精确时长，不会再出现重播跳帧）
animated.start();
host.postDelayed(session, PLAYBACK_MS + 100L);
```

并在 README 的"已知限制"里补一条：**软件渲染 / 录屏场景下只显示首帧**。

---

### M6 MediaPlayer：主线程 `prepare()`、无完成回调、音效通道选得不对

**位置**：`MjOverlay.java:114-142`

**原因**：

1. `player.prepare()`（124 行）是同步 IO，跑在主线程（`MjEngine` 的 `post` 里）→ 首帧 jank；
   应使用 `prepareAsync()` 或干脆换 `SoundPool`（3 秒以内的短音效，SoundPool 更合适、可预加载、可并发）。
2. 没有 `setOnCompletionListener`，播放器只能靠 3.3s 定时器 `release()`——如果素材比 3.3s 长，
   音效会被硬切；如果短则空转。应挂 `OnCompletionListener` 提前释放。
3. `USAGE_ASSISTANCE_SONIFICATION + CONTENT_TYPE_SONIFICATION`（120-121 行）走的是"系统提示音"通道：
   **静音/振动/勿扰模式下会被直接吞掉**，音量跟随铃声通道，用户调媒体音量也没用。
   彩蛋音效应使用 `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC`（或 `USAGE_GAME`）。
4. `openFd` 依赖 asset **未被压缩**。目前 `.m4a` 在 aapt2 的默认 no-compress 白名单里，能工作；
   但这属于隐式依赖——一旦有人把素材换成 `.ogg`/别的扩展名或显式开启压缩，
   `openFd` 就会抛 `FileNotFoundException: ... it is probably compressed`。建议显式声明（见 A2）。
5. `player.stop()`/`isPlaying()` 的状态机异常已被 `catch (Throwable)` 兜住，这点写得比较稳。

**修复建议**：

```java
private static MediaPlayer playSound(AssetManager am, String asset) {
    MediaPlayer player = new MediaPlayer();
    try (AssetFileDescriptor afd = am.openFd("mj/" + asset + ".m4a")) {
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)          // ← 跟随媒体音量，不被静音吞掉
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
        player.setOnCompletionListener(mp -> { try { mp.release(); } catch (Throwable ignored) {} });
        player.prepareAsync();                                 // ← 不阻塞主线程
        player.setOnPreparedListener(MediaPlayer::start);
        return player;
    } catch (Throwable t) {
        try { player.release(); } catch (Throwable ignored) {}
        return null;
    }
}
```

（注意：改用 `prepareAsync()` 后，`Session.stop()` 里需要区分"已 prepared / 未 prepared"两种状态再 `stop()`，
或统一用 `reset()` + `release()` 包在 try 里。）

---

### M7 `WeakHashMap` 的 value 强引用 key：`ACTIVE` 实际不会自动清理；且 `show()` 中途失败会留下永不复位的 Session

**位置**：`MjOverlay.java:38-39`（`ACTIVE`）、`MjOverlay.java:145-157`（`Session` 持有 `root`）、`MjOverlay.java:100-105`（注册顺序）

**原因**：

```java
private final ViewGroup root;   // Session 强引用 root
...
ACTIVE.put(root, session);      // ACTIVE 的 key=root, value=Session → value 强引用 key
```

`WeakHashMap` 的 key 之所以能被回收，前提是 **value 不反向强引用 key**。这里 `Session.root`
正好强引用 key，于是 `ACTIVE` 里的条目**永远不会因为 key 变弱而自动消失**，只能靠 `stop()`
里的 `ACTIVE.remove(root)` 显式清理（191 行）——也就是说 `WeakHashMap` 在这里没有任何意义，
换成 `HashMap` 语义等价，且更容易看出"必须手动清理"。

更实际的风险在清理路径上：`show()` 的顺序是 `addView` → `ACTIVE.put` → `playSound` → `postDelayed`。
只要 `ACTIVE.put` 之后、`postDelayed` 之前的任何一步抛异常（`playSound` 已自带 catch，但
`image.setImageDrawable` / `host.postDelayed` 在极端情况下会抛，例如宿主 view 已 detach +
handler 异常），`Session` 就永远留在 `ACTIVE` 里：**MediaPlayer 不停、view 不摘、`previous.stop()`
下次反而会把它清掉一半**。同时 3.3s 定时器是唯一生命周期来源，宿主若提前把整个窗口拆掉，
音效会在页面切换后继续响到定时器触发。

**修复建议**（两件小事）：

```java
// 1) 让 value 不再强引用 key
private static final class Session implements Runnable {
    private final WeakReference<ViewGroup> rootRef;   // 不再是强引用
    ...
    void stop() {
        ViewGroup r = rootRef.get();
        if (r != null && ACTIVE.get(r) == this) ACTIVE.remove(r);
    }
}

// 2) 注册与清理放在 try/finally，任何一步失败都能复位
Session session = new Session(root, host, image);
ACTIVE.put(root, session);
try {
    root.addView(host);
    image.setImageDrawable(session.drawable);
    ...
    if (sound) session.player = playSound(am, asset);
    host.postDelayed(session, PLAYBACK_MS);
} catch (Throwable t) {
    Log.w(TAG, "overlay failed: " + t);
    session.stop();          // 不留半成品
}
```

另外建议给 `host` 挂 `addOnAttachStateChangeListener`：宿主窗口被销毁时立即 `stop()`，
不要等定时器。

---

### M8 "只 hook `EditText`" 覆盖不到抖音的输入路径，缺兜底

**位置**：`ModuleMain.java:80-106`（注释断言"抖音的评论输入框 / 私信输入框都继承自 android.widget.EditText"）；`README.md:24`

**原因**：这个断言目前是**假设**，没有任何证据支撑（仓库里没有反编译产物佐证，`tools/dexstrings.py` 也一直没用上）。
已知的现实风险：

1. **评论输入**：抖音近几年把评论输入面板大幅迁移到 **Lynx 动态模板**（README 自己也提到
   `CommentEasterEggModule` / `CommentLynxEasterEggViewDelegate`）。如果输入框是 Lynx 的
   `<input>`/`TextArea` 组件，它底层仍是原生 `EditText`（可能性大），但如果换成自绘文本编辑器
   （`LynxTextArea` 自绘 + 自建 `InputConnection`）或 Jetpack Compose `BasicTextField`，**hook EditText 完全无效**。
2. **私信**：如果 IM 相关逻辑跑在 `:im` 之类**独立进程**，并且该进程被注入（M2 的做法是反过来的：
   你现在全进程都装，恰好覆盖了这种情况）——但一旦你按 M2 优化成"只主进程"，就必须确认 IM 进程名。
3. **`EditText` 构造被内联**：`EditText` 的构造体只有一句 `super(...)`，AOT/JIT 很可能把它内联进子类构造。
   若某个 ROM/某次编译没有被 LSPosed 的 dex2oat wrapper 关掉内联，构造 hook 就会**静默不触发**，
   而你的"兜底"（attach hook）只覆盖 EditText 实例——如果构造 hook 失效，兜底也不会触发
   （因为兜底判断的也是 `instanceof EditText`）。**兜底路径和主路径失效条件是重叠的**，这是设计缺陷。
   排查手段：`deoptimize(ctor)`（`XposedInterface.java:471-487`）。

**修复建议**（按性价比排序）：

1. **先取证**：用仓库里的 `tools/dexstrings.py` 对抖音 APK 的 dex 目录跑一遍，确认输入框实现：
   ```powershell
   python tools/dexstrings.py <解包后的 dex 目录> "EditText|InputConnection|TextArea|comment_input" 200
   ```
   确认到底是 `XxxEditText` 还是 Lynx/Compose。**不要靠猜**。
2. **兜底改成树遍历**（见 M1 的 `scan()`）：只要输入框是 `EditText`，树遍历一定能覆盖，
   且与构造 hook 的失效条件不重叠。
3. **再加一层"非 EditText"兜底**：hook `android.view.inputmethod.BaseInputConnection#commitText`
   与 `#setComposingText`，把提交的文本拼起来判定。Compose / 自绘输入框（只要用了标准
   `InputConnection`）都能覆盖：
   ```java
   Method commit = BaseInputConnection.class.getDeclaredMethod("commitText", CharSequence.class, int.class);
   hook(commit).intercept(chain -> {
       Object r = chain.proceed();
       try { MjEngine.onInputText(String.valueOf(chain.getArg(0))); } catch (Throwable ignored) {}
       return r;
   });
   ```
   注意这条路拿不到 `EditText`，播放要改成"用当前焦点的 DecorView / `WindowManager` 全局浮层"，
   这会牵出一处架构调整——如果 dexstrings 确认是原生 EditText，**不建议**上这条，成本偏高。
4. 若抖音确为原生 EditText，也建议在 README 里把"目前没有"这种断言改成"已在 vX.Y.Z 上验证"，
   并写清验证方法。

---

### M9 冷却 600ms « 动画 3300ms：连打 `mjmjmj` 会反复重启动画

**位置**：`MjEngine.java:16`（`COOLDOWN_MS = 600`）、`MjEngine.java:29-32`、`MjOverlay.java:66-67`

**原因**：用户按原版手感连续输入 `mj` `mj` `mj` 时，每 600ms 就满足一次触发条件，`MjOverlay.show()`
里的 `previous.stop()` 会把上一段动画**当场掐掉重新开始**，视觉上是"闪一下就重来"。
原版彩蛋是"发送成功后播一次"，不存在这个问题。

**修复建议**：

```java
// 方案 1：冷却时间不短于一次完整播放
private static final long COOLDOWN_MS = MjOverlay.PLAYBACK_MS + 200L;

// 方案 2（更贴原版）：同一个 root 正在播放时直接忽略，且"文本被清空=已发送"再播
//   在 TextWatcher 里记 pending，等 s.length()==0 时再 MjOverlay.show(...)
```

同时建议加"只对用户可见输入生效"的过滤，避免程序化 `setText` 误触：

```java
if (!editText.isAttachedToWindow() || !editText.isShown() || !editText.hasFocus()) return;
```

---

## 三、【建议】

### A1 `module.prop` 与 scope 配置的收尾

**位置**：`module.prop:1-3`、`scope.list`、`ModuleMain.java:31-35`

* 字段本身**用法正确**：`minApiVersion` / `targetApiVersion` 是必填项（`package-info.java:31-34`），
  `autoHotReload` 是 API 102 的可选项（`package-info.java:41-44`）。`java_init.list`（单行全限定类名 + `\n`）
  与 `scope.list`（每行一个包名 + `\n`）的格式、结尾换行都没问题。
* 建议补 `staticScope=true`：本模块只对三个包有效，`TARGET_PACKAGES` 里也做了同样的过滤
  （`ModuleMain.java:66-71`），不加 `staticScope` 只是让用户能把它勾到无关应用上白跑一遍。
* `minApiVersion=101` 却依赖 102 的 `autoHotReload`：要么把 minApi 提到 102，要么去掉 autoHotReload
  （见 M3）。
* 建议显式写 `exceptionMode=protective`（默认值就是它，`package-info.java:39-40`），
  让"hook 抛异常不炸宿主"这件事变成可见的声明而不是隐含默认。

### A2 素材体积与 `noCompress`

**位置**：`app/build.gradle.kts`（无 `noCompress`）、`app/src/main/assets/mj/`（合计 4.6MB）

```kotlin
androidResources {
    noCompress += listOf("m4a", "webp")   // openFd 的硬要求 + 顺手明确
}
```
再配合 M4 的素材降采样，APK 体积能砍掉一大半。

### A3 TextWatcher 的每键开销

**位置**：`ModuleMain.java:145-151`、`MjEngine.java:24-27`、`MjKeywords.java:36`

* `s == null ? "" : s.toString()` 每键都分配一个 String。改成先看长度、先看开关：
  ```java
  public void afterTextChanged(Editable s) {
      if (s == null || s.length() < 2) return;   // 99.9% 的按键在这里就返回了
      if (!MjSettings.enabled()) return;
      MjEngine.onTextChanged(editText, s.toString());
  }
  ```
* `MjKeywords.matchesExtra` 里的 `extraKeywords.split("[,，;；\\s]+")` **每次调用都编译一次正则**。
  应在设置变化时解析成 `Set<String>` 缓存起来。
* `MjSettings.enabled()` / `sound()` / `mode()` / `extraKeywords()` 每次都穿透读 Remote Preferences。
  Remote Preferences 支持变化监听（`wiki-modern-api.md:25` 的 Change Listener 列），
  应该注册 listener + 缓存到 `volatile` 字段，把热路径变成纯内存读。

### A4 每个 EditText 实例打一条 INFO 日志

**位置**：`ModuleMain.java:153`

`log(Log.INFO, TAG, "watcher installed on " + editText.getClass().getName())` 在**引导类构造函数 hook**
里执行，抖音一个页面里可能有几十个输入控件（含 `SearchBox`、`EditText` 子类），
而且 `log()` 走框架（可能有 IPC/IO）。请改成 `Log.DEBUG` 或加 `if (BuildConfig.DEBUG)` 开关。

### A5 4 个构造函数全都 hook 是多余的

**位置**：`ModuleMain.java:86-104`

`EditText` 的 1/2/3 参构造最终都会 `this(...)` 链到 4 参构造，而 4 参构造也被 hook 了 →
**每次 `new XxxEditText(context)` 会走 4 次 hook 桥**（`WATCHED` 集合保证了只挂一个 watcher，
但 3 次多余的 proceeds 是白付的）。只 hook 4 参那一个即可：

```java
Constructor<?> ctor = EditText.class.getDeclaredConstructor(
        Context.class, AttributeSet.class, int.class, int.class);
hook(ctor).intercept(...);
```

### A6 往 DecorView `addView` 的可行性与几个小坑

**位置**：`MjOverlay.java:69-93`

* **可行性**：`DecorView`（API 21+ 是 `FrameLayout` 子类）`addView` 一个 MATCH_PARENT 的子 View 是
  Xposed 模块里很常见的做法，**能工作**；它位于 IME 之下（IME 是独立窗口）、位于页面内容之上，
  符合预期。
* **触摸穿透**：目前**能穿透**。`host` 与 `ImageView` 都不消费事件（`setClickable(false)` +
  无 `OnTouchListener`），`ViewGroup#dispatchTouchEvent` 会把事件继续派发给下面的兄弟节点。
  Android 12+ 的"不可信触摸"拦截只针对**跨窗口**浮层，窗口内 `addView` 不受影响。这点代码是对的。
* **但**：这是"非官方支持"的用法，抖音/某些 ROM 里有 `((ViewGroup) decorView).getChildAt(0/1)`
  这类硬编码假设，你多插一个孩子可能触发奇怪行为（历史上出现过 ActionMode/Transition 错乱）。
  更稳的是插到 `android.R.id.content`，或干脆用 `WindowManager.addView(..., type=TYPE_APPLICATION_PANEL, token=decor.getWindowToken())`：
  ```java
  ViewGroup content = root.findViewById(android.R.id.content);  // 找不到再退回 root
  (content != null ? content : root).addView(host);
  ```
* `host.setClipChildren(false)`（74 行）是无效代码：它只影响**它自己的孩子**是否被裁剪，
  而 `host` 的唯一孩子是 MATCH_PARENT 的 `ImageView`，没有越界内容。
* `host.setElevation(1000f)`（75 行）没有实际作用（无 background → 无 outline → 不投影），
  徒增一次 RenderNode 属性设置；想置顶应依赖 addView 顺序或 `bringToFront()`。
* 建议补 `host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)`，
  否则 TalkBack 会在特效播放时读到/聚焦到这个空容器。
* `getRootView()`（`MjEngine.java:40`）在输入框位于 `PopupWindow`/小尺寸 Dialog 时返回的是**那个小窗口**
  的 decor，动画会被裁在小窗口里。建议在 `MjEngine` 里优先解析 Activity 的 DecorView：
  ```java
  Context c = editText.getContext();
  while (c instanceof ContextWrapper) {
      if (c instanceof Activity) { root = ((Activity) c).getWindow().getDecorView(); break; }
      c = ((ContextWrapper) c).getBaseContext();
  }
  ```

### A7 静默吞异常，出问题无法定位

**位置**：`MjEngine.java:43-44`（`catch (Throwable ignored) {}`）、`ModuleMain.java:95-97`、`121-122`

S1 那个"素材读不到"的致命问题之所以能在开发阶段被忽略，很大程度是因为整条链路上有 4 处
`catch (Throwable ignored)`。建议至少 `log(Log.WARN, TAG, "overlay failed", t)` 一次（限流），
并把 `MjOverlay.java:89-91` 的 decode 失败从 `WARN` 提升为带堆栈的 `ERROR`。

### A8 构建与发布卫生

* `build.gradle.kts:22`：release 用 **debug 签名**。至少加注释警告，或改 `signingConfigs`。
* `build.gradle.kts:18-24`：`isMinifyEnabled = false` 时 `proguard-rules.pro` **完全不会生效**，
  所以里面的 `-adaptresourcefilecontents` 和 `-keep` 现在都是"死配置"。将来一旦开启混淆，
  请务必确认 `java_init.list` 被正确改写（否则 `ClassNotFoundException` 让模块静默失效）。
* `build.gradle.kts:37-40`：`abortOnError = false` + `checkReleaseBuilds = false` 把 S3 这类问题盖住了，
  见 S3 的建议。

### A9 `MjSettings` 的默认值策略

**位置**：`MjSettings.java:62-70`

`remotePrefs == null` 时一律返回默认值（`enabled=true`、`sound=true`）。也就是说
**框架读不到配置时模块反而是"全开"**。这个默认值本身可以接受，但建议在
`ModuleMain.onPackageReady` 里把"remote prefs 不可用"降级为一条明确的 `ERROR` 日志，
否则用户永远不知道为什么设置不生效（叠加 S2，这将是最常见的用户反馈）。

### A10 README 与实现不符之处（会误导使用者与后续维护者）

* `README.md:19`："不拦截触摸" —— 结论对，但依靠的是"没人消费事件"的隐式行为，建议写明。
* `README.md:24`："`EditText` 是系统类，任何版本、任何页面…都能命中" —— 见 M8，这是假设不是结论。
* `README.md:65`："模块 App 内可以：开关彩蛋…" —— 见 S2，目前**全部无效**。
* `README.md:71`："更低版本只在日志里提示" —— 实际是 API 26–28 上**模块 App 直接崩**（S3）。
* 建议补一条"热重载/模块升级后需要重启目标 App"（M3）。

### A11 风险提示：抖音的风控

字节系的客户端对 Xposed/LSPosed 特征（`de.robv.android.xposed.*` 类存在、`/proc/self/maps` 中的
`lspd`/`riru`、方法 hook 后的调用栈异常、`Method` 数量突变等）有检测能力。本模块的 hook 点
（引导类 `EditText` 构造 + `View#onAttachedToWindow`）属于**痕迹最明显**的那一类。
建议在 README 里明确写出"存在被客户端风控识别/账号风险，建议用小号测试"，
并优先采用 M1/M8 里"少而精"的 hook 点（减少 hook 数量本身就能降低特征）。

### A12 `MjKeywords` 的小边界（可忽略）

`MjKeywords.java:23-32` 的整段匹配是刻意贴合原版判定，逻辑与 `tools/kwtest/KwTest.java`
里的用例一致（含 `"  mj  "` → `trim()` 后命中），这部分我核对过是**正确**的。
未覆盖：全角 `ｍｊ`、零宽字符、`mj` 前后被输入法自动插入的空格以外的空白（`trim()` 已处理首尾）。
如需更宽容，`text.replace("\u200B","")` + `Normalizer.normalize(text, Form.NFKC)` 即可，但不是必须。

---

## 四、对你提出的 5 个重点的逐条结论

**1) libxposed API 102 用法是否正确？**

* `module.prop` 字段、`java_init.list`、`scope.list` 的**格式与位置全部正确**（含结尾换行），
  `minApiVersion/targetApiVersion` 齐全。问题只在语义层面：`autoHotReload=true` 却未实现热重载契约（M3）、
  建议补 `staticScope`（A1）。
* `XposedModule` 生命周期用法正确：不在构造期做初始化，`onPackageReady` 里安装 hook，
  `log()` 只在框架 attach 之后调用（`XposedInterfaceWrapper.java:43-47` 的 `ensureAttached` 不会被踩到）。
  非目标包未 `detach()` 是浪费（M2），不是错误。
* `hook(Executable).intercept(Hooker)` 用法正确；返回值 `HookHandle` 被丢弃 → 无法 unhook/replace（M3）。
* **构造函数 hook 的语义**：`Chain#proceed()` 对构造函数执行原构造（返回 `null`，`XposedInterface.java:226-229`），
  `getThisObject()` 在 `proceed()` 之后拿到的是**已完成 super 链、字段已初始化**的实例，
  因此 `ModuleMain.java:88-99` 里"先 proceed，再 getThisObject，再 addTextChangedListener"的写法**是正确的**
  （反过来在 proceed 之前碰这个对象才是错的，代码没有这么写）。唯一注意点：构造函数 hook 有可能被
  内联而静默不触发（M8），必要时用 `deoptimize()` 排查。
* 异常处理：hooker 内部四处 `catch(Throwable ignored)`，配合默认的 `PROTECTIVE` 模式（`XposedInterface.java:335-360`）
  不会炸宿主，但会掩盖问题（A7）。
* **能否 hook 引导类（EditText/View）**：可以，libxposed 通过 `Executable`/`Class` 对象定位，
  不受 ClassLoader 影响；LSPosed 也支持 hook 引导类方法。代价是会影响该进程内**所有** App 共享的该类
  （已由 scope 限制），以及上面的内联/热路径问题。
* **"编译过、运行炸"的真实清单**：S1（素材读不到，功能全废）、S2（设置全废）、S3（API<29 崩）、
  M3（热重载后模块报废）。这四条都不会在编译期被发现。

**2) Android 运行期风险**

* DecorView `addView`：可行，触摸能穿透；但属非官方用法，建议插到 `android.R.id.content`
  或用 `WindowManager` 浮层（A6）。
* `AnimatedImageDrawable`：API 28+（已有 `isSupported()` 保护，正确）；**需要硬件加速窗口**，
  软件渲染下只出首帧（M5-2）；`repeatCount` 与容器 `-loop 0` + 3300ms 魔数的组合存在重播跳帧风险（M5-1）。
* `MediaPlayer`：主线程 `prepare()`、缺完成回调、音效通道选成"提示音"（静音即失效）、`openFd` 隐式依赖不压缩（M6）。
* `WeakHashMap`/异步清理：`ACTIVE` 的 value 强引用 key，等价于永不自动清理；`show()` 中途失败会留下
  不复位的 Session（M7）。
* 内存占用：900×1974×4 ≈ **7.1MB/帧**，动画期峰值 15–25MB，且在主线程解码 → 必现首帧卡顿（M4）。
* 触摸穿透：当前实现正确（见 A6）。

**3) 触发链路**

* 只 hook `EditText` **不足以**证明覆盖抖音评论/私信：Lynx/Compose/自绘输入框、以及构造被内联的场景
  都会漏；而且"兜底"attach hook 的失效条件与主路径**重叠**（M8）。建议先 `dexstrings.py` 取证，
  再把兜底换成树遍历，必要时补 `BaseInputConnection#commitText`。
* `TextWatcher` 性能：绝对量很小（每键 O(n) 扫描），但当前实现有 3 处可省的分配/IO（A3）；
  加上 M9 的 600ms 冷却导致的重复重启才是真正的体感问题。
* hook `View#onAttachedToWindow` 的副作用：收益接近 0（构造 hook 已覆盖）、成本是"全进程每个 View 一次 bridge 调用
  + 禁用该方法内联 + 与常见 hook 点的冲突面"，建议删掉换成树遍历（M1）。

**4) 模块 App 与宿主进程的类加载**

* **设置页不会因为 `compileOnly` 的 libxposed jar 而崩**：`SettingsActivity` 只依赖
  `MjSettings`/`MjOverlay`，这两个类**不引用任何 `io.github.libxposed.*` 类型**；ART 的类校验是**按类惰性**的，
  不加载 `ModuleMain` 就不会去解析 `XposedModule`。而且 LSPosed 现代 API 明确"模块 App 不再被 hook"
  （`wiki-modern-api.md:11`），`ModuleMain` 在模块 App 进程里根本不会被加载。
* 但这是个**隐式契约**，很容易被后续改动打破：只要有人在 `SettingsActivity` 里
  `import com.mjcomeback.easteregg.ModuleMain`（比如想复用 `TAG` 或 `log`），
  模块 App 就会 `NoClassDefFoundError: io.github.libxposed.api.XposedModule`。
  建议在 `MjSettings` 头部加注释墙，并把 `TAG` 常量、日志工具抽到独立的 `MjLog`（不依赖框架），
  同时**不要**把 libxposed 从 `compileOnly` 改成 `implementation`（否则宿主里会出现两份 `XposedModule`，
  `attachFramework` 会 `IllegalStateException`/`ClassCastException`）。
* 真正会崩的是 S3（主题 API 29）——与类加载无关，但同样只在 API<29 设备上暴露。

**5) 抖音侧更稳的 hook 点 / 兜底方案**

1. **取证优先**：`python tools/dexstrings.py <dex目录> "EditText|TextArea|InputConnection"` 确认输入框实现，
   别靠猜。
2. 主路径保留 `EditText` 4 参构造 hook（最省事、跨版本稳）。
3. 兜底改为 `Activity.onResume` + `Dialog.show` 的**树遍历**（成本有界、与构造 hook 失效条件不重叠）。
4. 需要覆盖自绘/Compose 输入框时，再补 `BaseInputConnection#commitText`/`setComposingText`（M8）。
5. 若想更贴"原版只在发送时触发"：把 `TextWatcher` 里的匹配结果记为 pending，
   在"文本被清空"（发送后清空）时再播放，顺带解决 M9 的连打重启问题。
6. 若抖音真的迁移到 Lynx 模板，最终方案其实是 hook Lynx 的 `LynxView`/`TemplateRenderHandler`
   或 `CommentLynxEasterEggViewDelegate` 的调用链——但这是"跨版本极易失效"的路，
   建议只在有明确类名/版本证据时再做，并用 DexKit 做运行时查找。

---

## 五、总体结论

**结论：不可发布（Not shippable）。**

1. **功能层面是 0**：由于 S1，模块在抖音进程里连一帧动画都放不出来、一个音符都播不出来；
   唯一"能用"的场景是模块 App 内的预览按钮——这恰好是最容易让人误判"已完成"的假象。
2. **配置层面是 0**：由于 S2，设置页的 4 个开关/选项全部无效，用户以为改了什么也没改。
3. **稳定性层面有必崩项**：S3 让模块 App 在 API 26–28 上直接崩溃；M3 会让"升级模块 App"这一操作
   把正在运行的抖音里的模块功能悄悄打死。
4. **性能/内存层面需要收敛**：主线程解码 900×1974 动画（M4）+ 全量 hook 最热的
   `View#onAttachedToWindow`（M1）+ 全进程注入（M2），对一个"3 秒特效"来说成本明显过高，
   而收益是一个可以被树遍历以近乎零成本替代的能力。
5. **架构方向是对的**：不用硬 hook 混淆业务类、素材全离线、改用系统类做嗅探——这个思路在
   "服务端配置被撤 + 素材不下发"的前提下是合理的；`MjKeywords` 的判定逻辑、libxposed 现代 API
   的骨架（`module.prop`/`java_init.list`/`scope.list`、生命周期、链式 hook）都用对了。
   问题几乎全部集中在"跨进程资源获取"和"UI/媒体生命周期"这两块经验性领域。

**修复优先级建议**：S1 → S2（或先删掉骗人的设置页）→ S3 → M3 → M2/M1 → M4/M5/M6/M7 → M8（先取证）→ 其余。

**验收清单**（建议逐条做完再发版）：

- [ ] 真机（抖音最新版 + 至少一台 API 28、一台 API 34）输入 `mj`，确认动画 + 音效都出现；
- [ ] 强杀抖音进程重启后再测一次（验证 hook 安装时机）；
- [ ] 抖音私信、群聊、评论区、搜索框各测一次（区分"期望触发/期望不触发"）；
- [ ] 升级模块 App（触发 hot reload 路径）后不重启抖音再测（验证 M3）；
- [ ] 在评论区连打 4 次 `mj`，确认动画不出现"闪一下重来"（验证 M9）；
- [ ] API 26–28 设备打开模块 App 不崩（验证 S3）；
- [ ] 打开/关闭设置页每个开关，杀进程重启后确认真的生效（验证 S2）；
- [ ] 录屏 + 正常使用两种情况下都确认动画可见（验证 M5 的硬件加速限制）；
- [ ] `adb shell dumpsys meminfo <抖音包名>` 观察触发前后的 PSS 增量（验证 M4）。
