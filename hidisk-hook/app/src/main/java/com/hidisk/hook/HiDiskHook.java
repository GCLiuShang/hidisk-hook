package com.hidisk.hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import dalvik.system.DexFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Minimal LSPosed module for 华为云空间 (com.huawei.hidisk).
 *
 * On this Honor ROM, HMS Core (com.huawei.hwid) is a normal /data/app with a
 * different signing certificate, so hidisk's local trust check
 * com.huawei.hicloud.base.common.d.h2(Context, Uri) always returns false.
 * That check is the gate in front of the HMS agreement/provider path and the
 * account-state path, and it is the direct cause of
 * "无法连接服务器，请点击屏幕再试".
 *
 * A second Honor-specific problem appears once the app gets past that gate:
 * com.huawei.android.fsm.HwFoldScreenManagerEx does not exist on this ROM.
 * d.j1() catches Exception, but the missing class is a NoClassDefFoundError,
 * so it escapes and aborts inflation of BackupDeviceView; d.g1() has no
 * try/catch at all. Both are hooked to false (this is not a foldable).
 *
 * The module does not touch the network stack, HMS Core, the APK signature,
 * or root detection.
 */
public class HiDiskHook implements IXposedHookLoadPackage {

    private static final String TAG = "HiDiskHook";
    private static final String HIDISK = "com.huawei.hidisk";
    private static final String HIDISK_DS_PROCESS = "com.huawei.android.ds";
    private static final String PHOTOS = "com.hihonor.photos";
    private static final String CALENDAR_PROVIDER = "com.android.providers.calendar";
    private static final String GALLERY_CALLBACK_UTILS =
            "com.hihonor.gallery.photoshare.cloudsdk.CloudAlbumSdkCallbackUtils";
    private static final String GALLERY_CALLBACK =
            "com.hihonor.gallery.cloudsdk.adapter.api.CloudAlbumSdkCallback";
    private static final String GALLERY_MEDIA_UTILS =
            "com.hihonor.gallery.media.GalleryMediaUtils";
    private static final String HWID = "com.huawei.hwid";
    private static final String HWID_PROVIDER = "com.huawei.hwid.api.provider";
    private static final String CONTACT_ACCOUNT = "com.huawei.android.hicloud.sync.contact.e";
    private static final String CONTACT_MANAGER = "com.huawei.android.hicloud.sync.contact.ContactManager";
    private static final String HONOR_CONTACT_TYPE = "com.android.hihonor.phone";
    private static final String HONOR_CALENDAR_TYPE = "com.android.hihonor.phone";
    private static final String CALENDAR_HELPER =
            "com.android.providers.calendar.sync.EventSyncHelper";
    private static final String CALENDAR_MODEL =
            "com.android.providers.calendar.sync.event.HwSyncCalendar";

