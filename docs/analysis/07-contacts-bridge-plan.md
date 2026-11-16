# 华为云联系人桥接方案（Honor ROM）

> 说明：本文为实验记录，设备 IP、账号、手机号等已脱敏；文中 `analysis/`、`hidisk-hook/` 为仓库内相对路径。


## 目标

在 Honor 手机上，让 `com.huawei.hidisk` 的华为云联系人同步把本机
`Phone / com.android.hihonor.phone` 联系人当作自己的
`Phone / com.android.huawei.phone` 账户来读写，从而：

- 本机新建联系人可以上传到 Huawei Cloud；
- Huawei Cloud 上其他设备新建的联系人可以下载到本机；
- 不修改 Huawei Cloud APK、不重签、不替换 ContactsProvider。

## 根因回顾

- 本机联系人账户是 `Phone / com.android.hihonor.phone`。
- `com.huawei.hidisk` 的 `ContactManager` 硬编码
  `Phone / com.android.huawei.phone`。
- `pp.a.Y(Context)` 检测到 `com.hihonor.contacts.sync` 的
  `com.hihonor.android.cloudSync.intent.STARTSYNC` 接收器后返回 true，
  hidisk 因此不注册自己的联系人 observer，转由 Honor Cloud 同步。
- 结果：Honor 联系人进 Honor Cloud，Huawei Cloud 联系人不会落到本机。

## 方案

### 1. 停用 Honor 联系人同步

先禁用，不删除：

```bash
pm disable-user --user 0 com.hihonor.contacts.sync
```

`com.hihonor.hncloud` 等 Honor Cloud 包是否禁用，按实际影响决定；联系人
这一项只需要停 `com.hihonor.contacts.sync`。

### 2. LSPosed 三个桥接点（`com.huawei.hidisk` 进程）

1. `pp.a.Y(Context)` 返回 `false`，强制 hidisk 使用自己的 observer。
2. `com.huawei.android.hicloud.sync.contact.e.f11743b`
   从 `com.android.huawei.phone` 改为 `com.android.hihonor.phone`。
3. `ContactManager.f11664r[1]` 从 `com.android.huawei.phone` 改为
   `com.android.hihonor.phone`。

这两处账户类型覆盖 hidisk 的查询、插入和更新。

### 3. 一次性迁移旧联系人

Honor 同步过的联系人 `dirty=0`，hidisk 只查询 `dirty=1`。首次迁移时
把现有 Honor 联系人标记为 `dirty=1`，触发一次联系人同步；hidisk 上传
成功后会按自己的逻辑把 `dirty` 置 0。

测试时可以先只改一条（例如 111）验证，再批量迁移。

### 4. 下载方向

Huawei Cloud 下发联系人时，`ContactManager` 使用桥接后的
`Phone / com.android.hihonor.phone` 插入 ContactsProvider，因此理论上
仍显示在 Honor 联系人列表里。需要实测 Honor Contacts 是否接受该账户。

### 5. 触发同步

- 重启 `com.huawei.hidisk` 后，`ObserverManager.V()` 会注册
  `ContactObserverJobService`（JOB 1001）。
- 用 `cmd jobscheduler run -f com.huawei.hidisk 1001` 触发；
- 或打开云空间联系人入口，让应用自行触发。

## 验证

1. 本机联系人列表应出现 222（来自 Huawei Cloud）。
2. 本机 111 应上传到 Huawei Cloud。
3. CDP 打开 `https://cloud.huawei.com/home#/contact`，确认 111 出现。
4. 关闭/开启联系人同步开关后，再重复一次，确认不是一次性偶然。

## 风险

- `ContactManager` 假设 Huawei 账户的字段/组结构，Honor 账户可能有差异；
- 首次批量 `dirty=1` 可能造成 Huawei Cloud 重复联系人；
- Honor Contacts 可能对非 Honor 账户有显示/编辑限制；
- 直接删除系统包不可逆，优先 `pm disable-user`；
- 修改 Contacts DB 前必须备份 `contacts2.db` 与 `-wal`。

## 回滚

```bash
pm enable com.hihonor.contacts.sync
```

并在 LSPosed 中停用本模块或删除联系人桥接 hook；Contacts DB 用备份恢复。

## 实测结果（2026-11-17 02:35）

- 已禁用：`com.hihonor.contacts.sync`、`com.hihonor.hncloud`、
  `com.hihonor.assetsyncservice`、`com.hihonor.assetsync`（均
  `disabled-user`）。
- 实际 dex 字段名：`com.huawei.android.hicloud.sync.contact.e.b`、
  `com.huawei.android.hicloud.sync.contact.ContactManager.r`；不是 jadx
  生成的 `f11743b` / `f11664r`。
- 本机 `111`（raw_id=70）置 `dirty=1` 后，点击云空间“联系人”卡片：
  - `ContactManager: Local added contact size = 70`
  - `SyncProcessBase: syncV2 createRecordsNofile records succ size: 43`
  - `AddressLogicProcess: syncV2 local suc add contact size: 43`
  - `SyncContactTask: Contact sync result = 0, returnCode = 0`
- 下载方向：`222` 出现在本机
  `raw_contacts._id=71`，`account_type=com.android.hihonor.phone`，
  `dirty=0`。
- 上传方向：CDP 刷新 `https://cloud.huawei.com/home#/contact` 后
  `所有联系人(29)` → `所有联系人(35)`，列表出现 `111`（手机号
  `测试号码`），确认上传成功。
- 最终 APK：`hidisk-hook/build/HiDiskHook.apk`
  （20,961 B，SHA-256
  `6d5b9fe08a1c0684fb83006596ef71070b7b22b4fe152467a95be1b86d703fdd`）。
- 说明：上传的 43 条记录中包含更新/已存在联系人，因此云端总数只从
  29 增到 35；111 已确认存在。
