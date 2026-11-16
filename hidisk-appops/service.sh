#!/system/bin/sh
# HiDisk AppOps companion for the LSPosed HiDisk Hook.
# Gallery's cloud-album SDK writes thumbnails under /sdcard/.photoShare,
# which MediaProvider owns. Without all-files access hidisk reads those
# files with EACCES (upload ret=1011) and can fail to create download
# thumbnails. Keep the appop granted across reboots.

MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

/system/bin/appops set com.huawei.hidisk MANAGE_EXTERNAL_STORAGE allow
