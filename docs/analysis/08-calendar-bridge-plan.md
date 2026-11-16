# 华为云日历在 Honor 设备上的桥接修复

> 说明：本文为实验记录，设备 IP、账号、手机号等已脱敏；文中 `analysis/`、`hidisk-hook/` 为仓库内相对路径。


## 1. 现象

- 本机在日历 App 内新建 2026-11-18 事件 `哈哈哈`（`events._id=6`，`calendar_id=1`，`account_type=com.android.hihonor.phone`），华为云/网页看不到。
- 华为云已有 2026-11-19 事件，本机日历不出现。
- 华为账号登录正常；`com.huawei.hidisk`、`CalendarProvider` 均存在 Huawei CloudSync SDK。

## 2. 根因

### 2.1 上传候选为 0：GTA vendor 兼容层退化

`CalendarProvider` 的同步日历选择：

```java
// SyncConstants
SYNC_CALENDARS_SELECTION =
    "account_type = '" + Utils.OLD_PHONE_TYPE + "'";

// Utils
OLD_PHONE_TYPE =
    "com.android."
    + com.hihonor.android.app.GtaPkgNameConstant.GTA_PRODUCT_VENDOR_LOWER
    + ".phone";
```

本机 `GTA_PRODUCT_VENDOR_LOWER = ""`，所以：

```text
OLD_PHONE_TYPE = com.android..phone
```

本地 `Calendars` 中：

- `_id=1`，`account_type=com.android.hihonor.phone`，包含 11-18 事件；
- `_id=5`，`account_type=com.android..phone`，空日历（classic account 回退）。

`EventSyncHelper.querySyncCalendars()` 只取 `com.android..phone`，漏掉 `_id=1`，于是 `queryLocalIds=0`，上传无候选。

### 2.2 云端 startSync 被拒：realPkgName 为空

修好本地候选后，`CloudSyncV1.lIds` 已包含 `id=6`，但 hidisk 侧：

```text
Cloud[17.0.1.701]Sync.SyncLogicService:
  startSync not avavliable, syncType = calendar, realPkgName =
```

`SyncLogicService.startSync()` 用 `getRealCallingPackageName()` 得到调用包，再交给 `DriveSyncUtil.k(syncType, pkg)` 做白名单校验。Honor 上该值为空，校验失败，直接拒绝。

## 3. 修复（LSPosed 模块）

模块：`hidisk-hook`
APK：`hidisk-hook/build/HiDiskHook.apk`
SHA-256：`b634552f369cf4992bf6696e7676f2e6f038842bd5f2337ca30f4ece12aa0954`

### 3.1 本地候选补齐

hook：

```text
com.android.providers.calendar.sync.EventSyncHelper
  .querySyncCalendars()
```

after hook：

1. 查询 `CalendarContract.Calendars.CONTENT_URI`：
   `account_type = com.android.hihonor.phone AND sync_events=1 AND deleted=0`；
2. 用 `HwSyncCalendar.SYNC_CALENDAR_PROJECTION` 构造 `HwSyncCalendar`；
3. 去重后追加到原列表；
4. 将 `mLocalPhoneId` 设为第一个真实日历 id（本机为 1）。

日志：

```text
HiDiskHook: calendar sync calendars appended, localPhoneId=1, count=4
HwCalendarSyncProcess: queryLocalIds size = 1
CloudSyncV1: lIds = [LocalId [id=6, dirty=0 , haveFile=0]] ,lIds size = 1
```

### 3.2 云端调用包白名单

hook：

```text
com.huawei.android.hicloud.sync.service.aidl.SyncLogicService
  .getRealCallingPackageName(Context, String)
```

after hook：`syncType=calendar` 且结果为空时返回 `com.android.providers.calendar`。

hook：

```text
com.huawei.android.hicloud.syncdrive.util.DriveSyncUtil
  .k(String, String)
```

before hook：`syncType=calendar` 时直接返回 `true`。

日志：

```text
HiDiskHook: calendar realPkgName -> com.android.providers.calendar
SyncLogicService: getCallingPackageName: callingAppPackageName = android.uid.calendar:10016
SyncLogicService: syncModulePackageName = android.uid.calendar:10016, sdkVersion = 2
```

### 3.3 作用域

LSPosed 中需勾选：

- `com.huawei.hidisk`（云空间）
- `com.android.providers.calendar`（日历存储）

图库/联系人 hook 仍保留在模块中，互不影响。

## 4. 实测结果

触发：

```bash
adb shell su -c 'am broadcast \
  -a com.hihonor.android.cloudSync.intent.STARTSYNC \
  -p com.android.providers.calendar \
  --es syncType calendar --ei syncScene 2'
```

上传：

```text
CloudSyncBase: Upload data , add = [[luid=6, guid=null, etag=null, unstruct uuid=null]]
UploadDataImpl: calendar syncV2 Begin uploadDataV2
SyncProcessBase: syncV2 Method = createRecordsNofile, syncType = calendar
SyncProcessBase: syncV2 Method result: dataType = calendar, succSize:1, failSize:0
SaveResultImpl: mSaveResult = [LocalId [luid=6,
  guid=FmBuI8SzQKyrnsPf-XC9LPpGpBmyaUAuq, etag=1, ...]]
HwSyncService: SyncServiceCallback onDataSyncEnd lastResultCode = 0
Cloud: calendar sync result: sync success.
```

下载：

```text
（同步结束后本地新增）
events._id=7, title=哇哇哇,
dtstart=1795030200000 (2026-11-19 03:30), calendar_id=1
```

本地事件查询：

```text
_id=6, title=哈哈哈, dtstart=1794943800000 (2026-11-18 03:30)
_id=7, title=哇哇哇, dtstart=1795030200000 (2026-11-19 03:30)
```

结论：11-18 已上传（服务端返回 guid/etag 1），11-19 已下载并落库；双向同步打通。

## 5. 说明与边界

- 没有手工改 `Calendars.account_type`，没有改 `events`，只通过 LSPosed hook 修正选择与调用包校验。
- `DriveSyncUtil.k` 的 calendar 放行只在模块作用域内生效；不影响其他同步类型。
- 该修复依赖 `CalendarProvider` 内置 Huawei CloudSync SDK；若 ROM 升级移除该 SDK，需要重新评估。
- 回滚：在 LSPosed 中停用模块，重启 `com.huawei.hidisk` 与 `com.android.providers.calendar`。
