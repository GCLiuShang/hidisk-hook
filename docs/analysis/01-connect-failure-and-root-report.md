# 华为云空间（com.huawei.hidisk）无法连接服务器 / Root 提示 分析报告

> 说明：本文为实验记录，设备 IP、账号、手机号等已脱敏；文中 `analysis/`、`hidisk-hook/` 为仓库内相对路径。


- 分析时间：2026-11-16
- 设备：HONOR REP-AN00，Android 15 (SDK 35)，MagicOS 9.0.0.230(SP1CHNC00E215R2P6)，Build type user / tags release-keys
- 分析对象：设备上已安装的 **17.0.1.701**（versionCode 170001701）
  - APK：`/data/app/~~QeWCoCOSYXpm283sKthYWA==/com.huawei.hidisk-xhEmbMzpLICWoXJX5TIXLg==/base.apk`
  - 本地副本：`analysis/apk/installed-base.apk`
  - SHA-256：`10ea7c0531078298396010ea6375d7f58cb7b0bcdc3c6c0cc87d170fe90f9dc0`
- 对照包：`/Download/com.huawei.hidisk.apk`，17.0.1.301（versionCode 170001301），SHA-256 `c5111e07099433648c3c165a410e9aa0cdb7356f6c94004fad3bc709223e08b1`
- 反编译：jadx 1.5.1，输出 `analysis/jadx-installed/sources`（17,687 个 .java）

---

## 一、结论（先看这里）

1. **“无法连接服务器”不是网络问题，也不是华为服务器故障。**
   应用在欢迎页查询“统一协议（terms/agreement）”时，先做了一次**本地信任校验**：
   `com.huawei.hwid`（HMS Core）提供的 `content://com.huawei.hwid.api.provider/agree_state` 必须满足以下任一条件：
   - 是系统应用（`FLAG_SYSTEM`），或
   - 是“系统应用的更新版”（`FLAG_UPDATED_SYSTEM_APP`），或
   - 与本应用（hidisk）签名相同。

   本机 HMS Core 是**通过 APK 安装的普通用户应用**（`/data/app/...`，flags 中没有 SYSTEM），且它的签名证书与 hidisk **不同**。因此校验失败，代码直接返回错误码 `31001`，欢迎页把该错误统一显示为 `connect_server_fail_msg1` —— “无法连接服务器，请点击屏幕再试”。

   这条文案是**通用的“取信息超时/失败”文案**（错误页 TextView 的 id 就是 `set_get_info_timeout_str`），并不代表 TCP/TLS/HTTP 失败。日志里 GRS、TLS、配置下载都成功了，`getUserPublicInfo` 请求也已发出并进入响应解析。

2. **“手机已 Root”是真实检测结果，不是误报。**
   应用的 `RootUtil.i()` 是 6 项检查的“或”：
   - `su` 文件是否出现在 `$PATH` 各目录；
   - 系统属性 `persist.sys.root.status` 是否非空且不等于 `0`；
   - `ro.secure` 是否为 `0`；
   - `ro.build.tags` 是否含 `test-keys`；
   - 是否模拟器；
   - 是否安装了经典 root 应用包名列表中的某一个。

   本机命中两项：
   - `/system/bin/su` 存在（4,892,712 字节，root:root；KernelSU 提供）；
   - `persist.sys.root.status = 10`。

   所以它报告 Root 是**正确的**。这个对话框只是风险提示，点“继续使用”可以继续；它**不是**连接失败的原因。

3. 这两个问题是**相互独立**的：
   - Root 提示：本地 `RootUtil` 检测 → 弹窗（可绕过，仅提示）。
   - 连接失败：本地 `h2()` 信任校验 → 协议查询失败 → 通用断连文案（真正的拦路点）。

---

## 二、复现过程

清空 logcat 后启动：

```bash
adb shell am start -n com.huawei.hidisk/com.huawei.android.hicloud.ui.activity.NewHiSyncSettingActivity
```

1. 首先出现 Root 风险对话框：
   - 文案：`手机已 Root，使用云空间可能存在安全风险`
   - 按钮：`暂不使用` / `继续使用`
   - 截图：`analysis/s1.png`
2. 点“继续使用”后，进入欢迎页，随即整屏显示：
   - `无法连接服务器，请点击屏幕再试`
   - 截图：`analysis/s3.png`
   - UI dump：`analysis/ui3.xml`
   - 该 TextView 的 resource-id 为 `com.huawei.hidisk:id/set_get_info_timeout_str`

