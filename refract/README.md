# refract

View 版折射/液态玻璃组件。把背景 View 录进 `RenderNode`，用 AGSL（`RuntimeShader`）做圆角 SDF 折射 +
色散 + 边缘高光 + 模糊，再把结果画在自己下面、子 View 画在上面。

纯 View，没有 Compose 依赖；Android 13 以下自动降级成半透明圆角色块，不会崩。

---

## 一、它能做什么

| 效果 | 参数 | 说明 |
|---|---|---|
| 折射 | `refractionHeight` / `refractionAmount` | 边缘一圈把背景"推弯"，越大越像厚玻璃 |
| 色散（彩边） | `dispersion` | 红绿蓝分离，玻璃边缘的彩虹感 |
| 边缘高光 | `edgeHighlight` | 上缘提亮、下缘压暗，制造体积 |
| 模糊 | `blurRadius` | 独立于折射的后置模糊 |
| 染色 | `tintColor` / `tintAlpha` | 给玻璃罩一层色（白色提亮、深色压暗） |
| 磨砂 | `frosted` | 强模糊 + 关掉折射，iOS 那种毛玻璃 |

**它做不到的**：在纯色背景上折射出细节。背景本身没有纹理，玻璃只能得到一块"同色微亮块"——这是物理限制，
不是参数没调好。想要玻璃感，背景得有渐变、照片或文字。

---

## 二、环境要求

| 项 | 值 |
|---|---|
| minSdk | 26 |
| compileSdk | 35 |
| JDK / jvmTarget | 17 |
| 真实玻璃效果 | **Android 13（API 33 / TIRAMISU）及以上** |
| 依赖 | 只有 `androidx.annotation` |

API 33 以下 `isShaderSupported` 为 false，`dispatchDraw` 走半透明圆角兜底，观感是"淡色玻璃块"。

---

## 三、引入

同仓库模块：

```kotlin
implementation(project(":refract"))
```

发布到本地 Maven 后（`./gradlew :refract:publishToMavenLocal`），产物在
`~/.m2/repository/com/github/cloudin/refract/1.0.0/`：

```kotlin
implementation("com.github.cloudin:refract:1.0.0")
```

> 发布坐标与版本号写在 `refract/build.gradle.kts` 的 `publishing` 块里。
> 要发到远程仓库时加 `repositories { maven { url = ... } }` 与凭据即可，模块本身不用改。

---

## 四、最快上手

关键只有一句：**给玻璃一个「不包含它自己」的背景 View**。所以布局上背景层和玻璃必须是兄弟节点。

```xml
<FrameLayout android:id="@+id/container" ...>

    <!-- 1) 背景层：图片 / 渐变 / 任意内容 -->
    <ImageView
        android:id="@+id/backdrop"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:scaleType="centerCrop" />

    <!-- 2) 玻璃：盖在背景上 -->
    <com.cloudin.refract.GlassView
        android:id="@+id/glass"
        android:layout_width="280dp"
        android:layout_height="120dp"
        android:layout_gravity="center" />

</FrameLayout>
```

```kotlin
val glass = findViewById<GlassView>(R.id.glass)
glass.cornerRadius = dp(24f)
glass.backdropSource = findViewById(R.id.backdrop)   // 必须：不包含 glass 自己的那一层
```

玻璃里的内容用普通子 View 加进去即可，它们会画在玻璃之上：

```kotlin
glass.addView(TextView(context).apply { text = "玻璃卡片" })
```

### 小控件（按钮）

```kotlin
val button = GlassView(context).apply {
    useControlOptics(radiusDp = 28f)     // 折射 7/9dp、模糊 9dp、染色 0.24
    tintColor = Color.WHITE
    backdropSource = backgroundView
}
```

### 弹窗卡片

```kotlin
val card = GlassView(context).apply {
    usePopupOptics()                     // 压平折射、关掉边缘高光
    cornerRadius = dp(26f)
    tintAlpha = 0.10f
    backdropSource = dialogBackdrop      // 必须是这个 window 里不含自己的背景
}
```

