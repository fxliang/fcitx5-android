# 安装

::: tip 两个构建版本
fx 提供 **fx 构建**（与上游可共存）与 **mainline 构建**（替换上游）两个版本，配合两套插件兼容关系。**多数用户选 fx 构建即可**。详细差异、共存策略与插件矩阵见 [构建版本与插件兼容性](/guide/builds-and-plugins)。
:::

::: tip 已经在用上游？
上游导出的用户数据备份可以直接导入到 fxliang —— 一次拿到所有上游里的自定义配置（Rime / 词库 / 主题 / SharedPreferences 等）。完整步骤见 [从上游迁移](/guide/migrate-from-upstream)。
:::

## 一、下载渠道

### 1. fxliang/fcitx5-android Releases（推荐）

前往 [fxliang/fcitx5-android Releases](https://github.com/fxliang/fcitx5-android/releases) 下载最新版本：

**主程序**（二选一）：

| 文件名样式 | 是哪一个 | 与上游 |
|------------|----------|--------|
| `org.fcitx.fcitx5.android.fx-<ver>.apk` | **fx 构建**（推荐） | 可共存 |
| `org.fcitx.fcitx5.android-<ver>.apk` | mainline 构建 | 互相覆盖 |

**插件**（按需，单个 APK 同时兼容两种主程序构建）：

- `org.fcitx.fcitx5.android.plugin.rime-<version>.apk` —— Rime 输入引擎
- `org.fcitx.fcitx5.android.plugin.chinese-addons-<version>.apk` —— 拼音 / 双拼等中文方案
- `org.fcitx.fcitx5.android.plugin.text_editor-<version>.apk` —— 应用内文本编辑器（fx 新增）
- 其他方案插件（五笔、仓颉、新酷音、Anthy、Hangul、Sayura、Thai、Unikey、Jyutping、Chewing、Clipboard-Filter 等）

::: tip 选择正确的 ABI
若不确定设备架构，下载文件名含 `arm64-v8a` 的版本即可（覆盖大多数现代设备）。
:::

### 2. 应用内更新检查（已安装后）

进入 **设置 → 关于 → 检查更新**，可自动从 GitHub 拉取 fxliang 仓库的最新 Release，支持镜像下载（详见 [更新检查器](/features/update-checker)）。

::: info 为什么没有 F-Droid
这是个人魔改分支，未上架 F-Droid。如需 F-Droid 渠道请使用上游版本。
:::

## 二、安装步骤

### 1. 卸载冲突版本（按构建判断）

- 安装 **fx 构建**：无需卸载上游 —— 它会作为独立应用并存
- 安装 **mainline 构建**：若已装上游版本，直接覆盖或先卸载（覆盖保留配置，卸载会清除配置）

### 2. 安装主程序 APK

在文件管理器或浏览器中点击下载的 APK。首次安装可能需要授予 **允许安装未知来源应用** 权限。

### 3. 安装所需插件

如需使用 Rime、拼音等方案，安装对应插件 APK。插件本身不可独立启动，安装后会自动被主程序识别。

::: tip 想同时使用上游 / fxliang 两种插件？
默认主程序只识别"匹配自身构建"的插件。在 **设置 → 高级 → 允许第三方 Fcitx5 插件** 中可放开。详见 [构建版本与插件兼容性 → 兼容性矩阵](/guide/builds-and-plugins#插件兼容性矩阵)。
:::

### 4. 启用输入法

进入系统 **设置 → 系统 → 语言和输入法 → 屏幕键盘 / 管理键盘**（不同 ROM 命名略有差异），找到对应的 Fcitx5 项并开启开关。

### 5. 切换为默认输入法

在任意可输入文本的位置（如短信、备忘录），调出键盘，点击键盘右下角的 **切换输入法** 按钮，选择对应的 Fcitx5。

或在系统设置中将其设为默认输入法。

## 三、权限说明

- **悬浮窗 / 显示在其他应用上层**：用于浮动键盘等
- **存储**：用于读取自定义词库、Rime 配置、导入主题/布局等
- **通知**：用于更新检查与下载进度提示
- **相机**：扫描二维码导入布局/主题/Popup 配置时使用（详见 [QR 分享](/features/theme/share-import)）

## 四、OEM 关联启动（重要）

在 **小米 (MIUI/HyperOS)、华为 (EMUI/HarmonyOS)、OPPO (ColorOS)、vivo (OriginOS)** 等国产定制 ROM 上，**必须** 手动为主程序和插件开启「关联启动 / 自启动」权限，否则插件可能无法被主程序识别。

详细操作步骤见 [OEM 关联启动](/troubleshooting/oem-startup)。

## 五、卸载与切回上游

- 卸载主程序前建议先取消其默认输入法状态
- 插件可独立卸载，不影响主程序
- 自定义配置存放于应用私有目录，**fx 构建与 mainline 构建数据不互通**；卸载时会一并删除（请先用应用内 **设置 → 高级 → 导出用户数据** 备份，详见 [从上游迁移](/guide/migrate-from-upstream)）
- 卸载后可前往 [上游 Release](https://github.com/fcitx5-android/fcitx5-android/releases) 重新安装上游版本