对应字符串资源（aapt2 dump resources）：

| 资源 | ID | zh-rCN |
|---|---|---|
| `string/connect_server_fail_msg1` | `0x7f0b0676` (2131428982) | 无法连接服务器，请点击屏幕再试 |
| `string/server_disconnected_error_text` | `0x7f0b0fa1` (2131431329) | 同上 |
| `string/root_alert_msg1` | `0x7f0b0f6a` (2131431274) | 手机已 Root，使用云空间可能存在安全风险 |
| `string/root_alert_go_on` | `0x7f0b0f69` | 继续使用 |
| `string/root_alert_not_use` | `0x7f0b0f6b` | 暂不使用 |

---

## 三、“无法连接服务器”的证据链

### 3.1 运行日志（21:25:07，pid 15185）

```text
21:25:07.248  I Cloud[17.0.1.701]Sync.BasicService: requestInfo is getUserPublicInfo
21:25:07.481  I Cloud[17.0.1.701]Sync.BasicService:
              https://userk-drcn.cloud.dbankcloud.cn/HiCloudUserFoundationService/Client/getUserPublicInfo
              getUserPublicInfo x-hw-trace-id = 07012_01_1794835507_58098258
21:25:07.537  E Cloud[17.0.1.701]Base.AccountUtil: is not Target App or System App
21:25:07.537  I Cloud[17.0.1.701]Base.AccountUtil: getDeviceAccountBrand from provider, accountBrand:
21:25:07.541  E Cloud[17.0.1.701]Base.BaseUtil:
              getOsBrand exception: java.lang.ClassNotFoundException: com.huawei.system.BuildEx
21:25:07.706  I Cloud[17.0.1.701]Sync.BasicService: parseErrorByRsp
21:25:07.719  I Cloud[17.0.1.701]Sync.CheckGRSUrlsTask: processSuccess
21:25:07.723  I Cloud[17.0.1.701]Sync.QueryAgreementServiceTask:
              QueryAgreementServiceTask isSupportUnifyAgreement=true, isChinaRegion=true
21:25:07.724  I Cloud[17.0.1.701]Base.BaseUtil: provider packageName is: com.huawei.hwid
21:25:07.725  E Cloud[17.0.1.701]Sync.QueryAgreementServiceTask: is not Target App or System App
21:25:07.725  I Cloud[17.0.1.701]Sync.AgreementHandler: handleMessage, msg.what: 31001
21:25:07.725  I Cloud[17.0.1.701]Sync.WelcomeBaseActivity: Terms query failed, show error!
21:25:07.726  I Cloud[17.0.1.701]Sync.WelcomeBaseActivity: Receive msg=1
21:25:08.646  I Cloud[17.0.1.701]Config.HiCloudSysParamConfigTask: get hicloudSysParam config success
```

关键点：

- `provider packageName is: com.huawei.hwid` → 解析到的正是 HMS Core 的 provider；
- 紧接着 `is not Target App or System App` → 本地校验拒绝；
- `msg 31001` → 协议查询任务失败；
- `Terms query failed, show error!` → 欢迎页展示通用错误页；
- **同一时间段配置下载成功了**（`get hicloudSysParam config success`），网络是通的。

### 3.2 代码路径

`o7/a.java`（`QueryAgreementServiceTask`）：

```java
// o7/a.java:53
public void call() {
    ...
    boolean N0 = u9.b.N0(this.f49293b.getApplicationContext(), vj.e.T().H0());
    boolean booleanValue = vj.e.T().Z0().booleanValue();
    ...
    if (N0 && booleanValue) {
        e();          // 中国区 + 支持统一协议 → 走 HMS provider
    } else {
        f();          // 否则走 TMS 网络查询
    }
}

// o7/a.java:76
public final void e() {
    ContentResolver contentResolver = f.a().getContentResolver();
    Uri parse = Uri.parse("content://com.huawei.hwid.api.provider/agree_state");
    if (!com.huawei.hicloud.base.common.d.h2(f.a(), parse)) {
        u9.l.e("QueryAgreementServiceTask", "is not Target App or System App");
        Message obtain = Message.obtain();
        obtain.what = 31001;
        this.f49292a.sendMessage(obtain);
        return;
    }
    ...
}
```

信任校验 `com/huawei/hicloud/base/common/d.java:1399`：

