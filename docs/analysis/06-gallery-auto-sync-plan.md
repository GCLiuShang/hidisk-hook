# 图库持续自动上传：LSPosed 方案设计

> 说明：本文为实验记录，设备 IP、账号、手机号等已脱敏；文中 `analysis/`、`hidisk-hook/` 为仓库内相对路径。


日期：2026-11-17  
设备：`192.0.2.10:5555`（REP-AN00 / MagicOS 9）  
图库：`com.hihonor.photos`（uid 10205）  
云空间：`com.huawei.hidisk`（uid 10054）  
目标：不修改 Gallery/HiDisk APK、不重签、不写 SQLite trigger，使用 LSPosed 让新照片在 Gallery 正常同步时自动进入上传队列。

---

## 1. 结论摘要

已完成实验证明，上传失败不是网络、账号或云空间容量问题，而是两个必要条件同时缺失：

1. **相册级 `gallery_album.dirty=0`**：Gallery 的 `getCreatedGeneralAlbums()` 只查 `dirty=1` 的相册，因此相册内即使有 `gallery_media.dirty=1`，也不会产生上传候选。
2. **hidisk 读 `.photoShare/thumb/...` 被 EACCES 拒绝**：没有 `MANAGE_EXTERNAL_STORAGE` 时，上传阶段即使启动，也会在读缩略图时得到 `ret=1011`，随后因 hmac 返回 null 触发 `Long.longValue()` NPE。

修复实验：

- `appops set com.huawei.hidisk MANAGE_EXTERNAL_STORAGE allow`；
- 手工把相机/截图相册 `gallery_album.dirty=1`；
- 触发一次 Gallery 云同步（广播 `com.huawei.cg.action.CAN_START_SYNC`，`Module=2`）。

结果：本地 34 条媒体全部获得 `uniqueId`，云端从 `745` 增加到 `779`，目标相机照片和测试截图均出现在云端。

**因此持续自动同步需要两层：**

- 权限层：hidisk 必须能读 `.photoShare/thumb/...`；
- 逻辑层：Gallery 必须把“有 dirty 媒体的相册”当作上传候选。

推荐的最小化 LSPosed 方案是 **两个 hook + 一次性 appops**：

1. 在 `CloudAlbumSdkCallbackUtils.getGeneralAlbums()` 的 SQL 中，把“相册 dirty=1”扩展为“相册 dirty=1 或其媒体存在 dirty=1/32”；
2. 在 `CloudAlbumSdkCallback.needSyncUploadGeneral()` 返回 false 时，用媒体级 dirty 做兜底，避免上传阶段被提前判定为“无需要上传”；
3. `MANAGE_EXTERNAL_STORAGE` 保持 allow（AppOps 模式通常跨重启保留，不需要每次开机重设）。

不推荐在模块里持续写 Gallery 数据库，也不推荐用 SQLite trigger 作为首选：两者都能工作，但会引入 DB 锁、WAL、`albumId` 为空、应用升级后 schema 变化等额外风险。

---

## 2. 实验事实（方案依据）

### 2.1 最终状态

```text
gallery_media 中 local_media_id > 0：34 条
其中 uniqueId 非空：34 条
云端：745 张照片 + 4 个视频 -> 779 张照片 + 4 个视频
```

说明“权限 + 相册 dirty”修好后，整个积压队列会按批上传完成，并不是只上传测试图。

### 2.2 两个独立断点

| 断点 | 现象 | 直接原因 | 修复 |
|---|---|---|---|
| 上传不收集候选 | 日志只有下载，`no upload file`，无 `createGeneralFiles` | `gallery_album.dirty=0`，`getCreatedGeneralAlbums()` 查询为空 | 让“有 dirty 媒体的相册”进入候选 |
| 上传启动后 1011 | `createGeneralFiles` 有 10 个文件，但 `ret=1011`，随后 `Long.longValue()` NPE | hidisk 读 `.photoShare/thumb/thumb/...` 被 EACCES 拒绝 | 授予 `MANAGE_EXTERNAL_STORAGE`，或改为通过 MediaStore 读取 |

### 2.3 关键代码位置

- `CloudAlbumSdkCallbackUtils.getGeneralAlbums(String, String[], long)`
  - 位置：`jadx-gallery6/sources/com/hihonor/gallery/photoshare/cloudsdk/CloudAlbumSdkCallbackUtils.java:311`
  - 内部调用 `GalleryAlbum.query(str, strArr, null, j)`，把行转成 `GeneralAlbumData`。
