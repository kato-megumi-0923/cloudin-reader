# 发版检查清单

每次发版前逐项确认。做过一项就勾掉，并在括号里写上版本号。

## 待办（下次发版一并处理）

- [ ] README 标题改成 `# 云中阅读`，去掉 `· Cloudin Reader`，英文只保留一行简介
- [ ] GitHub 仓库 About 描述改成中文（例：本地 TXT/EPUB 阅读器，Liquid Glass 界面，无广告不联网）
- [ ] 对外展示名统一只用「云中阅读」，不出现英文名
- [ ] 应用商店名称若上架，使用「云中阅读」（英文 `CLOUDIN` 已被他人注册商标，避免使用）

## 每次发版固定步骤

1. [ ] 读代码：`git log <上个 tag>..HEAD`、`git diff --stat`，逐个看改动文件
2. [ ] 出一份「功能变化 + 风险」清单给作者确认
3. [ ] `assembleDebug` 编译通过
4. [ ] 改版本号：`versionName` 第三位两位递增，`versionCode` +1
5. [ ] 构建并复制到 `dist/cloudin-reader-<版本>.apk`
6. [ ] `git commit` + `git tag -a v<版本>` + `git push` + `git push origin v<版本>`
7. [ ] 写 release notes（Markdown），创建 GitHub Release 并上传 APK
8. [ ] 安装到平板：`adb -s <serial> install -r dist/cloudin-reader-<版本>.apk`
9. [ ] 确认 release 页面：tag、附件、说明都正确

## 版本号约定

- 格式：`主.次.NN`，第三位固定两位（`0.2.09`、`0.2.14`）
- `versionCode` 每次发版 +1

## 已知取舍

- 保留 32 位 `armeabi-v7a`：体积多约 0.7MB，换旧设备可安装
- 局域网传书不加密、无配对码：只在可信局域网使用
- 液态玻璃需 Android 13+，低版本降级为半透明色块
