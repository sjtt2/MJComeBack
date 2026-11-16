# MJ你快回来吧（抖音 / TikTok LSPosed 模块）

在抖音的**评论区、私信、群聊**输入框里**发送** `mj`，重新播放当年那个蜘蛛侠彩蛋动画
（和原版一样：打字时不出现，消息发出去才出现）。

> 原版是 2026 年 8 月上线的抖音 × 蜘蛛侠联动：在私信 / 群聊输入框发送 `MJ` 触发，随机播放
> 「蜘蛛侠倒挂出场」和「蜘蛛侠荡秋千送心心」两段 CG，2026 年 9 月因刷屏被官方下线。
> 服务端配置已经撤掉，客户端里只剩 Lynx 模板的调用代码（`CommentEasterEggModule`、
> `CommentLynxEasterEggViewDelegate` 等），素材不随包下发，所以**改配置是复活不了的**。
> 本模块改用「本地重放」：不依赖服务端，直接在输入框层面嗅探关键词并播放原版素材。

## 工作原理

1. 基于 **libxposed API 102**（LSPosed 现代 Xposed API），入口 `io.github.sjtt2.mjcomeback.ModuleMain`。
2. `onPackageReady` 时给 `android.widget.EditText` 的全部构造函数挂钩，构造完成后挂 `TextWatcher`；
   另外在 `Activity#onResume` / `Dialog#show` 时扫一遍视图树兜底。
3. **只在发送时触发**。判断“发送”用了两个不依赖抖音混淆类名的信号：
   - `View#performClick`（发送按钮 / 发送箭头）或 `TextView#onEditorAction`（输入法发送键）之后 2.5s 内，
     输入框被清空，且清空前内容正好是 `mj` 连写；
   - 输入框被**程序化** `setText("")` 清空（键盘手动删字不会走 `setText`）。

   打字过程中只记录「待发送的内容」，不播放。
4. 命中后在**当前输入框所在窗口**的装饰视图上叠一层透明、不可点击的图层，用
   `AnimatedImageDrawable` 播放带 Alpha 通道的动画 WebP，同时播放原版音效。图层只铺「键盘上方」
   的可见区域，避免被输入法窗口拦腰截断；播放结束自动移除，不拦截触摸。

为什么这么做：

- 抖音类名高度混淆，彩蛋挂在 Lynx 动态模板上，硬 hook 业务类跨版本必坏；
  `EditText` 是系统类，任何版本、任何页面（评论 / 私信 / 群聊 / 回复弹窗）都能命中。
- 素材离线内置，和抖音版本、服务端配置无关。

## 目录结构

```
.
├─ app/                          模块本体（标准 AGP 单模块布局）
│  ├─ src/main/java/io/github/sjtt2/mjcomeback/
│  │   ModuleMain.java           Xposed 入口：进程过滤 + hook 安装
│  │   MjEngine.java             触发判定（关键词、冷却、输入法组词过滤）
│  │   MjKeywords.java           关键词匹配规则
│  │   MjSettings.java           配置（宿主读 Remote Preferences，模块 App 写同一份）
│  │   MjAssets.java             从模块 APK 里读素材（ZipFile，不依赖宿主 AssetManager）
│  │   MjOverlay.java            动画 + 音效播放器
│  │   MjApp.java                注册 libxposed/service
│  │   MjService.java            LSPosed 服务句柄
│  │   SettingsActivity.java     设置页（含“试一试”输入框与预览按钮）
│  ├─ src/main/assets/mj/        构建产物：动画 WebP + 音效（由 tools/build_assets.ps1 生成）
│  ├─ src/main/res/mipmap-*/     图标（legacy + 自适应两层，由 tools/make_icon.py 生成）
│  ├─ src/main/resources/META-INF/xposed/
│  │   java_init.list            入口类
│  │   module.prop               minApiVersion=102 / targetApiVersion=102 / staticScope=true
│  │   scope.list                预置抖音 + TikTok 三个包名
│  └─ libs/                      libxposed api / service 的 classes.jar（API 102）
├─ assets/source/                原版「双蒙版」素材 mp4（转码的输入）
├─ tools/
│  ├─ build_assets.ps1           mp4 → 动画 WebP + m4a
│  ├─ make_icon.py               动画帧 → App 图标
│  ├─ kwtest/KwTest.java         MjKeywords 的单元测试（javac 直接跑）
│  ├─ scratch/                   当初逆向抖音用的临时脚本（连通域分析、ui dump 解析等）
│  ├─ icon/                      图标预览图（README 里引用的那几个，不提交）
│  └─ ffmpeg/                    本地 ffmpeg（不提交，按下方说明下载）
├─ docs/
│  ├─ REVIEW.md / REVIEW-2.md    两轮独立代码审查报告
│  └─ wiki-modern-api.md         libxposed 现代 API 笔记
├─ gradlew / gradlew.bat / gradle/wrapper/   固定 Gradle 8.9
├─ _scratch/                     本地工作区（不提交）：解包的抖音 APK、ffmpeg.zip、调试截图
└─ README.md / .gitignore / .gitattributes
```