```java
public static boolean h2(Context context, Uri uri) {
    ...
    ProviderInfo resolveContentProvider =
            packageManager.resolveContentProvider(uri.getAuthority(), 0);
    ...
    ApplicationInfo applicationInfo = resolveContentProvider.applicationInfo;
    String str = applicationInfo.packageName;
    hk.a.i("BaseUtil", "provider packageName is: " + str);
    int i11 = applicationInfo.flags;
    return ((i11 & 1) == 1)                 // FLAG_SYSTEM
        || ((i11 & 128) != 0)               // FLAG_UPDATED_SYSTEM_APP
        || (packageManager.checkSignatures(str, context.getPackageName()) == 0);
}
```

也就是说：**HMS Core 必须是系统应用 / 系统应用更新版，或者与 hidisk 同签名**，否则直接判失败，连 provider 都不会调用。

### 3.3 为什么本机 `h2()` 一定返回 false

`adb shell dumpsys package com.huawei.hwid`：

```text
codePath=/data/app/~~QF6SMf4o8ThOcsVJbIjMhQ==/com.huawei.hwid-tGmJDe0TesvoHBNwC-cTHQ==
versionName=6.16.2.300  versionCode=61602300
flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
```

- 路径在 `/data/app/...`，是**用户安装应用**；
- flags 中没有 `SYSTEM`(1)，也没有 `UPDATED_SYSTEM_APP`(128)；
- `pm list packages -s | grep -i huawei` 结果为空，进一步确认 HMS Core 不在系统分区。

签名对比（apksigner）：

| APK | 证书 DN | SHA-256 |
|---|---|---|
| HMS Core `com.huawei.hwid` | `EMAILADDRESS=mobile@huawei.com, CN=AndroidTeam, OU=TerminalCompany, O=Huawei, L=Shengzhen, ST=Guangdong, C=CN` | `b92825c2bd5d6d6d1e7f39eecd17843b7d9016f611136b75441bc6f4d3f00f05` |
| hidisk `com.huawei.hidisk` | `CN=China, OU=Huawei, O=Huawei, L=Shenzhen, ST=Shenzhen, C=CN` | `35ee6832a80d8552eef38408a504f169d27564b87d3bd7a3b0779bee68a57df4` |

两者证书不同，`checkSignatures()` 不可能返回 0。

provider 归属也确认无误：

```text
[com.huawei.hwid.api.provider]:
  Provider{... com.huawei.hwid/com.huawei.hms.runtimekit.stubexplicit.HwIDProvider}
    applicationInfo=ApplicationInfo{... com.huawei.hwid}
```

**结论：flags 不满足、签名不满足 → `h2()` 返回 false → `31001`。**

### 3.4 错误页为什么显示“无法连接服务器”

`WelcomeBaseActivity.java:574`（内部类 `i.d()`）：

```java
public void d() {
    u9.l.i("WelcomeBaseActivity", "Terms query failed, show error!");
    if (d.I1(WelcomeBaseActivity.this)) {
        f14512f1.sendEmptyMessage(1);
    } else {
        f14512f1.sendEmptyMessage(3);
    }
}
```

Handler 的 `case 1`（`WelcomeBaseActivity.java:300`）执行 `X1()` + `z2(8, 8, 0, 8)`，显示错误布局。

布局本身可以直接证明文案来源（`res/layout/welcom_layout.xml`）：

```text
aapt2 dump xmltree apk/installed-base.apk --file res/layout/welcom_layout.xml
  ...
  android:id(0x010100d0)=@0x7f0a1194          # id/set_get_info_timeout_str
  android:text(0x0101014f)=@0x7f0b0676        # string/connect_server_fail_msg1
  ...
```

也就是说，屏幕上那个 TextView 的 id 是 `set_get_info_timeout_str`，文本直接绑定 `connect_server_fail_msg1`。

因此这条文案的实际含义是“**取信息失败/超时**”，而不是“服务器不可达”。把本地信任校验失败映射成“无法连接服务器”，是该版本的一个**误导性错误提示**。

### 3.5 排除网络原因的证据

- GRS 成功返回 `com.huawei.hicloud.v2` 等服务 URL（`GrsClientManager.synGetGrsUrl ... totalTime=0ms`，`getServicesUrlsMap` 返回完整 JSON）；
- TLS 握手成功，日志中出现证书 `CN=cloud.huawei.asia`、`CN=hw.dbankcdn.com`；
- 配置下载成功：`get hicloudSysParam config success`；
- `getUserPublicInfo` 请求已发出（日志带 `x-hw-trace-id = 07012_01_1794835507_58098258`），随后进入 `parseErrorByRsp` 响应解析；该日志只是对响应中的错误/状态字段做解析，不是连接异常；
- `CheckGRSUrlsTask: processSuccess`。

