# AGENTS.md

给 AI 编码助手的项目说明。**改代码前先读完这份，尤其是第 5 节「关键约束」**——这里的很多写法
不是风格偏好，而是踩过坑之后唯一能跑通的形态。

## 1. 这是什么

**MJ你快回来吧** —— 一个 Android **LSPosed 模块**（libxposed API 102，不是旧版 XposedBridge）。

抖音在 2026-08 上线过「蜘蛛侠联动彩蛋」：在私信 / 群聊 / 评论区输入框**发送** `MJ` 会弹出
蜘蛛侠动画。2026-09 因刷屏下线，服务端配置已撤、素材不随包下发，所以**改配置复活不了**。
本模块改为「本地重放」：在输入框层面嗅探关键词，发送成功后由模块自己把原版素材播一遍。

- 包名 / applicationId：`io.github.sjtt2.mjcomeback`
- 入口类：`io.github.sjtt2.mjcomeback.ModuleMain`（`XposedModule` 子类）
- 作用域：`com.ss.android.ugc.aweme` / `com.ss.android.ugc.trill` / `com.zhiliaoapp.musically`
- 联网：**没有**。APK 未声明任何权限，素材全在 APK 内，纯本地播放

它**不是**抖音破解 / 去水印 / 刷量工具；不修改抖音业务逻辑和消息内容；不发请求；不注入 JS。
它做的唯一一件事是「在本地屏幕上盖一层动画」。

## 2. 技术栈与版本

| 项 | 值 |
| --- | --- |
| 语言 / 源码级 | Java 17（**纯 Java，不要引入 Kotlin**） |
| AGP | 8.7.3 |
| Gradle | 8.9（wrapper 已固定，用 `./gradlew`，别用系统 gradle） |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |
| Xposed API | libxposed API **102**（`app/libs/libxposed-api-102.jar`：`compileOnly`，运行期由框架提供） |
| 服务通信 | `app/libs/libxposed-service-102.jar`（`implementation`，必须打进 APK） |
| 第三方依赖 | 无。除上面两个 jar 外没有任何依赖（连 AndroidX 都没用） |

## 3. 目录地图

```
app/src/main/java/io/github/sjtt2/mjcomeback/
  ModuleMain.java      Xposed 入口。进程过滤 → 装 hook（EditText 构造 / 窗口扫描 / 发送信号 / 程序化清空）
  MjEngine.java        触发判定状态机：PENDING + armedAt + 冷却；只有这里决定「播不播」
  MjKeywords.java      关键词规则（纯函数、无 Android 依赖，所以能脱离设备做单测）
  MjSettings.java      配置读取。宿主读 Remote Preferences，模块 App 写同一份
  MjAssets.java        从**模块 APK** 里 ZipFile 直读 assets（关键坑，见第 5 节）
  MjOverlay.java       叠层 + AnimatedImageDrawable + MediaPlayer，Session 管生命周期
  MjApp.java           Application，注册 XposedServiceHelper 监听
  MjService.java       XposedService 句柄（静态持有）
  SettingsActivity.java 设置页（代码里 hand-build 的 UI，没有 layout xml）
app/src/main/assets/mj/          构建产物：mj_drop / mj_swing 的 .webp + .m4a
app/src/main/res/mipmap-*/       图标（legacy + 自适应），由 tools/make_icon.py 生成
app/src/main/resources/META-INF/xposed/
  java_init.list / module.prop / scope.list    ← 必须与包名、入口类保持一致
assets/source/*.mp4              原版双蒙版素材（转码输入，不是运行时资源）
tools/build_assets.ps1           mp4 → 动画 WebP + m4a
tools/make_icon.py               动画帧 → 图标
tools/kwtest/KwTest.java         MjKeywords 单测（javac 直接跑）
tools/scratch/                   逆向抖音时的一次性脚本（不参与构建）
docs/                            两轮代码审查报告 + libxposed 现代 API 笔记
_scratch/                        本地大文件工作区（不提交）：解包的抖音 APK、ffmpeg.zip、调试截图
```

## 4. 常用命令

```powershell
# 构建（产物 app/build/outputs/apk/release/app-release.apk）
.\gradlew.bat :app:assembleRelease

# 关键词单测（必须带 -encoding UTF-8，源码里有中文注释）
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
& "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -d tools\kwtest\out `
    app\src\main\java\io\github\sjtt2\mjcomeback\MjKeywords.java tools\kwtest\KwTest.java
