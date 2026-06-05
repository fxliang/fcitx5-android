# 构建版本与插件兼容性

fx 的 Release 同时提供两个版本的主程序 APK，以及一套魔改插件。理解它们的关系可以让你在 **保留上游 / 替换上游 / 双装并用** 之间自由选择。

## 主程序：两个构建版本

| 维度 | **fx 构建**（推荐） | **mainline 构建** |
|------|------|------|
| 包名 | `org.fcitx.fcitx5.android.fx` | `org.fcitx.fcitx5.android` |
| 与上游能否共存 | ✅ 可以（包名不同） | ❌ 不能（包名相同，会互相覆盖） |
| 应用名 | Fcitx5 默认名 | Fcitx5 Mainline / Fcitx5 Mainline (debug) |
| Release 文件名 | `org.fcitx.fcitx5.android.fx-<ver>.apk` | `org.fcitx.fcitx5.android-<ver>.apk` |
| 适合场景 | 想保留上游版本，作为额外的"魔改版"双装 | 想用魔改版**完全替换**上游 |

::: tip 不知道选哪个？
**先装 fx 构建**。它不会动你已有的上游版本；如果用得满意，再决定是否卸载上游。
:::

### 怎么区分下载到的是哪个

- 文件名里 **含 `.fx`** → fx 构建
- 文件名 **不含 `.fx`**（即 `org.fcitx.fcitx5.android-<ver>.apk`） → mainline 构建

应用安装后，在 **设置 → 关于** 中也能看到当前版本来自哪一个构建。

## 插件兼容性矩阵

魔改插件（Rime、chinese-addons、五笔、仓颉、新酷音、Anthy、Hangul、Sayura、Thai、Unikey、Jyutping、Chewing、Clipboard-Filter、Text-Editor 等）的包名 **沿用上游**，例如：

```
org.fcitx.fcitx5.android.plugin.rime
org.fcitx.fcitx5.android.plugin.chinese-addons
...
```

所以同一个插件包名只能存在一份 —— **fxliang 插件与上游同名插件不能并存安装**。

### 签名才是关键

插件能否被加载，**实际由 APK 签名决定**（包名相同只是 Android 系统级要求，不代表可加载）。规则如下：

- **同签名**：主程序与插件由同一密钥签名 → 直接放行
- **不同签名**：必须主程序提供"放行口"才能加载

fxliang 主程序（fx / mainline 两种构建都用 fxliang 的同一套密钥签名）提供了 **"允许第三方 Fcitx5 插件"** 开关；**上游主程序没有这个开关**。

|                        | 上游原版插件（upstream 签名） | fxliang 魔改插件（fxliang 签名） |
|-----------------------|:------------:|:----------------:|
| **fx** 主程序（fxliang 签名）  | ✅ 需开启"允许第三方 Fcitx5 插件" | ✅ 默认 |
| **mainline** 主程序（fxliang 签名） | ✅ 需开启"允许第三方 Fcitx5 插件" | ✅ 默认 |
| **上游官方** 主程序（upstream 签名） | ✅ 默认 | ❌ 上游无放行机制，签名不匹配，无法加载 |

::: warning 上游主程序无法使用 fxliang 魔改插件
即使你看到上游主程序"识别到了" fxliang 插件，加载步骤会因签名校验失败而拒绝。这是 Android 安全模型，不是 bug。想用魔改插件，必须用 fxliang 主程序（fx 或 mainline）。
:::

### "允许第三方 Fcitx5 插件" 开关

- 路径：**设置 → 高级 → 允许第三方 Fcitx5 插件**
- 默认 **关闭**
- 启用后：主程序在加载阶段对"包名前缀在白名单内、但签名与主程序不一致"的插件放行（默认白名单前缀 `org.fcitx.fcitx5.android`，可在同页加自定义）

::: warning 不要装重复功能的插件
启用上述开关后请勿同时安装"功能相同的上游原版插件 + fxliang 魔改插件" —— 同名包根本装不上；即便靠 debug / release 不同变体绕开，候选流程会异常。
:::

## 常见组合策略

### 策略 A：双装（推荐新手）

- 主程序：fx 构建（与上游并存）
- 插件：用 fxliang Release 的全套魔改插件
- 体验完整魔改特性，但保留上游主程序随时回退

### 策略 B：替换上游

- 卸载上游主程序与插件
- 装 mainline 构建 + fxliang 魔改插件
- 应用图标、包名都是"上游同款"，对其他依赖此包名的应用 / 自动化脚本透明

### 策略 C：fx + 上游插件

- 主程序：fx 构建
- 插件：来自上游
- 开启 "允许第三方 Fcitx5 插件"
- 适合：想要魔改的主程序功能（编辑器、QR 分享等），但坚持上游官方的方案插件

::: tip
策略 A 与策略 C 都能让你 **同一台手机同时拥有两份键盘**（fx + 保留的上游），根据使用场景在系统输入法切换里选用。
:::

::: info 为什么没有"上游主程序 + fxliang 魔改插件"策略
上游主程序无放行开关，会因签名不匹配拒绝 fxliang 签名的插件 —— 详见上方[签名校验规则](#签名才是关键)。
:::

## 切回上游 / 卸载

- **fx 构建**：作为独立包卸载，不影响上游
- **mainline 构建**：卸载后从 [上游 Release](https://github.com/fcitx5-android/fcitx5-android/releases) 重装即可
- 用户数据（自定义词库、Rime 配置、布局、主题等）存放在 **各自的应用私有目录**：
  - fx 构建 ↔ `org.fcitx.fcitx5.android.fx`
  - mainline 构建 ↔ `org.fcitx.fcitx5.android`
  - 两者数据 **不共享**；切换 / 双装时通过 [备份与导入](/guide/migrate-from-upstream) 在两端来回搬

## 用户数据迁移

上游、fx 构建、mainline 构建三者的备份格式互通 —— 同一份 zip 备份可在任意一端导出、任意一端导入。完整流程与注意事项见 [从上游迁移](/guide/migrate-from-upstream)。

## 相关页面

- [安装](/guide/installation)
- [反馈问题](/troubleshooting/report-issue)
- [关于：致谢与差异说明](/about/credits)
