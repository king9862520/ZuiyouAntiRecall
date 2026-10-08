# 最右 7.3.19.2 私聊撤回 - 定位工程（尚非防撤回成品）

本工程包含 LSPosed 调试模块源代码，目标包名 `cn.xiaochuankeji.tieba`。

## APK 静态检查发现
- `classes7.dex` 中，`cn.xiaochuankeji.tieba.ui.chat.ChatActivity` 定义了 `chatRevoke` 方法。
- `classes7.dex` 中存在 `cn.xiaochuankeji.tieba.push.event.ChatRevokeEvent` 类名。
- `classes8.dex` 中存在 `cn.xiaochuankeji.tieba.ui.chat.holder.ChatRevokeHolder`。
- `classes8.dex` 中存在 `/chat/recall_message` 字符串。
- `classes8.dex` 中，`cn.xiaochuankeji.tieba.push.api.ChatSyncService` 有 `revoke` 方法。

## 当前作用
hook `ChatActivity.chatRevoke`，在 LSPosed 日志中记录是否调用，不记录聊天内容，不修改其功能。**不提供防撤回效果保证**。

## 构建
在装有 Android SDK 和 JDK 17 的 Android Studio 中打开工程，联网同步 Gradle 后运行 `gradle assembleDebug`（或点击 Build APK）。本环境缺少 Android SDK/Gradle，未能生成 APK。

## 启用
安装调试 APK，LSPosed 中启用模块，仅勾选“最右”，重启最右进程。在另一测试账号上撤回已发出的私聊文字，检查 LSPosed 日志 `ZuiyouAntiRecall`。测试时注意双方隐私及平台规则。

## 下一步
根据真实调用记录及进一步反编译代码，查找入站撤回事件和本地消息持久化修改点；需要在修改数据前验证逻辑，并加上可配置的防撤回开关与降级机制。当前不能武断地跳过 `ChatActivity.chatRevoke`，以免只隐藏提示却仍删除原文。


## GitHub Actions 云端编译
把项目文件（包括 `.github/workflows/build.yml`）上传到仓库根目录，打开 Actions，选择 Build LSPosed diagnostic APK → Run workflow。构建成功后在该次运行的 Artifacts 中下载 ZIP，解压可得 app-debug.apk。也可推送到 main/master 自动编译。当前版本仅记录撤回事件，不阻止撤回。
