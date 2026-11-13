# ImageToolbox-ImgBed-LSPosed

适用于 **Image Toolbox（图像工具箱，包名 `ru.tech.imageresizershrinker`）** 的轻量级 LSPosed 模块。

## 🌟 功能特性

- **保存自动拦截并弹窗选择**：在 Image Toolbox 点击原版“保存”图标、图片落盘完成瞬间，自动弹出原生选择对话框：
  - **「正常保存」**：仅保留在本地相册，不产生任何网络请求。
  - **「保存并上传图床」**：后台静默上传至自定义 Cloudflare ImgBed 图床，上传成功后自动生成时间戳 Markdown 链接并写入剪贴板，同时弹出 Toast 提示。
  - **「⚙️ 配置」**：支持随时在手机弹窗内自定义填写/修改你的私有图床域名与可选的 AuthCode 认证密码。
- **隐私安全（零硬编码）**：代码与编译生成的 APK **不包含任何个人网址或密钥**，所有图床参数均保存在手机本地应用私有存储中，完全脱敏，安全公开托管于 GitHub。
- **抗版本更新**：通过 Android 系统底层写入流关闭事件（`openOutputStream.close`）与媒体库落盘信号双重捕获，完美兼容系统相册保存与 SAF 自定义文件夹保存，应用后续升级亦不失效。
- **零额外体积**：纯原生 Android API 构建，APK 仅数十 KB，且自动通过调试密钥签名，下载后可直接安装。

---

## 🚀 部署至 GitHub Actions 自动编译

本项目已配置完整的 GitHub Actions CI 流水线（[`.github/workflows/build.yml`](.github/workflows/build.yml)）。将代码推送到你的 GitHub 仓库后，Actions 会全自动编译生成 Release 与 Debug 版本的 APK。

### 方式 1：通过 Git 命令行推送（推荐）

```bash
cd E:\github\ImageToolboxImgBed
git add .
git commit -m "feat: make imgbed url and authCode dynamically configurable in UI"
git push origin main
```

### 方式 2：通过 GitHub 网页端上传

在 GitHub 网页仓库中，直接将修改后的文件上传并覆盖提交到主分支。

---

## ⚙️ 手机端使用与图床配置

1. 在 GitHub Actions 下载编译好的 APK 并安装到手机。
2. 打开 **LSPosed 管理器** -> 切换到「模块」标签 -> 启用本模块，并确保勾选了 **Image Toolbox**（`ru.tech.imageresizershrinker`）。
3. **强行停止（Force Stop）一次 Image Toolbox**。
4. 打开 Image Toolbox 编辑任意图片并点击**保存**：
   - 屏幕弹出对话框，底部包含三个选项：`[ ⚙️ 配置 ]`、`[ 正常保存 ]`、`[ 保存并上传图床 ]`。
   - 点击 **「⚙️ 配置」**，在输入框中填入你的私有图床域名（例如 `https://your-imgbed.pages.dev`）以及上传密码（选填），点击确定保存。
   - 配置仅需填写一次，保存在手机本地，重启应用不丢失。
   - 配置完成后，点击 **「保存并上传图床」** 即可一键上传并复制 Markdown 链接！