- `CloudAlbumSdkCallback.getCreatedGeneralAlbums(int)`
  - 位置：`jadx-gallery5/sources/com/hihonor/gallery/cloudsdk/adapter/api/CloudAlbumSdkCallback.java:214`
  - 上传 SQL 的核心条件：
    - `dirty = ?`，参数为 `1`；
    - `(uploadStatus = 1 OR cloud = 1)`；
    - `EXISTS (SELECT 1 FROM gallery_media ... )`。
- `CloudAlbumSdkCallback.needSyncUploadGeneral()`
  - 位置：`CloudAlbumSdkCallback.java:474`
  - 先查 `gallery_album` 的 `dirty != 0`；
  - 若为 0，再查 `gallery_media` 的 dirty（`dirty=1/32`）作为兜底。
- `CloudAlbumSdkCallback.getCreatedGeneralFiles(int)`
  - 位置：`CloudAlbumSdkCallback.java:244`
  - 调用 `UploadService.getUploadFiles(i, false)`；
  - 选中相册的 `uploadStatus=1` 时，媒体 `dirty=1` 也能进入上传候选。
- `GeneralAlbumSyncLogic.Y()`
  - 位置：`jadx-gallery3/sources/m2/d.java:286`
  - 只有 `needSyncUploadGeneral()` 为 true 才继续上传；否则 `no need upload`。

---

## 3. 设计目标与约束

### 目标

1. 新照片进入相机/截图等已开启同步的相册后，下一次 Gallery 同步能自动上传；
2. 不要求用户每次手工改 `gallery_album.dirty`；
3. 不修改 Gallery/HiDisk APK，不重签，不破坏云空间签名/账号信任；
4. 只在目标包生效，模块停用后行为恢复；
5. 不因模块导致重复上传、上传已关闭相册、或破坏回收站/隐藏相册语义。

### 约束

- Gallery 的数据库由 `GalleryProvider` 管理，直接 `UPDATE` 可能与 WAL、事务、应用缓存冲突。
- 新扫描的 `gallery_media.albumId` 可能为空（实验中的测试图就是空），不能假设它已有 albumId。
- `gallery_album.dirty` 会被云同步流程复位为 0；模块不应依赖“一次性写 DB 后永久保持”。
- `.photoShare/thumb` 由 MediaProvider 持有，`chmod` 会被 FUSE 重置。
- LSPosed 模块运行在目标进程内，不能天然给自己授予 `MANAGE_EXTERNAL_STORAGE`；AppOps 仍应由设备级设置或 root 脚本处理。
- 不同 Gallery 版本可能改名/内联；需要版本保护和失败降级。

---

## 4. 推荐方案：两个逻辑 hook + 一次性权限

### 4.1 Hook A：扩展 `getGeneralAlbums()` 的相册候选

**目标**：让 `dirty=0` 但含 `dirty=1/32` 媒体的相册，也出现在 `GeneralAlbumData` 列表中。

**位置**：`CloudAlbumSdkCallbackUtils.getGeneralAlbums(String sql, String[] args, long limit)`。

**做法（概念，不是代码）**：

- 只对“上传创建相册”的 SQL 生效，即同时包含：
  - `dirty = ?`；
  - `(uploadStatus = 1 OR cloud = 1)`；
  - `EXISTS (SELECT 1 FROM gallery_media ...)`。
- 在该 SQL 的 `EXISTS` 子查询中追加媒体级条件：
  - `dirty = 1`（新增/修改待上传）；
  - `dirty = 32`（需要更新的媒体状态）；
  - 保留原有的 `recycleFlag` 排除条件。
- 等效语义：
  - 原条件：相册自身 `dirty=1`；
  - 新条件：相册自身 `dirty=1` **或** 该相册存在 `dirty IN (1,32)` 且未回收的媒体。
- 不改 `uploadStatus/cloud` 条件，因此用户关闭的相册仍不会上传。
- 不改 `limit`，保持每批 10 个的行为。

**为什么选这里**：

- `getGeneralAlbums()` 是“相册行 → `GeneralAlbumData`”的唯一转换点；
- 保留原有 `GalleryAlbum.query`、`CloudDataConverter` 逻辑，不需要手工构造 `GeneralAlbumData`；
- 不需要写数据库，不会与 Gallery 的 WAL/事务冲突；
- 只影响上传候选查询，不影响相册列表、回收站、隐藏相册等 UI 查询。

