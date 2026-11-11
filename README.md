# 云中阅读

> 本地 TXT / EPUB 阅读器，Liquid Glass 界面。无广告、无账号、不联网。
>
> *A local TXT / EPUB reader with a Liquid Glass interface. No ads, no accounts, no tracking.*

![Platform](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white)
![AGSL](https://img.shields.io/badge/AGSL-Android%2013%2B-4B8BBE)
![Version](https://img.shields.io/badge/version-0.2.19-9C5015)

一个给自己写的阅读器：书架、分页、翻页动画、阅读统计全部本地完成，界面按 iOS 26 的「液态玻璃」风格重做过一轮。

---

## 特性

### 书架

- 网格 / 列表两种布局，网格列数随屏幕宽度自适应（平板 8 列）
- 分组：手动新建，或按目录导入时自动按子目录建组，组内只含本层书
- 置顶、喜爱、长按多选、拖选批量选择
- 封面提取与缩略图缓存，封面书名保留，角标颜色随封面明暗自适应
- 滚动时标题淡出，书籍从顶栏下方穿过

### 阅读

- 自研分页引擎：`StaticLayout` 测量 + `PageBreaker` 断页，支持 EPUB 内嵌图片
- 排版：单列 / 双列，字号、行距、边距、对齐、字体族可调
- 翻页方式：无 / 仿真卷页 / 左右 / 上下 / 滚屏
- 阅读背景：10 种纸张（牛皮纸、雅白、夜间、雅黑、青瓷、木纹、梅子、亚麻、纯白、墨绿），带纹理预览页，可全局切换
- 自动阅读、音量键翻页、亮度调节（可跟随系统）
- 章节标题与底部进度可单独开关，进度按字符偏移精确恢复
- EPUB 插图点击可全屏查看、缩放、保存到相册
- 章节目录、进度条、百分比、剩余阅读时间
- 阅读时长统计与阅读记录

### 搜索

- 按书名 / 作者搜索，带历史记录与一键清除
- 结果网格列数与书架一致

### 我的

- 最近阅读、累计阅读时长
- 阅读统计：按小时分桶的柱状图、进度条
- 设置：背景、翻页、布局、玻璃材质

### 局域网传书

不用数据线，电脑浏览器直接把 TXT / EPUB 传进平板：

1. 书架「＋」→「局域网传书」
2. 选一个本地保存目录（SAF）
3. 电脑浏览器打开弹窗里显示的地址（默认 `http://<平板IP>:8787`）
4. 拖拽或选择文件上传，完成后点「停止接收」

文件会写入所选目录并自动归入同名分组；同名同内容会跳过，同名不同内容会新建。

### 液态玻璃

- 基于 `RenderNode` + `RuntimeShader`（AGSL）的实时折射：圆角 SDF、边缘折射、RGB 色散、边缘高光
- 书架底栏是自研 `ShelfTabBar`：把整行 tab 画进位图、按强调色染色，再用独立着色器（`shelf_tab_lens.agsl`）做光学放大
- 两档玻璃材质：**液态**（折射）/ **磨砂**（模糊），在「我的 → 设置」里切换
- Android 13 以下自动退化为半透明色块，不会崩

---

## 技术栈

| 层 | 使用 |
|---|---|
| 语言 | Kotlin |
| 基础 | AppCompat + Material Components 1.11 |
| 列表 | RecyclerView + 自定义 Adapter |
| 文本排版 | `StaticLayout` + 自研分页 |
| 玻璃 | AGSL `RuntimeShader` + `RenderNode` + 第三方 `liquidglass` |
| 存储 | SharedPreferences + 应用私有目录 JSON |
| 网络 | 仅局域网传书用的 `ServerSocket`，无第三方网络库 |

最低支持 **Android 8.0（API 26）**，编译 / 目标 **API 35**。

---

## 构建

需要：

- JDK 17
- Android SDK 35（含 build-tools）
- Gradle 8.9

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/Android/Sdk

gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 仓库未包含 Gradle Wrapper，可直接用本机 Gradle 8.9；也可以先 `gradle wrapper` 生成 wrapper 再构建。

---

## 目录结构

```
app/src/main/java/com/cloudin/reader/
├── MainActivity.kt          书架、分组、我的、选择模式
├── ReaderActivity.kt        阅读页、设置面板、目录
├── SearchActivity.kt        搜索与历史
├── ReadingStatsActivity.kt  阅读统计
├── ReadingHistoryActivity.kt 阅读记录
├── PageView.kt              分页渲染与翻页手势
├── PageBreaker.kt           分页算法
├── PageCurl.kt              仿真卷页
├── PageMotion.kt            翻页动画
├── ShelfTabBar.kt           自研底栏 + 光学透镜
├── AgslGlassView.kt         AGSL 玻璃容器
├── GlassActionButton.kt     玻璃按钮 + 统一按压动效
├── GlassStyle.kt            液态 / 磨砂材质切换
├── BookStore.kt             书籍存储与导入
├── EpubParser.kt            EPUB 解析
├── TextCodec.kt             TXT 编码探测
├── WifiBookTransfer.kt      局域网传书
└── ...                      封面、纹理、统计图表等
```

---

## 权限

| 权限 | 用途 |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | 从本地目录导入书籍、写入传书文件 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 仅用于局域网传书 |

应用不请求账号、不接入统计 SDK、不上传任何数据。局域网传书只在同一局域网内监听，关闭弹窗即释放端口。

> 传书走的是明文 HTTP，且没有配对码；请只在可信网络（家里、个人热点）使用。

---

## 版本历史

| 版本 | 主要变化 |
|---|---|
| 0.2.19 | 165 张内置封面按最少使用分配并持久化；封面加渐变遮罩与书名；缩略图缓存支持资源封面；设置面板切换加高度与滑动动画（APK 增至约 16 MB） |
| 0.2.18 | 统计 / 记录 / 搜索 / 阅读背景 / 插图页统一极光背景与系统栏；配色收敛到公共色值；阅读记录改用统一分段控件 |
| 0.2.17 | 重做「关于」卡片：版本徽章、项目主页与版本历史入口；显示版本号与构建号 |
| 0.2.16 | 阅读器拆分为分页 / 滚动 / 翻页动画 / 分页缓存四个模块；分页结果落盘复用；段落整体切分改善断行；自动阅读改为帧回调；关于页显示版本号与构建号 |
| 0.2.15 | 阅读背景扩展到 10 种纸张并新增背景预览页；排版支持双列、上下/滚屏翻页；自动阅读、音量键翻页、亮度调节；EPUB 插图全屏查看与保存；进度按字符偏移精确恢复 |
| 0.2.14 | 新增局域网传书；阅读进度并入设置面板；EPUB 解析加固 |
| 0.2.13 | 重写书架底栏（自研光学透镜）；新增玻璃按钮；书架页头沉浸化 |
| 0.2.12 | 底栏长按浮起透镜，轻点直接切换 |
| 0.2.08 | 阅读页顶栏 / 底栏 / 设置面板改用 AGSL 折射玻璃 |
| 0.2.06 | 对话框改用 RenderNode + AGSL |
| 0.2.0 | 封面保留书名，去掉格式角标 |
| 0.1.95 | 新增「我的」页面、阅读统计、阅读记录 |
| 0.1.86 | 早期版本 |

完整记录见 [Releases](https://github.com/kato-megumi-0923/cloudin-reader/releases)。

---

## 已知限制

- 液态玻璃需要 Android 13（API 33）及以上；低版本为半透明色块
- AGSL 折射在低端 GPU 上可能掉帧，可切到「磨砂」材质
- 界面优先适配平板（2400×3392 @420dpi），手机上会按宽度调整列数
- 局域网传书为明文 HTTP，未做鉴权

---

## 致谢

- [AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView)（MIT）：AGSL 玻璃着色器的基础
- [liquidglass](https://github.com/QWEA0/liquid-glass-android)：磨砂材质与玻璃控件
- Material Symbols Rounded：界面图标

---

## License

本项目暂未指定开源协议，代码与设计归作者所有。第三方依赖遵循其各自协议。