& "$env:JAVA_HOME\bin\java.exe" -cp tools\kwtest\out KwTest      # 期望输出 ALL PASS

# 素材重转（需要 tools/ffmpeg/…/ffmpeg.exe）
pwsh tools\build_assets.ps1

# 图标重生成（--frame 是 0~89 之间的帧号）
python tools\make_icon.py assets\source\mj-swing-dual-mask.mp4 app\src\main\res --frame 66

# 真机排障
adb logcat -s MJComeback MJAssets MJOverlay MJSettings
```

核对 APK 元信息（包名 / 标签 / xposed 三件套）：

```powershell
& "D:\tools\android-sdk\build-tools\34.0.0\aapt2.exe" dump badging app\build\outputs\apk\release\app-release.apk
python -c "import zipfile;z=zipfile.ZipFile('app/build/outputs/apk/release/app-release.apk');print(z.read('META-INF/xposed/java_init.list').decode())"
```

## 5. 关键约束（**不要「优化」掉这些**）

| 约束 | 原因 | 违反后果 |
| --- | --- | --- |
| **只在「发送」时播放**，打字过程中静默 | 用户明确要求与原版一致：发出去才出现 | 打字时持续弹动画，功能等于废掉 |
| 素材必须用 `ZipFile` 读**模块 APK** | 宿主进程里 `context.getAssets()` 拿到的是**抖音自己的** AssetManager | 静默无动画，日志里只有一条 asset not found |
| 叠层只铺「键盘上方可见区域」（`getWindowVisibleDisplayFrame`） | 输入法窗口永远盖在应用窗口之上 | 动画被键盘拦腰截断 / 只露一条 |
| 一次播放的 `Session` 挂在 host view 的 `setTag()` 上 | 避免静态 Map 持有已销毁 View | 泄漏、连发两条时不重播 |
| `MjSettings` 的 prefs 监听器必须**强引用** | `SharedPreferencesImpl` 用 WeakHashMap 存监听器，lambda 被 GC 后配置静默失效 | 改了开关没反应，且极难查 |
| 目标进程用**黑名单**而不是白名单 | 猜错进程名的代价是「私信里没反应」，比多挂几个进程严重 | 某些入口漏挂 |
| 不 hook 抖音业务类名 | 抖音类名高度混淆、彩蛋走 Lynx 动态模板，跨版本必坏 | 每个版本都要重写，还会崩在宿主进程里 |
| 关键词整段匹配（`mj` 连写才算） | 贴合原版判定、降低误触 | 发「mj 好看」也弹动画 |
| 非目标包 / 不需要的子进程要 `detach()` | 避免在 push / webview 进程里白装 hook | 资源浪费，个别进程行为异常 |
| 每个 hook body 必须 try/catch 包住 | hook 跑在宿主进程里，抛异常会把抖音带崩 | 抖音闪退 |
| `minSdk 26` 但 `AnimatedImageDrawable` 需要 API 28 | 清单兼容性 ≠ 运行时能力 | 必须走 `MjOverlay.isSupported()`，不能盲用 |

## 6. 运行时数据流

```
EditText 构造 ──hook──▶ installWatcher()      （+ Activity#onResume / Dialog#show 扫视图树兜底）
        │
        ▼
afterTextChanged ──▶ MjEngine.onTextChanged()
        │                 ├─ 匹配 → PENDING[et] = 文本（只记录，不播）
        │                 └─ 变空 → 若 armedRecently() 则 play()
        ▼
View#performClick / TextView#onEditorAction ──▶ onUserSendIntent() → armedAt = now
TextView#setText(cs, BufferType) 且结果为空 ─▶ onProgrammaticClear(before) → 直接 play()
        ▼
play()：冷却 1.2s → editText.post() → MjOverlay.show(rootView, …)
        ▼
MjOverlay：透明不可点击 FrameLayout → 单线程解码 WebP → AnimatedImageDrawable.start()
           + MediaPlayer 播 m4a（素材先 materialize 到宿主 cache）
           3150ms 后自动移除
