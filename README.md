# HiDisk Hook — Honor 设备上的华为云空间修复与同步桥接

<p align="center"><img src="docs/images/icon.png" width="160" alt="HiDisk Hook icon"></p>

> **免责声明：本项目仅供学习、研究与个人设备自用。**
>
> 使用本项目可能违反华为/荣耀的服务条款，可能导致账号异常、云数据不同步、应用升级后失效、系统不稳定或数据丢失。请在操作前完整备份，并在充分理解风险后自行决定是否使用。作者不提供任何形式的担保，也不对任何直接或间接后果承担责任。请勿将本项目用于商业用途、绕过付费服务、侵犯他人隐私或其它违法用途。

## 项目简介

本项目针对 **Honor（荣耀）MagicOS 设备安装华为云空间（`com.huawei.hidisk`）后出现的兼容问题**，提供一套可逆的 LSPosed 模块与一个极小的 KernelSU 辅助模块。

测试环境为 Honor REP-AN00 / MagicOS 9.0 / Android 15，华为云空间 `17.0.1.701`。不同 ROM、应用版本、HMS Core 版本可能需要调整 hook 点；升级后请先看 LSPosed 日志。

## 解决/增强的内容

| 模块 | 问题 | 处理方式 |
| --- | --- | --- |
| `d.h2` / `d.i2` | HMS Core 以普通用户 APK 安装，证书与 hidisk 不同，`is not Target App or System App`，欢迎页显示“无法连接服务器” | 仅对 `com.huawei.hwid` / `com.huawei.hwid.api.provider` 放行 provider 信任检查 |
| `d.j1` / `d.g1` | Honor ROM 缺 `com.huawei.android.fsm.HwFoldScreenManagerEx`，进入主页时 `InflateException` / 黑屏 | 非折叠屏固定返回 `false` |
| UI 兼容类 | Honor ROM 缺 `huawei.android.widget.ListView` / `HwTextView`，右上角四个点无响应、“管理空间”黑屏 | 注入两个兼容类，并替换菜单里的 Honor ListView |
| 图库上传 | 相册级 `dirty=0`，Gallery 不把本地媒体交给上传队列 | hook `getGeneralAlbums` 的相册 SQL 与 `needSyncUploadGeneral()` |
| 图库下载/缩略图 | `.photoShare` 属于 MediaProvider，hidisk 直接读写被 EACCES/EPERM 拒绝 | KernelSU 伴随模块开机设置 `MANAGE_EXTERNAL_STORAGE=allow`，保持原缓存路径 |
| 联系人 | Honor Contacts 只走 Honor Cloud；hidisk 只认 `com.android.huawei.phone`，被 OEM 同步能力短路 | 停用 `com.hihonor.contacts.sync`，hook OEM 检测与账户类型，桥接到华为云 |
| 日历 | `CalendarProvider` 用 GTA vendor 常量选日历，Honor 上退化成 `com.android..phone`，漏掉真实日历；`SyncLogicService` 又因调用包为空拒绝 `startSync` | hook 本地日历选择，补 `realPkgName`，仅对 calendar provider 放行 `DriveSyncUtil.k` |

> 说明：这不是“替换系统云服务”。联系人/日历桥接是在 Honor 应用与华为云 SDK 之间做兼容层；备忘录（Notepad）因 `signature|privileged` 权限门槛暂未实现。

## 仓库组成

```text
hidisk-hook/        LSPosed 模块源码（Android 项目，非 Gradle 构建）
hidisk-appops/      KernelSU 伴随模块源码（只做一条 AppOps）
libs/               Xposed API stub（构建用）
release/            发布用 APK 与 KernelSU zip
docs/analysis/      分析记录（连接失败、黑屏、图库、联系人、日历等）
docs/images/        图标
```

## 适用环境

- Honor / MagicOS 9，Android 15（测试机 REP-AN00）；
- 已安装并激活 **LSPosed**（测试版本 2.2.1，API 102）；
- 已有 **KernelSU** 或可用的 root（控制台与 AppOps 伴随模块需要）；
- 已安装 **HMS Core**（`com.huawei.hwid`）并登录华为账号；
- 华为云空间、荣耀图库、荣耀日历存储均在设备上。

不保证适用于华为原厂 ROM、其它 MagicOS 版本、折叠屏或后续应用版本。

## 安装

### 1. 备份

至少备份：

- 联系人（导出 vCard）；
- 日历（导出 .ics）；
- 图库原始照片；
- 如准备修改数据库，先备份 `/data/user/0/...` 对应 DB。

### 2. 安装 LSPosed 模块

```bash
adb install -r HiDiskHook-v1.0.0.apk
```

在 LSPosed 管理器中：

1. 启用 **HiDisk Hook**；
2. 作用域勾选：
   - **华为云空间**（`com.huawei.hidisk`）
   - **图库**（`com.hihonor.photos`）
   - **日历存储**（`com.android.providers.calendar`）
3. 强制停止上述三个应用，再重新打开。

### 3. 安装 KernelSU 伴随模块

把 `hidisk_appops.zip` 安装为 KernelSU 模块（KernelSU 管理器或 `ksud module install`），然后 **软重启**（KernelSU 模块变更需要软重启；完整重启也可以）。

验证：

```bash
adb shell appops get com.huawei.hidisk MANAGE_EXTERNAL_STORAGE
# 期望：MANAGE_EXTERNAL_STORAGE: allow
```

该模块只执行一条命令，不修改系统分区、图库数据库或云空间 APK。

### 4. 打开控制台

安装后桌面会出现 **HiDisk Hook** 图标（云空间图标 + 中心扳手）。打开后：