所以：**DNS / TLS / 出网 / 华为云服务端均正常，失败点在本地。**

---

## 四、“手机已 Root”的证据链

### 4.1 检测代码

`com/huawei/android/hicloud/utils/c0.java`（`RootUtil`）：

```java
public static boolean a() {   // Build.TAGS 含 test-keys
    String str = Build.TAGS;
    if (str == null || !str.contains("test-keys")) return false;
    u9.l.i("RootUtil", "checkBuildTags: device is rooted");
    return true;
}

public static boolean c(Context context) {  // 经典 root 应用包名
    String[] strArr = {"com.noshufou.android.su", "com.thirdparty.superuser",
        "eu.chainfire.supersu", "com.koushikdutta.superuser",
        "com.zachspong.temprootremovejb", "com.ramdroid.appquarantine"};
    ...
}

public static boolean d() {   // persist.sys.root.status
    String g11 = g();
    if (g11 == null || g11.isEmpty() || "0".equals(g11)) return false;
    u9.l.i("RootUtil", "checkRootProperty: device is rooted");
    return true;
}

public static boolean e() {   // ro.secure == "0"
    if (!"0".equals(h("ro.secure"))) return false;
    u9.l.i("RootUtil", "checkSecureProperty: device is rooted");
    return true;
}

public static boolean f() {   // PATH 下存在 su
    String str = System.getenv("PATH");
    if (str == null) str = "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin";
    for (String str2 : str.split(":")) {
        if (new File(str2 + "/su").exists()) {
            u9.l.i("RootUtil", "checkSu: device is rooted");
            return true;
        }
    }
    return false;
}

public static boolean i(Context context) {
    if (f() || d() || e() || a() || b() || c(context)) return true;
    u9.l.i("RootUtil", "Device is not rooted");
    return false;
}
```

### 4.2 本机逐项结果

| 检查 | 依据 | 本机实际值 | 是否命中 |
|---|---|---|---|
| `checkSu` (f) | `$PATH` 下存在 `su` | `/system/bin/su` 存在，4,892,712 B，root:root | **是** |
| `checkRootProperty` (d) | `persist.sys.root.status` 非空且 ≠ 0 | `10` | **是** |
| `checkSecureProperty` (e) | `ro.secure == "0"` | `1` | 否 |
| `checkBuildTags` (a) | `Build.TAGS` 含 `test-keys` | `release-keys` | 否 |
| 模拟器 (b) | goldfish/generic/google_sdk 等 | 真机 REP-AN00 | 否 |
| root 应用列表 (c) | 6 个经典包名 | 列表内包名均未安装；KernelSU 管理器包名不在列表 | 否 |

补充：`su -c id` 返回 `uid=0(root) ... context=u:r:ksu:s0`，说明 KernelSU 的 root 是**真实生效**的；`/system/bin/su` 就是它提供的。

**因此 `c0.i()` 返回 true，弹窗是正确检测，不是误报。** 即使 KernelSU 的包名不在那 6 个经典包名里，`su` 文件和 `persist.sys.root.status` 已经足够触发。

### 4.3 对话框行为

`WelcomeBaseActivity.m3()`（`WelcomeBaseActivity.java:2407`）：

```java
public void m3() {
    u9.l.d("WelcomeBaseActivity", "rootProcess");
    this.f14532o0 = false;
    if (!td.d.c(this, this.f14542t0)) {
        this.f14532o0 = com.huawei.android.hicloud.utils.c0.i(this);
    }
    if (!this.f14532o0) {
        D3();
        return;
    }
    if (this.f14505c0 == null) {
        this.f14505c0 = new td.d(this, new WelcomeBaseActivity.c(), this.f14542t0);
    }
    ...
    u9.o.v2(this.f14505c0);
}
```

`td/d.java:57` 构造对话框：

```java
setMessage(getString(R$string.root_alert_msg1));
setButton(-1, getString(R$string.root_alert_go_on), onClickListener);   // 继续使用
setButton(-2, getString(R$string.root_alert_not_use), onClickListener); // 暂不使用
```

