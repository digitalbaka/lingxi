# 凌汐经典版 · 平板版（LingXi Classic Tablet）

凌汐（lingxi.xiguastudio.top）视频网站的 Android 客户端**平板移植版**，基于 [OldLX（灵犀经典版）] 移植而来：保留原有功能与轻量架构，针对 7 寸以上的平板与 Android 5.0+ 老设备做了显示与交互适配。

## 与 OldLX 原版的差异

| 项目 | OldLX 原版 | 本仓库（平板版） |
|---|---|---|
| 定位 | 手机 | 平板（sw600dp 及以上） |
| 栅格列数 | 固定 2 列 | 随屏幕自适应：手机竖屏 2 列 / 手机横屏 3 列 / 7–8 寸 3–4 列 / 10 寸+ 4–5 列 |
| 大屏布局 | 内容全宽拉伸 | 登录/注册表单限宽 480dp 居中、文章正文限宽 760dp 居中 |
| 设备声明 | — | `touchscreen required="false"`、`supports-screens` 覆盖 large/xlarge、`resizeableActivity` 支持分屏 |
| 屏幕方向 | — | 不加方向锁定，横竖屏自由旋转 |
| 播放器 | 横屏即全屏 | 平板横屏不强制全屏：播放器按约 3/4 屏高约束、16:9 居中，弹幕与评论区保持可见；全屏按钮改为纯布局切换 |
| 触屏反馈 | — | 视频卡片等可点击项补 `selectableItemBackground` 按压水波纹 |
| 应用图标 | 带「兼容版」横幅的绿色猫咪 | 绿色猫咪 + HD 角标，**不带「兼容版」横幅** |
| 构建工具链 | AGP 7.4.2 + Gradle 7.5 | **AGP 8.2.2 + Gradle 8.4**，显式 `buildConfig true` |
| 版本号 | 1.0.6（versionCode 6） | 与 OldLX 对齐：**1.0.6（versionCode 6）** |

## 功能

**内容浏览**
- 首页推荐流、分区筛选、排行榜、顶部在线/在看人数
- 搜索：视频 + UP 主双 Tab、历史搜索、热门标签
- UP 主个人空间：资料卡、关注/粉丝列表、视频/文章/动态

**播放与互动**
- ExoPlayer（Media3）小缓冲播放、磁盘缓存、画质切换、横屏全屏、历史进度续播
- 弹幕：Canvas 自绘（滚动/顶部/底部）、发送、字号/速度/透明度/屏蔽词设置
- 评论：分页、楼中楼回复、点赞、UP 主置顶
- 点赞 / 投币 / 收藏 / 分享 / 关注 UP 主
- 文章阅读（内置 Markdown 渲染）、音频播放（含平板限宽正文）
- 直链节点切换（handoff 授权 + 毒缓存自动清理重试）
- 私信：发送状态判定按业务 code，回执解析异常时提示"消息已发出（回执解析异常）"，不再误判为发送失败

**个人中心**
- 登录（强制邮箱验证码两步验证）/ 注册，图形验证码为自绘 SVG 渲染，不依赖 WebView
- 签到、小鱼干流水、大会员兑换、私信/通知、历史/收藏/我的稿件
- 资料编辑、修改密码
- 投稿：视频 / 音频 / 文章，自动提取媒体信息，带进度上传

**管理后台**（仅 UID 1 / 3 / 12 / 62 / 106 可见）
- 稿件审核、评论与弹幕管理、创作者申请、举报/认证/海报审核
- 站务数据、广告、商城（商品 + 订单）、用户管理、提现处理、存储管理、接入节点

## 平板上手说明

- **支持屏幕**：sw600dp（约 7 寸）及以上，横竖屏均可；手机竖屏 2 列 / 横屏 3 列，平板按尺寸自动 3–5 列
- **分屏 / 多窗口**：Manifest 已声明 `resizeableActivity="true"`，Android 7.0+ 可分屏
- **已安装验证机型**：R86（NIU_YUN_EDUCA_TION，Android 6.0.1，800×1280，160dpi）—— 仅完成安装与包信息校验，功能未逐项实测
- **字体与间距**：布局全部使用 dp/sp，无硬编码 px，平板下不会因密度换算而错位

## 安装

从 Release 下载 APK 直接安装，或：

```bash
adb install -r lingxi-classic-tablet-v1.0.6.apk
```

覆盖安装不会清除账号数据；降级安装会失败，需先卸载。

## 构建

用 Android Studio 打开本目录，或命令行：

```bash
gradlew.bat assembleRelease
```

- JDK 17、AGP 8.2.2、Gradle 8.4、Kotlin 1.9.22、compileSdk 34、minSdk 21
- 注意：仓库未包含 `gradlew` / `gradlew.bat` 包装脚本，命令行构建需本地已有 Gradle 8.4（或用 Android Studio 自带的 Gradle）
- Release 签名需自行配置 `signingConfigs`，仓库不包含签名证书

## 老设备适配

- minSdk 21，目标 1GB 内存设备
- Glide 全局 RGB_565（位图内存减半）
- 播放器小缓冲（10s/30s）、视频缓存 50–200MB 可配、启动自动修剪
- 弹幕对象池 + 同屏数量上限
- 无重型依赖（无 Material / Room），验证码不依赖 WebView

## 已知限制

- 未做双栏 / 列表-详情布局：设置、消息、管理后台仍是单列（已用限宽避免过度拉伸，但未拆分左右栏）
- 无自适应图标（adaptive icon）：Android 8.0+ 启动器会对旧版图标套用底色图章，圆角外可能露出底板
- 未在 Android 5.0/5.1 真机实测，API 21 兼容性依据静态检查（无 `java.time`、`java.util.stream` 等高版本 API）

## 技术栈

Kotlin · Retrofit/OkHttp · Gson · Glide · Media3 (ExoPlayer) · ViewBinding · 协程