### 4.2 Hook B：`needSyncUploadGeneral()` 的媒体级兜底

**目标**：防止 Gallery 在相册 `dirty=0` 时提前判定“无需要上传”，根本不进入 `getCreatedGeneralAlbums()`。

**位置**：`CloudAlbumSdkCallback.needSyncUploadGeneral()`。

**做法（概念）**：

- 先调用原方法；
- 若原方法返回 false，再查 `gallery_media`：
  - `dirty IN (1,32)`；
  - 未回收；
  - 属于 `uploadStatus=1` 或 `cloud=1` 的相册；
  - 有 `local_media_id > 0`。
- 有结果则返回 true；否则保持 false。

**注意**：

- 该方法的原生实现其实已经有一段媒体级兜底；但在本次设备上，相册 `dirty=0` 时上传仍被判“无候选”。Hook B 是防御性补强，确保不同 Gallery 版本/分支下都能进入 `getCreatedGeneralAlbums()`。
- 不应无条件返回 true：否则每次同步都会进入上传阶段，虽然后续列表为空也会退出，但会产生无意义日志和唤醒。

### 4.3 权限层：`MANAGE_EXTERNAL_STORAGE`

LSPosed 模块本身不负责授予 AppOps。推荐保留：

```text
appops set com.huawei.hidisk MANAGE_EXTERNAL_STORAGE allow
```

理由：

- AppOps 模式通常写入系统设置，跨重启保留；
- 卸载/清除数据/ROM 重置后需要重设；
- 不需要每次开机执行；
- 若希望设备级自动化，可另做一个 KernelSU `.su` 模块的 `service.sh` 做幂等设置（这是系统脚本，不是 LSPosed 模块的一部分）。

**不推荐**用 LSPosed 在 hidisk 内 hook `open()`/`FileInputStream` 来绕过 FUSE：

- 需要覆盖 Java/Native 多层文件读取；
- 还要处理 hidisk 的 `RandomAccessFile`、hmac、缩略图缓存；
- 代码量和回归风险都高于一次 AppOps 设置。

### 4.4 可选 Hook C：同步触发

如果希望“拍完照很快自动上传”，而不只是“下次打开 Gallery 时上传”，可以增加一个触发点：

- 监听 Gallery 的媒体变更/扫描完成事件，或
- 在 `MediaStore` 插入后触发一次 Gallery 的云同步。

但不建议把它作为第一版必需项：

- Gallery 自身有云同步调度；Hook A/B 保证“只要同步跑，就会带上新媒体”；
- 额外触发可能造成频繁唤醒、重复同步、耗电；
- 触发时机与 Gallery 生命周期耦合，版本升级容易失效。

建议先验证 Hook A/B 在“打开 Gallery / 手动同步 / 充电+Wi-Fi 自动同步”下都能工作，再决定是否需要 Hook C。

---

## 5. 为什么这是当前路径下的最小方案

| 方案 | 改动量 | 持续自动 | 风险 | 评价 |
|---|---|---|---|---|
| 手工改 `gallery_album.dirty` | 每次手工 SQL | 否 | DB 锁/WAL | 仅实验 |
| SQLite trigger | 一条 trigger | 是 | `albumId` 可能为空、schema 变化、应用升级丢失 | 无代码但脆弱 |
| Hook `createNewAlbum()` 置 dirty | 一个 hook | 部分 | 只覆盖新相册，不覆盖已有相册新增媒体 | 覆盖不全 |
| Hook `GalleryAlbum.query()` 全局放宽 | 一个 hook | 是 | 影响所有相册查询，范围过大 | 不推荐 |
| **Hook `getGeneralAlbums()` + `needSyncUploadGeneral()`** | **两个 hook** | **是** | 仅上传路径，失败可降级 | **推荐** |
| LSPosed 内 hook 文件读取替代 AppOps | 多个 hook | 是 | Java/Native 覆盖复杂 | 不推荐作为首版 |

结论：

- 对“一次性修复”，`appops + 一次相册 dirty` 已经是最小操作集；
- 对“持续自动同步”，**两个上传路径 hook + 一次性 appops** 是当前代码结构下最小且可控的组合；
- 不需要改数据库、不需要禁用入口、不需要重签 APK。

---

## 6. 持久化与部署

### 6.1 LSPosed 模块

- 作用域：仅 `com.hihonor.photos`；
- 不需要把 `com.huawei.hidisk` 加入作用域（除非未来选择 hook 文件读取）；
- 模块启用后，LSPosed 在 Gallery 进程启动时注入；
- 模块升级/停用后，重启 Gallery 生效；
- 建议在模块中记录版本号和命中日志，便于确认 hook 是否生效。