1. 授予 root 权限；
2. 点 **检查状态 / 作用域注入**，确认：
   - Root 可用；
   - AppOps 为 allow；
   - 三个作用域都有 `HiDiskHook: loaded in package=...` 记录；
3. 点 **重启作用域应用**（修改 scope 或升级模块后必须做）；
4. 点 **应用推荐 Honor 包状态**：
   - 停用 `com.hihonor.contacts.sync`；
   - 启用 `assetsync`、`assetsyncservice`、`collectcenter`、`hncloud`；
   - 设置 AppOps。

> 控制台的“作用域注入记录”来自 LSPosed 日志，是“最近一次注入”的证据，不是 LSPosed 配置库的绝对真值。刚改 scope 时请先重启目标应用再看。

## 使用方法

### 华为云空间

- 首次进入仍可能提示“手机已 Root”，选择“继续使用”即可；本模块不处理 root 检测。
- 登录/协议、主页、右上角四个点、管理空间应正常。
- 若仍显示“无法连接服务器”，先看 LSPosed 日志是否有 `hooked d.h2` / `hooked d.i2`，再确认作用域是否包含云空间。

### 图库双向同步

- 需同时启用云空间的图库同步与图库的“图库数据同步”；
- 相册是否上传仍尊重“使用云空间的应用/相册开关”，模块只放宽“相册级 dirty”，不会把用户关闭的相册强行上传；
- 缩略图/原图下载依赖 AppOps 伴随模块；
- 首次同步可能分批（每批 10 个），属正常。

### 联系人

- 联系人桥接依赖停用 `com.hihonor.contacts.sync`；
- 已有 Honor 本地联系人需要标脏（本项目的实验脚本使用 root `content update` 把 `raw_contacts.dirty=1`）；普通用户不建议手工改 DB；
- 恢复 Honor 包后，Honor 云可能重新接管联系人，产生竞争；控制台提供“恢复全部 Honor 包（回滚）”按钮，但回滚前请备份。

### 日历

- 日历桥接会 hook `CalendarProvider` 的本地日历选择与 hidisk 的调用包校验；
- 需允许日历存储的云同步开关；
- 仅对 `calendar + com.android.providers.calendar` 放行，不影响其它 syncType。

### 备忘录

Notepad 的真实同步引擎是 Honor HnCloud；华为 CloudSync 只做开关/校验。其 `SyncContentProvider` 受 `signature|privileged` 权限保护，普通 LSPosed 模块无法直接桥接，故本项目暂不提供备忘录同步。

## Release 资产

- `HiDiskHook-v1.0.0.apk`：LSPosed 模块（含控制台、图标、图库/联系人/日历桥接）。
- `hidisk_appops.zip`：KernelSU 伴随模块，只负责开机保持 `MANAGE_EXTERNAL_STORAGE=allow`。

校验值见 Release 页面。

## 从源码构建

### LSPosed 模块

需要：

- Android SDK，含 `build-tools` 与 `platforms/android-35`；
- JDK 17+；
- 本仓库 `libs/xposed-api-stub.jar`；
- 你自己的签名 keystore。

```bash
cd hidisk-hook
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_BUILD_TOOLS=35.0.0
export JAVA_HOME=/path/to/jdk
export XPOSED_API_STUB=$PWD/../libs/xposed-api-stub.jar
export KEYSTORE=$HOME/.android/debug.keystore
export KEY_ALIAS=androiddebugkey
export KEYSTORE_PASS=android
export KEY_PASS=android
./build.sh
```

产物：`hidisk-hook/build/HiDiskHook.apk`。  
构建脚本只把 `assets/shims.dex`（两个兼容类）追加到目标进程，不注入整个模块 dex。

### KernelSU 伴随模块

`hidisk-appops/` 是标准 KernelSU 模块目录；把 `module.prop`、`service.sh` 打包为 zip 即可。仓库 `release/hidisk_appops.zip` 是可直接安装的版本。

## 已知限制

- 不是实时同步引擎：依赖图库 Job、hidisk observer、CalendarProvider `HwSyncService` 等系统触发；
- 无版本守卫：应用升级后类名/字段/SQL 可能变化，hook 可能失效；
- 日历 hook 依赖 `CalendarProvider` 具体实现；
- 联系人桥接依赖停用 Honor 同步包，可能与 Honor 云功能冲突；
- 非折叠屏假设；折叠屏请勿直接使用 `j1/g1=false`；
- 未实现备忘录桥接；
- 控制台需要 root，且状态检查是日志证据，不是配置真值。

## 分析文档

- [连接失败与 Root 误报](docs/analysis/01-connect-failure-and-root-report.md)
- [四个点/管理空间黑屏](docs/analysis/02-four-dots-and-manage-space.md)
- [UI 兼容修复](docs/analysis/03-ui-compat-fix.md)
- [图库云端同步失败](docs/analysis/04-gallery-cloud-sync-cause.md)
- [图库上传实验](docs/analysis/05-gallery-upload-experiment.md)
- [图库自动同步方案](docs/analysis/06-gallery-auto-sync-plan.md)
- [联系人桥接方案](docs/analysis/07-contacts-bridge-plan.md)
- [日历桥接方案](docs/analysis/08-calendar-bridge-plan.md)
- [黑屏修复记录](docs/analysis/09-blackscreen-fix.md)

## 致谢

- LSPosed / Xposed API
- KernelSU
- 以及所有提供日志、反编译与真机验证思路的开源工具

## 许可与免责声明

本项目仅用于学习研究，不提供任何明示或暗示的担保。使用即表示你理解并自行承担全部风险。禁止用于商业用途、绕过付费服务、侵犯隐私或违反当地法律法规及服务条款的场景。