- 在欢迎页，`m3()` 传入的是自定义监听器 `WelcomeBaseActivity.c`（`WelcomeBaseActivity.java:441`）：
  ```java
  public void onClick(DialogInterface dialogInterface, int i11) {
      dialogInterface.dismiss();
      if (i11 == -1) {                       // 继续使用
          td.d.d(activity, type);            // 记录 is_agree_root_alert_hicloud=true
          activity.D3();                     // 继续进入欢迎流程
      } else if (i11 == -2) {                // 暂不使用
          activity.finish();
      }
  }
  ```
  这与我实测一致：点“继续使用”后对话框消失，欢迎页继续加载，然后才被 `h2()` 拦下。另外，点过“继续使用”后再次冷启动，不再弹 root 对话框——说明 prefs 已记录同意，`m3()` 直接跳过 root 检查（这也解释了为什么后来 logcat 里没有 `RootUtil` 日志）。
- `td.d` 自带的默认监听器 `td.d.a`（其它入口使用，例如 `MainActivity` 的 `new td.d(this, null, 2)`）在引导未结束时还会调用 `e()` 继续流程；欢迎页没有用它。
- `NewHiSyncSettingActivity` 也会把 root 结果通过 `need_root_alert` 传给 `MainActivity`（`MainActivity.java:2851`），主页面同样会弹这个提示。

**所以 Root 提示只是“风险告知”，不是功能封锁。** 点“继续使用”后仍然会走到协议查询，然后被 `h2()` 拦下。

---

## 五、其他相关异常（同一根因的旁证）

这些不是本次“无法连接”的直接原因，但都指向同一个事实：**这个包默认运行在华为系统环境里，HMS Core 是系统应用。**

1. `Base.BaseUtil: getOsBrand exception: java.lang.ClassNotFoundException: com.huawei.system.BuildEx`
   - `d.java:1381` 反射调用 `com.huawei.system.BuildEx.getOsBrand()`；
   - Honor MagicOS 没有这个华为框架类，反射失败被 catch，`getOsBrand()` 返回 null。

2. `Base.AccountUtil: is not Target App or System App` → `getDeviceAccountBrand from provider, accountBrand:` 为空
   - 同一个 `h2()/i2()` 信任逻辑在读取账号品牌时也失败了。

3. 其他模块也在用同样的检查：
   - `wj/b.java:288,463,546`（`HisyncAccountManager`）
   - `k9/g.java:71,100`（`CommonObserverManager`）
   - `ql/a.java:99,137,174`（`AgdProtocolProviderManager`）
   - 日志中可见 `QueryAgreementServiceTask`、`HisyncAccountManager`、`NotifyFileManagerConfigTask` 的同类报错。

4. **账号登录状态也读不到（这一点很关键）。**
   `HisyncAccountManager` 查询的是 `content://com.huawei.hwid.api.provider/has_login`，同样先过 `h2()`：

   ```java
   // wj/b.java:288
   Uri parse = Uri.parse("content://com.huawei.hwid.api.provider/has_login");
   if (!com.huawei.hicloud.base.common.d.h2(this.f56782a, parse)) {
       hk.a.e("HisyncAccountManager", "is not Target App or System App");
   } else if (wj.b.this.E(parse, contentResolver) == 1) {
       z10 = true;
   }
   ...
   iVar.loginResult(z10);   // z10 保持 false
   ```

   实测日志（21:28:36.438）：

   ```text
   E Cloud[17.0.1.701]Base.HisyncAccountManager: is not Target App or System App
   ```

   结果就是：**即使系统里已经登录了华为账号，云空间内部仍会认为“未登录”**（`getLoginResult` 直接返回 0）。这解释了为什么“有 HMS Core + 已登录账号”仍然不可用——它根本没有读到账号状态。

5. 早前系统日志还有：
   - `Permission Denial: opening provider com.huawei.android.hicloud.common.provider.HicloudFeatursProvider from com.huawei.hwid`，缺少 `com.huawei.android.permission.photoshareconfig`；
   - `AccountInConsistentActivity` 在 21:19:47 被拉起。

这些现象共同说明：**HMS Core 以普通用户 APK 的形式装在 Honor 设备上，缺少华为 ROM 里的系统应用身份、平台签名/权限和框架类。** 云空间的核心流程默认这些条件成立，因此会在多个环节失败。

---

## 六、为什么“以前能用/别人能用”，以及可行的处理方向

### 6.1 根因总结

| 现象 | 直接原因 | 性质 |
|---|---|---|
| 无法连接服务器 | `d.h2()` 要求 provider 是系统应用或同签名；本机 HMS Core 是 `/data/app` 普通应用且证书不同 → `31001` → 通用断连文案 | 本地信任校验，非网络 |
| 手机已 Root | `RootUtil` 命中 `/system/bin/su` 和 `persist.sys.root.status=10` | 真实检测，非误报 |
| `getOsBrand` 异常 | Honor ROM 无 `com.huawei.system.BuildEx` | 非华为 ROM 兼容性问题 |