### 6.2 AppOps 的持久化

- `appops set ... allow` 是系统级设置，通常跨重启保留；
- 应用卸载/清除数据/系统恢复后需要重做；
- 若要做成“刷机/恢复后自动恢复”，用 KernelSU `.su` 模块的启动脚本做幂等设置；
- 不建议把 AppOps 设置塞进 LSPosed 模块：模块进程没有 `MANAGE_APP_OPS_MODES`，直接改会失败或需要更危险的 system_server hook。

### 6.3 不建议的持久化方式

- **每次开机 `sqlite3` 改 Gallery DB**：Gallery 可能正在运行，WAL/锁/缓存会导致写入被覆盖或 DB 损坏；
- **SQLite trigger**：虽然能自动置 dirty，但新扫描行的 `albumId` 可能为空，需要复杂的 bucket 映射；Gallery 升级/清数据会丢 trigger；
- **定时脚本轮询 DB**：会持续唤醒、增加耗电，且仍有锁风险。

---

## 7. 正确性与边界条件

### 必须保持的行为

- 只上传 `uploadStatus=1 OR cloud=1` 的相册；
- 不上传回收站/隐藏相册中的媒体；
- 不改变 `dirty=2`（已同步）、`dirty=64`（云端变更）的语义；
- 不改变每批 10 个文件的上传节奏；
- 不改变 `uniqueId` 为空才上传的过滤条件；
- 不改变用户关闭某路径同步后的行为。

### 需要验证的边界

1. 新照片进入**已存在**的相机相册（`default-album-1`）；
2. 新截图进入**已存在**的截图相册（`default-album-2`）；
3. 新照片进入**新建**相册（`createNewAlbum` 已写 dirty=1，应仍然工作）；
4. 用户关闭相机同步后，相机新照片不应上传；
5. 回收站/隐藏相册中的 dirty 媒体不应上传；
6. 云端已存在、`uniqueId` 非空的媒体不应重复上传；
7. Gallery 升级到新版本后，hook 未命中时应**安全降级**，不能导致崩溃；
8. hidisk 未授予 all-files access 时，应回到 `1011` 的明确日志，而不是静默失败。

### 版本兼容

- 目标版本：当前设备 `com.hihonor.photos` 16.x；
- 类名/方法名可能被 R8 混淆；应使用完整类名 + 方法签名查找；
- 若查找失败，只记录日志并跳过，不影响 Gallery 正常启动；
- 升级后先验证 `getCreatedGeneralAlbums`/`needSyncUploadGeneral` 是否仍存在。

---

## 8. 验证计划（未来实施时）

### 阶段 1：静态/加载验证

- LSPosed 日志确认模块注入 `com.hihonor.photos`；
- 两个 hook 均打印“已安装”；
- Gallery 正常启动，无崩溃。

### 阶段 2：单张新照片

- 拍一张新照片（相机）；
- 打开 Gallery，触发一次云同步；
- 期望日志：
  - `getCreatedGeneralAlbums albumData size >= 1`；
  - `createGeneralFiles currentUploadFiles >= 1`；
  - `HandleCallable upload, ret: 0`；
  - `upload Success`。
- CDP 检查云端出现该文件名；
- DB 中该媒体 `uniqueId` 非空。

### 阶段 3：批量与关闭路径

- 连续拍多张，确认分批上传（每批 10）；
- 关闭相机同步，确认新照片不再上传；
- 重新开启，确认恢复；
- 回收站/隐藏相册不进入上传。

### 阶段 4：重启与升级

- 重启设备，确认 AppOps 仍为 allow；
- 重启 Gallery，确认模块仍生效；
- 模拟 Gallery 升级，确认 hook 失败时安全降级。

---

## 9. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| Gallery 版本升级导致方法名变化 | hook 不生效 | 启动时校验，失败仅日志；按版本适配 |
| 模块无条件返回 true | 频繁空同步、耗电 | Hook B 仅在存在 dirty 媒体时返回 true |
| SQL 改写范围过宽 | 影响非上传查询 | 只匹配包含上传三条件的 SQL |
| AppOps 被撤销 | 上传回到 1011 | 文档记录重设命令；可用 .su 模块开机幂等设置 |
| 用户关闭某相册同步 | 仍被上传 | 保留 `uploadStatus/cloud` 条件 |
| DB 被外部修改 | 数据不一致 | 本方案不写 DB，避免该风险 |