    private static final String COMPAT_HW_LIST_VIEW = "huawei.android.widget.ListView";
    /** R.id.main_menu_items in the installed 17.0.1.701 APK. */
    private static final int ID_MAIN_MENU_ITEMS = 0x7f0a0b74;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.classLoader == null) {
            return;
        }
        // Honor Gallery: make the upload query see media-level dirty rows even
        // when gallery_album.dirty has been reset to 0.
        if (PHOTOS.equals(lpparam.packageName)) {
            XposedBridge.log(TAG + ": loaded in package=" + lpparam.packageName);
            hookGalleryAutoSync(lpparam);
            return;
        }

        // Honor CalendarProvider selects sync calendars with
        // account_type = "com.android." + GtaPkgNameConstant.VENDOR + ".phone".
        // On this ROM the vendor constant is empty, so it selects the empty
        // "com.android..phone" classic calendar and never sees the real
        // "com.android.hihonor.phone" events. Append the real calendars.
        if (CALENDAR_PROVIDER.equals(lpparam.packageName)) {
            XposedBridge.log(TAG + ": loaded in package=" + lpparam.packageName);
            hookCalendarSync(lpparam);
            return;
        }

        // The cloud-sync provider runs in the com.huawei.android.ds process,
        // which belongs to the same package/UID. Hook both just in case.
        if (!HIDISK.equals(lpparam.packageName)
                && !HIDISK_DS_PROCESS.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": loaded in package=" + lpparam.packageName);
        hookCompatDexInjection(lpparam);
        hookH2(lpparam);
        hookI2(lpparam);
        hookFoldScreen(lpparam);
        hookCalendarCaller(lpparam);
        hookMainCompatViews(lpparam);
        hookContactBridge(lpparam);
    }

    /**
     * Honor ROM does not ship huawei.android.widget.HwTextView/ListView, but
     * hidisk's storage_manage layouts and the MainActivity field reference
     * them. ART resolves field types through the native class linker, not
     * through ClassLoader.loadClass(), so a Java-level loadClass hook cannot
     * inject them (confirmed: NoClassDefFoundError still occurs).
     *
     * Instead append this module's dex to the app's BaseDexClassLoader path
     * list. The native linker then sees the shim classes just like classes in
     * the app's own dex. No app APK change / re-sign is involved.
     */
    private void hookCompatDexInjection(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Application) {
                                installCompatDex(lpparam, (Application) param.thisObject);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked Application.onCreate for compat dex");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook Application.onCreate: " + t);
        }
    }

    private void installCompatDex(final XC_LoadPackage.LoadPackageParam lpparam,
                                  Application app) {
        try {
            ClassLoader appLoader = lpparam.classLoader;
            if (appLoader == null || app == null) {
                return;
            }
            String moduleApk = null;
            try {
                PackageInfo pi = app.getPackageManager()
                        .getPackageInfo("com.hidisk.hook", 0);
                moduleApk = pi.applicationInfo.sourceDir;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": cannot resolve module apk: " + t);
                return;
            }
            File cacheDir = app.getCacheDir();
            if (cacheDir == null) {
                XposedBridge.log(TAG + ": compat dex skipped (no cache dir)");
                return;
            }
            File dexFile = new File(cacheDir, "hidisk-compat-shims.dex");
            extractDex(moduleApk, dexFile);

            Class<?> dexFileClass = Class.forName("dalvik.system.DexFile");
            java.lang.reflect.Method loadDex = dexFileClass.getDeclaredMethod(
                    "loadDex", String.class, String.class, int.class);
            loadDex.setAccessible(true);
            Object dex = loadDex.invoke(null, dexFile.getAbsolutePath(), null, 0);
            if (dex == null) {
                XposedBridge.log(TAG + ": compat dex load returned null");
                return;
            }

            Class<?> elementClass = Class.forName("dalvik.system.DexPathList$Element");
            Object element = null;
            StringBuilder ctorLog = new StringBuilder();
            for (Constructor<?> ctor : elementClass.getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                ctorLog.append(" [");
                for (Class<?> pc : p) {
                    ctorLog.append(pc.getSimpleName()).append(',');
                }
                ctorLog.append(']');
                if (p.length == 1 && p[0] == dexFileClass) {
                    ctor.setAccessible(true);
                    element = ctor.newInstance(dex);
                } else if (p.length == 2 && p[0] == dexFileClass && p[1] == File.class) {
                    ctor.setAccessible(true);
                    element = ctor.newInstance(dex, dexFile);
                }
            }
            if (element == null) {
                XposedBridge.log(TAG + ": compat dex skipped (no Element ctor)" + ctorLog);
                return;
            }

            Object appPathList = getField(appLoader, "pathList");
            Field dexElements = findField(appPathList.getClass(), "dexElements");
            dexElements.setAccessible(true);
            Object oldElements = dexElements.get(appPathList);
            int oldLen = Array.getLength(oldElements);
            Class<?> component = oldElements.getClass().getComponentType();
            Object merged = Array.newInstance(component, oldLen + 1);
            for (int i = 0; i < oldLen; i++) {
                Array.set(merged, i, Array.get(oldElements, i));
            }
            Array.set(merged, oldLen, element);
            dexElements.set(appPathList, merged);
            XposedBridge.log(TAG + ": appended copied compat dex to app loader ("
                    + oldLen + " -> " + (oldLen + 1) + ")");
        } catch (Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            XposedBridge.log(TAG + ": compat dex append failed: " + t + " / cause=" + cause);
        }
    }



    private static void extractDex(String apkPath, File out) throws Exception {
        try (ZipFile zip = new ZipFile(apkPath);
             InputStream in = zip.getInputStream(zip.getEntry("assets/shims.dex"));
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " in " + type);
    }

    /**
     * MainActivity.jb() stores the main-menu ListView in a field whose type is
     * huawei.android.widget.ListView, but main_menu_window.xml inflates an AOSP
     * android.widget.ListView on this ROM.  Injecting the shim class fixes the
     * field type; this hook replaces the actual AOSP instance so the field
     * assignment (and pb()'s setAdapter) sees the shim instance.  The menu
     * content, adapter and click handling stay unchanged.
     */
    private void hookMainCompatViews(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    LayoutInflater.class,
                    "inflate",
                    int.class,
                    ViewGroup.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof View)) {
                                return;
                            }
                            View root = (View) result;
                            View old = root.findViewById(ID_MAIN_MENU_ITEMS);
                            if (old == null
                                    || !(old instanceof android.widget.ListView)
                                    || COMPAT_HW_LIST_VIEW.equals(old.getClass().getName())) {
                                return;
                            }
                            ViewGroup parent = old.getParent() instanceof ViewGroup
                                    ? (ViewGroup) old.getParent() : null;
                            if (parent == null) {
                                return;
                            }
                            try {
                                android.widget.ListView oldList =
                                        (android.widget.ListView) old;
                                Class<?> compatClass = Class.forName(
                                        COMPAT_HW_LIST_VIEW, true, lpparam.classLoader);
                                android.widget.ListView compat =
                                        (android.widget.ListView) compatClass
                                                .getConstructor(Context.class)
                                                .newInstance(root.getContext());
                                compat.setId(ID_MAIN_MENU_ITEMS);
                                compat.setDivider(oldList.getDivider());
                                compat.setDividerHeight(oldList.getDividerHeight());
                                try {
                                    compat.setSelector(oldList.getSelector());
                                } catch (Throwable ignored) {
                                    // getSelector() is not available on every ROM.
                                }
                                compat.setCacheColorHint(oldList.getCacheColorHint());
                                compat.setPadding(
                                        oldList.getPaddingLeft(),
                                        oldList.getPaddingTop(),
                                        oldList.getPaddingRight(),
                                        oldList.getPaddingBottom());
                                compat.setScrollBarStyle(oldList.getScrollBarStyle());

                                int index = parent.indexOfChild(old);
                                ViewGroup.LayoutParams lp = old.getLayoutParams();
                                parent.removeViewAt(index);
                                if (lp != null) {
                                    parent.addView(compat, index, lp);
                                } else {
                                    parent.addView(compat, index);
                                }
                                XposedBridge.log(TAG + ": replaced main-menu ListView");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": menu ListView replace failed: " + t);
                            }

                        }
                    });
            XposedBridge.log(TAG + ": hooked LayoutInflater.inflate(int, ViewGroup)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook LayoutInflater.inflate: " + t);
        }
    }


    /**
     * Honor ROM does not ship com.huawei.android.fsm.HwFoldScreenManagerEx.
     * d.j1() catches Exception but the missing class surfaces as
     * NoClassDefFoundError (an Error), so it escapes and kills the inflation
     * of BackupDeviceView / TempMainPageRecordFragment. On a non-Huawei
     * foldable the correct answer is "not expanded", i.e. false.
     */
    private void hookFoldScreen(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.huawei.hicloud.base.common.d",
                    lpparam.classLoader,
                    "j1",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.FALSE);
                        }
                    });
            XposedBridge.log(TAG + ": hooked d.j1()");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook d.j1: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.huawei.hicloud.base.common.d",
                    lpparam.classLoader,
                    "g1",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.FALSE);
                        }
                    });
            XposedBridge.log(TAG + ": hooked d.g1()");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook d.g1: " + t);
        }
    }

    /**
     * Honor Cloud owns the local contacts on this ROM. hidisk delegates contact
     * sync to com.hihonor.contacts.sync and its ContactManager only knows the
     * Huawei account type "com.android.huawei.phone". For the contacts-only
     * bridge we (1) stop the delegation and (2) make hidisk treat the local
     * Honor account as its own account type.
     */
    private void hookContactBridge(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "pp.a",
                    lpparam.classLoader,
                    "Y",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(Boolean.FALSE);
                        }
                    });
            XposedBridge.log(TAG + ": hooked pp.a.Y(Context) -> false");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook pp.a.Y: " + t);
        }

        try {
            Class<?> account = Class.forName(CONTACT_ACCOUNT, true, lpparam.classLoader);
            Field typeField = account.getDeclaredField("b");
            typeField.setAccessible(true);
            typeField.set(null, HONOR_CONTACT_TYPE);
            XposedBridge.log(TAG + ": contact account type -> " + HONOR_CONTACT_TYPE);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to patch contact account type: " + t);
        }

        try {
            Class<?> manager = Class.forName(CONTACT_MANAGER, true, lpparam.classLoader);
            Field accountsField = manager.getDeclaredField("r");
            accountsField.setAccessible(true);
            Object value = accountsField.get(null);
            if (value instanceof String[]) {
                String[] accounts = (String[]) value;
                if (accounts.length > 1) {
                    accounts[1] = HONOR_CONTACT_TYPE;
                }
            }
            XposedBridge.log(TAG + ": ContactManager account array patched");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to patch ContactManager account array: " + t);
        }
    }

    /**
     * CalendarProvider's Huawei CloudSync SDK calls SyncLogicService with the
     * calendar provider as caller. On this Honor build the caller package
     * cannot be resolved, so startSync is rejected ("realPkgName" empty).
     * Whitelist the calendar provider and force a non-empty caller package.
     */
    private void hookCalendarCaller(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.huawei.android.hicloud.sync.service.aidl.SyncLogicService",
                    lpparam.classLoader,
                    "getRealCallingPackageName",
                    Context.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!"calendar".equals(param.args[1])) {
                                return;
                            }
                            Object result = param.getResult();
                            if (result == null || ((String) result).isEmpty()) {
                                param.setResult("com.android.providers.calendar");
                                XposedBridge.log(TAG + ": calendar realPkgName -> com.android.providers.calendar");
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked SyncLogicService.getRealCallingPackageName");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook calendar caller: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.huawei.android.hicloud.syncdrive.util.DriveSyncUtil",
                    lpparam.classLoader,
                    "k",
                    String.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Only the calendar provider is allowed; do not open
                            // the gate to arbitrary callers that merely claim
                            // syncType=calendar.
                            if ("calendar".equals(param.args[0])
                                    && "com.android.providers.calendar".equals(param.args[1])) {
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked DriveSyncUtil.k(calendar)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook DriveSyncUtil.k: " + t);
        }
    }

    /**
     * d.h2(Context, Uri) decides whether a content provider may be trusted:
     * provider must be FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP, or share hidisk's
     * signing certificate. We only override it for the HMS provider.
     */
    private void hookH2(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.huawei.hicloud.base.common.d",
                    lpparam.classLoader,
                    "h2",
                    Context.class,
                    Uri.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object arg = (param.args != null && param.args.length > 1)
                                    ? param.args[1] : null;
                            if (!(arg instanceof Uri)) {
                                return;
                            }
                            String authority = ((Uri) arg).getAuthority();
                            if (authority != null
                                    && (HWID_PROVIDER.equals(authority)
                                        || HWID.equals(authority)
                                        || authority.startsWith(HWID + "."))) {
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked d.h2(Context, Uri)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook d.h2: " + t);
        }
    }

    /**
     * d.i2(Context, String) is the same trust test keyed by package name; it is
     * used by the notification/AGD modules. Return true only for HMS Core.
     */
    private void hookI2(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.huawei.hicloud.base.common.d",
                    lpparam.classLoader,
                    "i2",
                    Context.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object arg = (param.args != null && param.args.length > 1)
                                    ? param.args[1] : null;
                            if (HWID.equals(arg)) {
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked d.i2(Context, String)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook d.i2: " + t);
        }
    }

    /**
     * Honor Gallery keeps gallery_album.dirty at 0 while gallery_media.dirty is
     * 1 for newly scanned files. The upload callback therefore sees no album
     * candidate. We only relax the *upload candidate* SQL: an album is eligible
     * if it is itself dirty OR it contains dirty (1/32) non-recycled media.
     * The uploadStatus/cloud condition is intentionally preserved, so albums
     * disabled by the user still do not upload.
     */
    private void hookGalleryAutoSync(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    GALLERY_CALLBACK_UTILS,
                    lpparam.classLoader,
                    "getGeneralAlbums",
                    String.class,
                    String[].class,
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length < 1
                                    || !(param.args[0] instanceof String)) {
                                return;
                            }
                            String sql = (String) param.args[0];
                            String rewritten = rewriteUploadAlbumSql(sql);
                            if (!rewritten.equals(sql)) {
                                param.args[0] = rewritten;
                                XposedBridge.log(TAG + ": relaxed upload album SQL");
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked getGeneralAlbums(String, String[], long)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook getGeneralAlbums: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    GALLERY_CALLBACK,
                    lpparam.classLoader,
                    "needSyncUploadGeneral",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(param.getResult())) {
                                return;
                            }
                            try {
                                Class<?> utils = Class.forName(
                                        GALLERY_MEDIA_UTILS, false, lpparam.classLoader);
                                Object count = XposedHelpers.callStaticMethod(
                                        utils,
                                        "queryCount",
                                        "dirty IN (1,32) AND local_media_id > 0"
                                                + " AND relative_bucket_id IN"
                                                + " (SELECT relativeBucketId FROM gallery_album"
                                                + " WHERE uploadStatus = 1 OR cloud = 1)",
                                        null);
                                if (count instanceof Number
                                        && ((Number) count).intValue() > 0) {
                                    param.setResult(Boolean.TRUE);
                                    XposedBridge.log(TAG + ": needSyncUploadGeneral -> true via media dirty");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": gallery dirty fallback failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked needSyncUploadGeneral()");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook needSyncUploadGeneral: " + t);
        }
    }

    private void hookCalendarSync(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    CALENDAR_HELPER,
                    lpparam.classLoader,
                    "querySyncCalendars",
                    new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof List)) {
                                return;
                            }
                            List<Object> calendars = (List<Object>) result;
                            try {
                                Object helper = param.thisObject;
                                Context context = (Context) readField(helper, "mContext");
                                Class<?> model = Class.forName(
                                        CALENDAR_MODEL, false, lpparam.classLoader);
                                String[] projection = (String[]) readStaticField(
                                        model, "SYNC_CALENDAR_PROJECTION");
                                Set<Long> existing = new HashSet<>();
                                for (Object calendar : calendars) {
                                    Object id = invoke(calendar, "getId");
                                    if (id instanceof Long) {
                                        existing.add((Long) id);
                                    } else if (id instanceof Number) {
                                        existing.add(((Number) id).longValue());
                                    }
                                }
                                Cursor cursor = context.getContentResolver().query(
                                        CalendarContract.Calendars.CONTENT_URI,
                                        projection,
                                        "account_type=? AND sync_events=1 AND deleted=0",
                                        new String[]{HONOR_CALENDAR_TYPE},
                                        null);
                                if (cursor == null) {
                                    XposedBridge.log(TAG + ": calendar query returned null");
                                    return;
                                }
                                long firstId = -1;
                                try {
                                    while (cursor.moveToNext()) {
                                        Object calendar = model.getDeclaredConstructor()
                                                .newInstance();
                                        invoke(calendar,
                                                "setSyncCalendarFromCalendarCursor", cursor);
                                        Object idObj = invoke(calendar, "getId");
                                        long id = idObj instanceof Number
                                                ? ((Number) idObj).longValue() : -1;
                                        if (id < 0 || existing.contains(id)) {
                                            continue;
                                        }
                                        existing.add(id);
                                        calendars.add(calendar);
                                        if (firstId < 0) {
                                            firstId = id;
                                        }
                                    }
                                } finally {
                                    cursor.close();
                                }
                                if (firstId > 0) {
                                    writeLongField(helper, "mLocalPhoneId", firstId);
                                    XposedBridge.log(TAG + ": calendar sync calendars appended,"
                                            + " localPhoneId=" + firstId
                                            + ", count=" + calendars.size());
                                } else {
                                    XposedBridge.log(TAG + ": no Honor calendar appended");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": calendar querySyncCalendars hook failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked EventSyncHelper.querySyncCalendars()");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook calendar sync: " + t);
        }
    }

    private String rewriteUploadAlbumSql(String sql) {
        if (sql == null) {
            return null;
        }
        String marker = "EXISTS (SELECT 1 FROM gallery_media WHERE ";
        if (!sql.contains("dirty = ?")
                || !sql.contains("uploadStatus = 1 OR cloud = 1")
                || !sql.contains(marker)
                || sql.contains("(dirty = 1 OR dirty = 32)")) {
            return sql;
        }
        return sql.replace(marker, marker + "(dirty = 1 OR dirty = 32) AND ");
    }


    private static Object readField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object readStaticField(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void writeLongField(Object target, String name, long value)
            throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setLong(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invoke(Object target, String name, Object... args)
            throws Exception {
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }
}