> 注意：**升级/降级 HMS Core 版本不会解决连接问题**。校验看的是“安装位置（是否系统应用）+ 签名”，不是版本号；只要 `com.huawei.hwid` 仍装在 `/data/app` 且证书不同，`h2()` 就仍然返回 false。

### 6.2 可选方向（按风险从低到高）

1. **使用 Honor 自带云空间（推荐）**
   这台是 HONOR 设备，系统自带的是 `com.hihonor.hncloud` 体系；华为云空间 APK 不是该 ROM 的支持路径。换用 Honor 云空间可以避开系统应用身份/签名/框架类的一整套假设。

2. **只想去掉 Root 提示**
   - 该提示不影响功能，点“继续使用”即可；
   - 若坚持消除：需要让 `RootUtil` 的检查全部失败，例如移除 `$PATH` 中的 `su`（KernelSU 会提供 `/system/bin/su`）并把 `persist.sys.root.status` 置为 `0`。这会削弱/破坏 root 环境，且重启或重新 root 后可能恢复；
   - `persist.sys.root.status` 不是 AOSP 标准属性，通常是 root 方案/ROM 写入的；它会在重启后保留，所以只“临时取消 root”不一定能让提示消失；
   - 单纯改包名/隐藏 KernelSU 管理器不够，因为本机命中的是 `su` 文件和系统属性，不是那 6 个经典包名。

3. **让 HMS Core 变成系统应用（高风险，未执行）**
   把 `com.huawei.hwid` 放入 `/system/priv-app`（或等效的 systemless 挂载 + 重新扫描/重启），使 `ApplicationInfo.flags` 带上 `FLAG_SYSTEM`，`h2()` 的第一条件即可通过。
   风险：
   - 需要解锁/可写 system 或内核级挂载；
   - 可能破坏 HMS、OTA、Play Integrity、系统更新；
   - 即使过了 `h2()`，`BuildEx` 缺失、`accountBrand` 为空、其他 `is not Target App or System App` 检查仍可能让登录/同步失败，**不保证可用**。

4. **改包/改签名绕过 `h2()`（不建议）**
   修改 APK 会破坏华为签名与账号/HMS 的信任链，可能违反服务条款，且升级即失效。

> 本次分析全程只读：只做了 `am start`、`uiautomator dump`、`screencap`、`dumpsys`、`getprop`、`logcat`、拉取 APK 和 `su -c` 只读命令，没有修改系统分区、没有改属性、没有改包。

---

## 七、复现/验证命令（只读）

```bash
# 1. 确认 HMS Core 是用户应用
adb shell dumpsys package com.huawei.hwid | grep -E 'codePath|flags=|pkgFlags='
adb shell pm path com.huawei.hwid

# 2. 确认 hidisk 的签名（与 hwid 不同）
adb pull /data/app/.../com.huawei.hwid-.../base.apk hwid.apk
apksigner verify --print-certs hwid.apk
apksigner verify --print-certs installed-base.apk

# 3. 确认 root 检测命中项
adb shell getprop persist.sys.root.status      # 10
adb shell getprop ro.secure                    # 1
adb shell getprop ro.build.tags                # release-keys
adb shell ls -l /system/bin/su                 # 存在

# 4. 复现错误并看日志
adb shell logcat -c
adb shell am start -n com.huawei.hidisk/com.huawei.android.hicloud.ui.activity.NewHiSyncSettingActivity
adb shell logcat -d | grep -E 'is not Target App|Terms query failed|31001|getUserPublicInfo'
```

---

## 八、附件

| 文件 | 说明 |
|---|---|
| `analysis/s1.png` | Root 风险对话框截图 |
| `analysis/s3.png` | “无法连接服务器”错误页截图 |
| `analysis/ui1.xml` | Root 对话框 UI 层级 |
| `analysis/ui3.xml` | 错误页 UI 层级（含 `set_get_info_timeout_str`） |
| `analysis/apk/installed-base.apk` | 设备上 17.0.1.701 的 base.apk |
| `analysis/apk/hwid-base.apk` | HMS Core 6.16.2.300 base.apk（用于签名对比） |
| `analysis/jadx-installed/sources` | 反编译源码 |
| `analysis/strings-all.txt` | 全量字符串 |
| `analysis/res.txt` | aapt2 资源表 |