```

两个判定常量在 `MjEngine`：`ARM_WINDOW_MS = 2500`（点击→清空的最大间隔）、`COOLDOWN_MS = 1200`。

## 7. 常见改动的落点

| 想改什么 | 改哪里 |
| --- | --- |
| 关键词规则 | `MjKeywords.matches()`，改完必须跑 `KwTest` 并补用例 |
| 触发灵敏度 / 重复触发 | `MjEngine` 的 `ARM_WINDOW_MS`、`COOLDOWN_MS` |
| 新增发送信号（别的输入法路径） | `ModuleMain.hookUserSendIntents()` / `hookProgrammaticClear()`，再让 `MjEngine` 记录或直接 `play()` |
| 加一段新动画 | ① 放进 `assets/source/` ② 改 `tools/build_assets.ps1` 的 `$jobs` ③ 改 `MjOverlay.ASSET_NAMES` 和 `nextIndex()` 的取模基数 |
| 加设置项 | `MjSettings`（key + 读取）→ `SettingsActivity`（UI + 写入）；宿主侧不用改，Remote Preferences 自动生效 |
| 换图标 | `python tools/make_icon.py …`，不要手改 `res/mipmap-*` |
| 改应用名 | `res/values/strings.xml` 的 `app_name`（桌面名 / LSPosed 模块名 / 设置页标题共用） |
| 改作用域 | `META-INF/xposed/scope.list` **和** `ModuleMain.TARGET_PACKAGES`，两处都要改 |
| 改包名 / 入口类 | `build.gradle.kts` 的 namespace + applicationId、源码目录、`java_init.list`、`MjAssets.MODULE_PACKAGE`、README |

## 8. 素材与版权

- `assets/source/*.mp4` 是原版**双蒙版**素材：**左半 = 白底 Alpha 蒙版，右半 = 彩色画面**。
  转码时 `crop` 出两半再用 `alphamerge` 合成，不能直接当普通视频播。
- 来源：GitHub `qiu7c/Tiktok-MJ-for-Wechat`（iOS 微信同名 tweak 的仓库）。
- 动画/角色版权归原权利方，本仓库只做个人学习与怀旧，**不要商用、不要打包进商业产品**。
- 不要往仓库里塞大文件：`_scratch/` 已在 `.gitignore` 里；`.webp`/`.mp4` 几 MB 是刻意的
  （构建必需），别再往上加。

## 9. 代码风格与约定

- 纯 Java、4 空格缩进、工具类用 `final class` + 私有构造器。
- 注释用中文，写**为什么**而不是**做了什么**；踩坑点必须留注释（已有注释不要删）。
- 日志：宿主侧 `log(Log.X, TAG, …)`，模块自身 `Log.x(TAG, …)`；TAG 固定
  `MJComeback` / `MJAssets` / `MJOverlay` / `MJSettings`——排障全靠 `adb logcat -s` 这几个。
- 不新增权限、不加网络代码、不引依赖。「零权限、零联网」是这个模块的卖点。
- commit message：中文 + 约定式前缀（`feat:` / `fix:` / `chore:` / `docs:`）。

## 10. 已知限制（别当成 bug 去「修」）

- 只在**本机**播放，对方看不到（原版也是本地 CG，不随消息下发）。
- 「发送」是启发式判定。若某个抖音版本发送后**不清空**输入框，这里就静默不播，
  需要补业务侧 hook 点（先在真机抓 `adb logcat -s MJComeback MJOverlay` 取证）。
- 只覆盖原生 `android.widget.EditText` 及其子类；Lynx / Compose 自绘输入框不覆盖。
- 已在**真机 + LSPosed** 上实测通过（评论 / 私信发送 `mj` 能正常触发）；模拟器只能验 UI 和素材播放。
- `SettingsActivity` 的 UI 是代码里手搭的（没有 layout xml），改样式直接改 Java。

## 11. 给 AI 的协作要求

1. 先读本文件，再看对应类，不要凭类名猜实现。
2. 改完必须 `.\gradlew.bat :app:assembleRelease` 通过；动到 `MjKeywords` 必须补跑 `KwTest`。
3. 不确定的行为（尤其是 hook 点、发送判定）**不要猜着改**，先在真机 logcat 取证。
4. 不要为了「整洁」重构 hook 结构、不要把启发式判定换成业务类 hook、不要删踩坑注释。
5. 不要提交 `_scratch/`、`app/build/`、`local.properties`、调试截图；素材和图标用工具脚本生成。
