# refract

从云中阅读抽出的 View 版液态玻璃组件：把背景 View 录进 `RenderNode`，用 AGSL（`RuntimeShader`）
做圆角 SDF 折射 + 色散 + 边缘高光 + 模糊，再把结果画在自己下面、子 View 画在上面。

无 Compose 依赖，只要求 `minSdk 26`；Android 13（`TIRAMISU`）以下自动退化成半透明圆角色块。

## 引入

同仓库模块：

```kotlin
implementation(project(":refract"))
```

已发布到本地 Maven（`publishToMavenLocal`）：

```kotlin
implementation("com.github.cloudin:refract:1.0.0")
```

## 用法

```kotlin
val glass = GlassView(context).apply {
    cornerRadius = dp(28f)
    refractionHeight = dp(7f)     // 折射带高度，越大边缘越"厚"
    refractionAmount = dp(9f)     // 折射强度
    blurRadius = dp(6f)
    dispersion = 0.14f            // 色散（彩边）
    edgeHighlight = 1.1f
    tintColor = Color.WHITE
    tintAlpha = 0.10f
    backdropSource = backgroundView   // 关键：必须是不包含自己的背景层
}
container.addView(glass, FrameLayout.LayoutParams(width, height))
```

两个预设（和云中阅读的按钮/弹窗观感一致）：

- `useControlOptics(radiusDp)`：小控件，折射 7/9dp、模糊 9dp、染色 0.24
- `usePopupOptics()`：弹窗卡片，压平折射并关掉边缘高光

磨砂：`glass.frosted = true`（内部把模糊提到 ≥13dp、关掉折射）。

## 必须注意

- **`backdropSource` 不能落在「包含这个 GlassView 自己的子树」里**，否则 `RenderNode` 递归，
  渲染时原生栈溢出、进程直接死（logcat 只有 `has died`，没有 Java 栈）。浮层要么用独立 Dialog 窗口，
  要么另给一层不包含自己的背景 View。
- 兜底行为：**没有设置 `backdropSource`、或设的源是自己的祖先**（父容器通常就包含玻璃自身）时，
  不会录制背景，直接画半透明底色——观感是"淡色玻璃块"，但不会崩。所以想看到真实折射就必须显式给
  一个不包含自己的背景层。
- 纯色背景下玻璃只能得到"同色微亮块"——没有细节可折射，这是物理限制，不是参数没调好。
- 每帧都会重新录背景，静态页面也可以考虑按需调用 `invalidate()`。