---

## 10. 待确认问题

1. 在 Gallery 16.x 的当前分支里，`needSyncUploadGeneral()` 的媒体级兜底是否总会返回 true？如果会，Hook B 可以只作为防御性补丁。
2. 新扫描媒体的 `relative_bucket_id` 是否总是非空？Hook A 依赖它做相册关联，不能依赖可能为空的 `albumId`。
3. `getCreatedGeneralAlbums()` 的 SQL 在 `CloudSyncErrorManager` 有失败缓冲时多一个 `albumId NOT IN (...)` 条件；SQL 改写必须保留该条件。
4. 是否需要 Hook C（媒体插入即触发同步）取决于用户对“拍完照多久内必须上传”的要求；第一版建议不做。

---

## 11. 最终建议

- **采用 LSPosed 模块 + 两个上传路径 hook + 一次性 AppOps**；
- 不在模块中写 Gallery 数据库；
- 不把 SQLite trigger 作为首选；
- 不把 system 化 HMS Core 作为图库上传问题的解决方案（它只影响 hidisk 的 `h2/i2` 信任检查，不解决 Gallery 的相册候选与 `.photoShare` 权限）；
- 第一版只做“同步被触发时一定带上新媒体”，暂不做“拍照后立即强制同步”；
- 先验证相机/截图两个默认同步相册，再扩展到用户开启的其他路径。

这样可以在保持原生功能、不重签 APK、不修改系统分区的前提下，把“手工改 dirty + 手工触发”的实验方案，收敛为可持续的自动同步方案。

---

## 12. 实测结果（2026-11-17）

- 模块 21 KB，已安装；LSPosed 作用域加入 `com.hihonor.photos`，`getGeneralAlbums` 与 `needSyncUploadGeneral` 两个 hook 均加载：
  `HiDiskHook: hooked getGeneralAlbums(String, String[], long)` / `hooked needSyncUploadGeneral()`。
- 测试样本：新建 `Screenshot_20261117_014700_autotest.png`，仅做 MediaStore 扫描，**没有**手工改 `gallery_album.dirty`，**没有**发送 `CAN_START_SYNC` 广播。
- 启动图库后日志：
  - `HiDiskHook: relaxed upload album SQL`
  - `GeneralAlbumSyncLogic: startUpload`
  - `createGeneralFiles currentUploadFiles: 1`
  - `execUpload, upload file size: 1`
  - `HandleCallable upload, ret: 0`
- 数据库终态：`gallery_album` 的相机/截屏相册仍为 `dirty=0`；新截图 `_id=818` 从 `dirty=1, uniqueId=''` 变为 `dirty=2, uniqueId=1d869194be904b52a7e274bb7d6774a5f9a9e4c491ae435986b5640de96c701e`。
- 说明：本轮 CDP 页面因强制 reload 退出登录，云端页面回到官网，未能在网页侧完成最后一次计数确认；服务端已返回上传成功并分配 `uniqueId`，可重新登录后用“照片”视图核对（预期 779→780）。

---

## 13. 下载/缩略图链路补齐（2026-11-17）

静态分析表明，`e7.e.k/n` 与 `u6.a.b` 只是**默认缓存路径**；实际下载路径还可能由 `SettingsProp` 的 `thumb_cache_path`/`lcd_cache_path`、`FileInfo.localThumbPath/localRealPath` 决定。只 hook `e7.e` 不能覆盖所有路径，改 `db.b` 的落盘路径又会破坏调用方对目标文件的预期。

因此采用第三条路线，保持路径不变：

- 设备级前提：`appops set com.huawei.hidisk MANAGE_EXTERNAL_STORAGE allow`；
- 用 KernelSU 伴随模块 `hidisk_appops` 在开机后幂等设置该 AppOp；
- 模块不写 Gallery 数据库、不改系统分区、不改 hidisk 的下载路径。

已验证：在 AppOps 为 allow 时，Gallery 的“所有照片(云端)”能显示云端缩略图，不再只有占位/转圈；上传链路也不需要再改 `.photoShare` 路径。

伴随模块：`hidisk-appops/`，打包 `hidisk_appops.zip`，已通过 `ksud module install` 安装。

> 注：KernelSU 模块更新后需要**软重启**（或重启）才会执行 `service.sh`；LSPosed 模块更新只需重启作用域应用。若只更新 APK，不要重启手机。
