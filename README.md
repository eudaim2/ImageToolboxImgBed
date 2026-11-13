# ImageToolbox-ImgBed-LSPosed

适用于 **Image Toolbox（图像工具箱，包名 `ru.tech.imageresizershrinker`）** 的轻量级 LSPosed 模块。

## 🌟 功能特性

- **保存自动拦截并弹窗选择**：在 Image Toolbox 点击原版“保存”图标、图片落盘完成瞬间，自动弹出原生选择对话框：
  - **「正常保存」**：仅保留在本地相册，不产生任何网络请求。
  - **「保存并上传图床」**：后台静默上传至 Cloudflare ImgBed 图床，上传成功后自动生成当前时间戳的 Markdown 链接（例如 `![20261113_150812](https://imgbed1026.pages.dev/file/...)`）并写入剪贴板，同时弹出 Toast 提示。
- **抗版本更新**：Hook Android 系统框架层 `ContentResolver.update` 媒体库落盘信号（`IS_PENDING` 置 0），无需 Hook Image Toolbox 内部混淆的 Jetpack Compose 类，应用后续升级亦不失效。
- **零额外体积与零多余依赖**：纯原生 API 实现，生成的 APK 仅数十 KB，且在 Release 模式下自动通过调试密钥签名，构建完成可直接安装。

---

## 🚀 部署至 GitHub Actions 自动编译

本项目已配置完整的 GitHub Actions CI 流水线（[`.github/workflows/build.yml`](.github/workflows/build.yml)）。你只需将本项目代码推送到 GitHub，GitHub 会全自动编译生成 Release 与 Debug 版本的 APK。

### 方式 1：通过 Git 命令行推送（推荐）

在当前项目目录 `ImageToolboxImgBed` 下执行：

```bash
# 1. 初始化并提交代码（如果尚未提交）
git init
git add .
git commit -m "feat: init ImageToolbox ImgBed LSPosed module"
git branch -M main

# 2. 关联你的 GitHub 仓库（请替换为你在 GitHub 上新建的仓库地址）
git remote add origin https://github.com/<你的用户名>/<你的仓库名>.git

# 3. 推送至 GitHub
git push -u origin main
```

### 方式 2：通过 GitHub 网页端上传

1. 在 GitHub 上新建一个空仓库。
2. 将 `ImageToolboxImgBed` 文件夹内的所有文件（含隐藏目录 `.github`）上传或拖拽提交到仓库主分支。

---

## 📦 如何下载生成的 APK

1. 打开你的 GitHub 仓库页面，点击上方的 **Actions** 选项卡。
2. 点击最新触发的 **Build LSPosed Module APK** 工作流。
3. 待工作流运行完毕（约 1~2 分钟，显示绿色对勾），页面下方的 **Artifacts** 区域即可直接下载：
   - `ImageToolbox-ImgBed-Release.zip`（内含已签名的 `app-release.apk`）
   - `ImageToolbox-ImgBed-Debug.zip`（内含 `app-debug.apk`）
4. 解压 zip 包后直接传至手机安装即可。

> **小提示**：如果你给提交打上 Release Tag（如 `git tag v1.0.0 && git push origin v1.0.0`），GitHub Actions 还会在 Releases 页面直接发布包含 APK 下载的 Release。

---

## 📱 手机端启用与配置说明

1. 手机上安装编译好的 APK。
2. 打开 **LSPosed 管理器** -> 切换到「模块」标签。
3. 找到并勾选启用 **ImageToolbox ImgBed Hook**。
4. 确认作用域中已自动勾选 **Image Toolbox**（`ru.tech.imageresizershrinker`）。
5. 强行停止（Force Stop）一次 Image Toolbox，再次打开编辑图片并点击保存即可生效。

### 自定义图床地址或上传密钥

如果图床后台配置了 `authCode` 认证密码，或者需要更换为自己的图床域名，只需编辑 [`app/src/main/java/com/local/imgbedhook/HookEntry.kt`](app/src/main/java/com/local/imgbedhook/HookEntry.kt)：

```kotlin
private const val BASE_URL = "https://imgbed1026.pages.dev"
// 若后台开启了上传密码，改为: "$BASE_URL/upload?authCode=你的密码"
private const val UPLOAD_URL = "$BASE_URL/upload"
```
提交并推送到 GitHub 即可自动重新编译。
