# MJ你快回来吧

**抖音 / TikTok 的 LSPosed 模块。发送 `mj`，把当年那个蜘蛛侠彩蛋放回来。**

<p align="center">
  <img src="docs/img/drop.gif" width="210" alt="蜘蛛侠吊着蛛丝出场">
  &nbsp;&nbsp;&nbsp;
  <img src="docs/img/swing.gif" width="210" alt="蜘蛛侠荡秋千送爱心">
</p>

## 这是个啥

2026 年 8 月，抖音上线过「蜘蛛侠联动彩蛋」：在私信、群聊、评论区的输入框发送 `MJ`，
会弹出蜘蛛侠动画。9 月因刷屏被官方下线——服务端配置撤了，素材也不随安装包下发，
所以**靠"改配置"是复活不了的**。

这个模块换个思路：素材自带、完全本地播放。你在抖音里发出一句 `mj`，它就在你屏幕上
把那段动画放一遍，跟当年一模一样。

## 效果

|  |  |
| --- | --- |
| 触发词 | `mj`（整段都是 mj 也算：`mjmj`、`MJMJ`、`MjMj`…，大小写随意） |
| 播放内容 | 两段原版动画（约 3 秒）：蜘蛛侠吊着蛛丝出场 / 荡秋千送爱心，带原版音效 |
| 出现在哪 | 评论、私信、群聊、回复弹窗——凡是原生输入框的地方都能用 |
| 扩散范围 | 只在你自己手机上播，不会打扰对方（原版当年也是本地 CG） |

## 安装

1. **装 APK**：[下载最新版](../../releases/latest)（当前是 `mjcomeback-1.0.0.apk`）。
2. **在 LSPosed 里启用**：勾选「MJ你快回来吧」，作用域勾上**抖音**。
   用 TikTok 的话，两个 TikTok 包名已经预置好，一并勾上即可。
3. **强制停止抖音，再重新打开**，然后随便找个评论区发一句 `mj`。

> 设置完必须"强制停止"一次抖音，模块才会在它下次启动时生效。

## 设置

| 项 | 说明 |
| --- | --- |
| 启用彩蛋 | 总开关 |
| 播放音效 | 不想吵就关掉 |
| 播放顺序 | 交替（两段轮着来）/ 随机 |
| 附加关键词 | 想让别的词也触发就填在这里，逗号分隔（例如 `蜘蛛侠,spiderman`），同样要求整段匹配 |
| 试一试 | 不用装抖音就能预览：输入 `mj` 点「发送」，或直接点下面两个预览按钮 |

设置页顶部会提示有没有连上 LSPosed 服务。没连上时，设置只在设置页里生效，不会同步给抖音。

## 兼容性

| 环境 | 要求 |
| --- | --- |
| Android | 9 及以上（动画用的是系统 AnimatedImageDrawable，8.x 会跳过播放） |
| LSPosed | 需要支持 libxposed 现代 API（API 102）的版本，太老的 LSPosed 不会加载本模块 |
| 抖音 | 与具体版本无关。目前实测抖音 41.1.0 / Android 14 |
| 其他 | 除了 LSPosed 不需要别的东西；模块零权限、零联网 |

## 常见问题

**发了 `mj` 没反应？**
按顺序排查：模块启用了没 → 作用域勾了抖音没 → 有没有强停抖音重开 → 输入框里是不是只有 `mj`
（`mj 好看` 这种不触发，为的是贴合原版判定、少误触）。
仍然不行就抓日志提 issue：

```bash
adb logcat -s MJComeback MJAssets MJOverlay
```

**我这边播了，对方能看到吗？**
看不到。这是本机播放的动画，不随消息下发——原版当年也是这样。

**能让官方特效真正复活吗？**
不能。官方那套依赖服务端下发配置和素材，服务端已经撤了。本模块只是把两段素材搬回本地重放。

**会改我的消息、或者上传什么数据吗？**
不会。模块不联网、不申请任何权限，只在你屏幕上叠一层透明动画：不拦触摸、不碰消息内容。

## 从源码构建

需要 JDK 17 和 Android SDK（platform 34）。Gradle 用仓库里的 wrapper，不用另装：

```bash
./gradlew :app:assembleRelease        # Windows 用 .\gradlew.bat :app:assembleRelease
```

产物是 `app/build/outputs/apk/release/app-release.apk`。首次构建会下载 Gradle 8.9（约 130 MB），
国内网络卡的话，把 `gradle/wrapper/gradle-wrapper.properties` 里的地址换成镜像
（如 `https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip`）。

素材和图标都是脚本生成的：`pwsh tools/build_assets.ps1`、`python tools/make_icon.py …`。

## 素材来源

动画和音效取自 [qiu7c/Tiktok-MJ-for-Wechat](https://github.com/qiu7c/Tiktok-MJ-for-Wechat)
仓库里的原版双蒙版素材（左半 Alpha、右半彩色），构建时转成带透明通道的动画 WebP 内置进 APK。
感谢原作者的整理。

## 免责声明

仅供个人学习与怀旧，请勿商用。动画与角色版权归原权利方（漫威 / 抖音）所有，本项目与官方无关。

---

## 如果你是 AI 助手

这份 README 只讲"人"需要知道的部分。要**改代码**请先读 **[AGENTS.md](AGENTS.md)**：
里面有项目结构、运行时数据流、常见改动的落点，以及一节「关键约束」——例如
"只在发送时播放""素材必须从模块 APK 里读"这类踩过坑才定下的写法，别当成冗余代码优化掉。
历史代码审查报告在 `docs/`。