---

## 五、API

### GlassView 属性

| 属性 | 默认值 | 说明 |
|---|---|---|
| `cornerRadius` | 24dp | 圆角，内部按 `min(w,h)/2` 收敛 |
| `refractionHeight` | 18dp | 折射带高度（边缘"厚度"） |
| `refractionAmount` | 26dp | 折射位移强度 |
| `dispersion` | 0.14 | 色散强度，0 = 无色边 |
| `edgeHighlight` | 1.1 | 边缘高光，0 = 关闭 |
| `blurRadius` | 6dp | 后置模糊半径 |
| `frosted` | false | 磨砂：模糊至少 13dp、折射归零 |
| `tintColor` | `Color.WHITE` | 玻璃染色 |
| `tintAlpha` | 0.10 | 染色不透明度（0..1） |
| `backdropSource` | null | 背景源，见下方"坑" |

> 默认值是构造时按 dp 换算好的 **px**。**赋值时也按 px**，不是 dp——
> 传 `24f` 会被当成 24 像素。请乘 `resources.displayMetrics.density`，
> 或者用接收 dp 的预设方法 `useControlOptics(radiusDp)`。

### 只读 / 可覆写

| 成员 | 说明 |
|---|---|
| `isShaderSupported: Boolean` | 当前设备是否能跑 AGSL（API 33+ 且着色器编译成功） |
| `protected open backdropPaddingPx: Int` | 采样范围向外扩出的像素。子类可覆写，让折射在容器外也能采到内容（按钮类常用 20dp） |

```kotlin
private class RoundButton(context: Context) : GlassView(context) {
    // 折射和模糊要采到按钮轮廓之外
    override val backdropPaddingPx: Int
        get() = (20 * resources.displayMetrics.density).toInt()
}
```

### 预设方法

| 方法 | 效果 | 适用 |
|---|---|---|
| `useControlOptics(radiusDp: Float)` | 折射 7/9dp、模糊 9dp、`tintAlpha` 0.24 | 圆形/胶囊按钮 |
| `usePopupOptics()` | 折射 3/2dp、`edgeHighlight` 0 | 弹窗、菜单卡片 |

---

## 六、进阶：着色器参数

`res/raw/agsl_glass.agsl` 里可调的 uniform（`GlassView` 目前用固定值，改这里能整体调风格）：

| uniform | 当前值 | 作用 |
|---|---|---|
| `refractionAxis` | `(1, 0.72)` | 折射方向权重，偏 Y 一点更像从上往下看 |
| `isDark` | 由 `tintColor` 亮度决定 | 深色染色时翻转高光/阴影方向 |
| `lr` / `ld` | 0.5 / 0.10 | 左缘高光强度与衰减 |
| `dr` / `dd` | 0.55 / 0.06 | 右缘高光强度与衰减 |
| `depthEffect` | 0.35 | 边缘厚度感 |
| `edgeDispersion` | 1 | 色散只出现在边缘（0 = 全场） |
| `contrast` / `whitePoint` | 0.02 / 0.03 | 玻璃区域的对比与白点修正 |
| `chromaMultiplier` | 1.05 | 饱和度微调 |

`isDark` 用的是 **sRGB 线性化**后的相对亮度（阈值 0.22），和 `ColorUtils.calculateLuminance` 一致。
不要换成 gamma 后的 RGB 加权平均——同一个 `#757575` 会从深色分支跳到浅色分支，高光/阴影方向就反了。

---

## 七、必须知道的坑

### 1. `backdropSource` 不能包含玻璃自己（会让进程直接死）

背景源如果是玻璃的祖先（包括父容器，也包括玻璃自身），录制时会把自己的 `RenderNode` 画进录制目标，
轻则错位，重则无限递归 → 原生栈溢出，logcat 只有一行 `has died`，没有 Java 堆栈。

