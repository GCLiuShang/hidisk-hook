# 华为云空间激活模块后黑屏：根因与修复

> 说明：本文为实验记录，设备 IP、账号、手机号等已脱敏；文中 `analysis/`、`hidisk-hook/` 为仓库内相对路径。


## 现象
LSPosed 模块激活后，打开 `com.huawei.hidisk` 只显示纯黑背景（状态栏还在），
`splash` 窗口一直不撤；`uiautomator dump` 返回
`ERROR: null root node returned by UiTestAutomationBridge`。

## 证据链
1. `MainActivity` 处于 `RESUMED`，但 `dumpsys window` 里仍有
   `Splash Screen com.huawei.hidisk`（`mDrawState=HAS_DRAWN`）。
2. logcat：
   ```
   E TransactionExecutor: Failed to execute the transaction: ...
     LaunchActivityItem{... MainActivity ...}
     ResumeActivityItem{...}
   ```
   随后没有 `onCreate` 完成日志，也没有首帧。
3. 给模块加 `LayoutInflater.inflate` / `BackupDeviceView.initView` 诊断 hook 后拿到完整栈：
   ```
   android.view.InflateException: Binary XML file line #188 in
     com.huawei.hidisk:layout/main_layout:
     Error inflating class com.huawei.android.hicloud.ui.uiextend.BackupDeviceView
   Caused by: java.lang.NoClassDefFoundError: Failed resolution of:
     Lcom/huawei/android/fsm/HwFoldScreenManagerEx;
     at com.huawei.hicloud.base.common.d.j1(Unknown Source:3)
     at u9.o.c1(Unknown Source:40)
     at u9.o.t(Unknown Source:7)
     at ...TempMainPageRecordFragment.d(Unknown Source:51)
     at ...TempMainPageRecordFragment.onCreateView(Unknown Source:91)
     at ...BackupDeviceView.initView(Unknown Source:62)
     at ...BackupDeviceView.<init>(SourceFile:12)
   ```
4. 追加崩溃：
   ```
   FATAL EXCEPTION: main
   java.lang.NoClassDefFoundError: Lcom/huawei/android/fsm/HwFoldScreenManagerEx;
     at com.huawei.hicloud.base.common.d.g1(Unknown Source:0)
     at ...RecommendCardManager.getCurrentProportion
     at ...SeparatelyBannerFragment.f
     at ...MainActivity.A2(HiSyncReceiver.onReceive)
   ```

## 根因
本机是 Honor ROM（REP-AN00，MagicOS 9），没有华为的
`com.huawei.android.fsm.HwFoldScreenManagerEx`。

- `d.j1()` 里 `catch (Exception)`，但 `NoClassDefFoundError` 是 `Error`，
  所以异常直接逃逸，导致 `BackupDeviceView` 的构造/布局 inflate 失败，
  `MainActivity` 创建失败，只剩 splash。
- `d.g1()` 完全没有 try/catch，`RecommendCardManager` 调用时也会在
  主线程崩溃。

这不是网络问题，也不是 hook 没加载；是 Honor ROM 缺少华为折叠屏框架类。

## 修复
在 LSPosed 模块中把两个方法固定为 `false`（本机不是折叠屏）：

```java
XposedHelpers.findAndHookMethod(
    "com.huawei.hicloud.base.common.d", lpparam.classLoader, "j1",
    new XC_MethodHook() {
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(Boolean.FALSE);
        }
    });

XposedHelpers.findAndHookMethod(
    "com.huawei.hicloud.base.common.d", lpparam.classLoader, "g1",
    new XC_MethodHook() {
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(Boolean.FALSE);
        }
    });
```

最终模块同时 hook：

- `d.h2(Context, Uri)` -> 对 HMS provider 返回 true
- `d.i2(Context, String)` -> 对 `com.huawei.hwid` 返回 true
- `d.j1()` -> false
- `d.g1()` -> false

## 验证结果
修复后重新打开（`NewHiSyncSettingActivity` 会自动进入 `MainActivity`）：

- 不再黑屏；
- 能显示“我的云空间”主页：容量 13.3 GB/50 GB、云备份、图库、联系人、
  “使用云空间的应用”等；
- 首次进入会弹“是否允许‘云空间’读取设备应用列表？”，允许即可；
- 没有 `FATAL EXCEPTION` / `InflateException`。

截图：`analysis/fixed3.png`

## 产物
- 模块源码：`hidisk-hook`
- APK：`hidisk-hook/build/HiDiskHook.apk`
- 包名：`com.hidisk.hook`
- SHA-256：`a2e55024d998449eddf679c20f9b27e26dd98252b913c6809f0fb897cc3e5e21`