## 素材

`assets/source/*.mp4` 是原版「双蒙版」素材：左半是白色 Alpha 蒙版，右半是彩色画面。
来源：[qiu7c/Tiktok-MJ-for-Wechat](https://github.com/qiu7c/Tiktok-MJ-for-Wechat) 仓库。

构建时用 ffmpeg 合成带真实 Alpha 的动画 WebP（720px 宽、30fps）并抽出音轨：

```powershell
pwsh tools/build_assets.ps1
```

产物在 `app/src/main/assets/mj/`：

| 文件 | 说明 |
| --- | --- |
| `mj_drop.webp` | 蜘蛛侠吊着蛛丝倒挂出场（约 3.0s） |
| `mj_swing.webp` | 蜘蛛侠荡秋千 + 爱心（约 3.0s） |
| `mj_drop.m4a` / `mj_swing.m4a` | 对应原版音效 |

## 图标

图标就是从「荡秋千」那段动画里取的第 66 帧（荡到最低点、爱心最全的那一帧）做的：
抹掉右上角残留的爱心碎片、把蛛丝接成从画布左上角穿出去的一条线，再叠一层深红径向渐变
背景 + 蛛网纹理，最后输出各密度的 legacy 图标和自适应图标（`ic_launcher_background` /
`ic_launcher_foreground` 两层）。

```powershell
# 直接从双蒙版素材重生成（--frame 66 是帧号，0~89）
python tools/make_icon.py assets/source/mj-swing-dual-mask.mp4 app/src/main/res --frame 66
```

调完可以看 `tools/icon/preview-icon.png`（方形）和 `tools/icon/adaptive.png`（圆形 / 圆角遮罩效果）。

## 构建

环境：JDK 17、Android SDK（platform 34 / build-tools 34.0.0）。
Gradle 版本由 wrapper 固定为 **8.9**，不用另外装，首次执行会自动下载。

```powershell
# Windows
.\gradlew.bat :app:assembleRelease
# macOS / Linux
./gradlew :app:assembleRelease

# 产物：app/build/outputs/apk/release/app-release.apk
```

SDK 路径写在 `local.properties`（`sdk.dir=...`），这个文件不进仓库。

> 首次构建会去 `services.gradle.org` 下载 Gradle 8.9（约 130 MB）。国内网络如果卡住，
> 把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成镜像即可，例如：
> `https\://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip`。

依赖说明：

- `app/libs/libxposed-api-102.jar`：编译期用（`compileOnly`），运行期由框架提供；
- `app/libs/libxposed-service-102.jar`：模块 App 与框架通信，必须打进 APK（`implementation`）。

两个 jar 都来自 Maven Central（`io.github.libxposed:api:102.0.0` / `:service:102.0.0`），
直接取 AAR 里的 `classes.jar` 是为了避开 AAR 的 `minCompileSdk=37` 限制（本工程用 compileSdk 34）。
`libxposed/service` 的 `XposedProvider` 已在 Manifest 里手动声明，authority 为
`${applicationId}.XposedService`。

> 默认用 debug 签名，正式发布请替换 `app/build.gradle.kts` 里的 `signingConfig`。

## 安装与使用

1. 安装 APK；

   > 包名已改为 `io.github.sjtt2.mjcomeback`。如果之前装过旧包（`com.mjcomeback.easteregg`），
   > 那个是独立的应用 id，请先卸载旧的，否则桌面上会有两个图标。

2. 在 LSPosed 中启用「MJ你快回来吧」，作用域勾选 **抖音**（已预置 `com.ss.android.ugc.aweme`、
   TikTok 的两个包名）；
3. 强制停止抖音后重新打开；
4. 打开评论区或私信，输入 `mj`。

设置页可以开关彩蛋 / 音效、选择「交替 / 随机」、设置附加关键词（如 `蜘蛛侠`），
并且不用装抖音就能预览两段动画。设置页顶部的状态行会显示是否已连上 LSPosed 服务
（连上才会同步到抖音）。

## 已知限制

- 只在**本机**播放，不会让对端看到特效（原版也是本地 CG，不随消息下发）。
- 动画需要 `AnimatedImageDrawable`，即 Android 9（API 28）及以上；
  更低版本只在日志里提示，不播放。
- 文本必须**整段**等于 `mj` 连写（或自定义关键词的完整文本）才触发，贴合原版判定、减少误触。
- “发送”是启发式判定（点击/发送键 + 清空输入框）。如果某个版本的抖音发送后**不清空**输入框，
  需要再补一个业务侧 hook 点，届时告我。
- 若抖音以后改用非 `EditText` 的自绘输入框，需要补充对应 hook 点。
- 首次在真机上验证时，可用 `adb logcat -s MJAssets MJOverlay MJComeback` 看模块日志
  （会打印 `module apk = /data/app/...` 与播放过程）。

## 免责声明

仅供个人学习与怀旧；素材版权归原权利方所有，请勿商用。