库里有兜底：检测到源是自己的祖先时置 `captureBlocked`，不再录制，改画半透明底色。所以不会崩，
但也**看不到折射**。想看到真实效果就必须显式给一个不含自己的背景层。

```kotlin
// ✗ 父容器通常包含玻璃自己
glass.backdropSource = glass.parent as View

// ✓ 同级的背景层
glass.backdropSource = findViewById(R.id.backdrop)
```

### 2. 背景层尺寸为 0 时不会录

`width`/`height` 为 0 会跳过录制，等布局完成后的下一帧自动补上。若背景层一直是 0，
玻璃会一直显示兜底底色——检查背景层是否 `match_parent` 或确实有内容。

### 3. Dialog / PopupWindow 里要额外小心

独立 window 是另一套坐标，库内部用 `getLocationOnScreen` 对齐，但前提是背景源**在同一个 window 里**。
跨 window 取源会采到错位区域（表现为露黑块）。浮层要么放在独立 Dialog 里并自备一层背景，
要么改画实心卡片——云中阅读的二次确认弹窗就是直接放弃玻璃、用实心圆角卡片。

### 4. 纯色背景没有玻璃感

见"它能做什么"末尾。要么给背景加极淡的渐变/噪点，要么接受"同色微亮块"。

### 5. `frosted = true` 会关掉折射

磨砂和折射是互斥的两种观感：磨砂时 `refractionHeight` / `refractionAmount` 强制传 0，模糊至少 13dp。
两个都想要就自己调 `blurRadius` 而不开 `frosted`。

### 6. 别给 GlassView 设 `background`

绘制顺序是 `background → dispatchDraw（node + 子 View）`，玻璃会盖在你的背景之上，
所以 `background` 基本看不见，只是白画一层。需要底色就调 `tintColor` / `tintAlpha`。
`foreground` 在最后绘制，可以拿来描边，但要用只 `setStroke`、不 `setColor` 的
`GradientDrawable`，否则会整块盖住玻璃。

---

## 八、性能

- **每帧重新录制背景**：`OnPreDrawListener` 里 `record()`，玻璃越多、背景越复杂越吃 GPU。
  一屏 3~5 个玻璃控件没问题，几十个就别了。
- 静态页面不需要动画时，避免在 `onPreDraw` 里做额外工作；库本身只在 `needsUpdate` 时重设 uniform。
- `blurRadius` 是链式 `RenderEffect`（shader → blur），模糊半径大时开销明显。
- 列表项里别放玻璃：复用 + 每帧录制互相拖累，列表项用实心/半透明纯色更快。

---

## 九、云中阅读里的用法

| 位置 | 用法 |
|---|---|
| 顶栏圆形操作按钮 `GlassActionButton` | 继承 `GlassView`，`useControlOptics(28f)`，覆写 `backdropPaddingPx = 20dp` |
| 弹窗/菜单卡片 | `usePopupOptics()` + `cornerRadius 26dp` |
| 底部导航 `ShelfTabBar` | 单个 `GlassView` 承担液态与磨砂（`frosted` 切换）+ 一层自绘"选中透镜"，`backdropSource` 指向页面背景层 |
| 皮肤预览页 | 预览书架自己就是背景，玻璃必须指向它的兄弟层 |

底栏的"磨砂"档不是另一套控件，就是把 `frosted` 打开：折射归零、模糊抬到 ≥13dp。
纯色背景上它只能得到一块略亮的同色胶囊（没有内容可模糊），这是正常的。

深色皮肤下还需配合换色：玻璃染色、子 View 文字、图标 tint 一起翻转，否则深底上会出现黑图标
（`SkinTheme.applyTo` 那套，不在本模块内）。

---

## 十、出处与许可

- 折射算法与着色器基线来自 [AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView)（MIT）。
- 本模块代码随云中阅读项目，暂未单独声明开源协议。
