package com.topjohnwu.magisk.asyncs;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.widget.Toast;

import com.topjohnwu.magisk.MagiskManager;
import com.topjohnwu.magisk.R;
import com.topjohnwu.magisk.container.JarMap;
import com.topjohnwu.magisk.container.Policy;
import com.topjohnwu.magisk.utils.Utils;
import com.topjohnwu.magisk.utils.ZipUtils;

import java.io.File;
import java.util.List;
import java.util.jar.JarEntry;

public class HideManager extends ParallelTask<Void, Void, Boolean> {

    private static final String UNHIDE_APK = "unhide.apk";
    private static final String ANDROID_MANIFEST = "AndroidManifest.xml";
    private static final byte[] UNHIDE_PKG_NAME = "com.topjohnwu.unhide\0".getBytes();

    public HideManager(Context context) {
        super(context);
    }

    @Override
    protected void onPreExecute() {
        getMagiskManager().toast(R.string.hide_manager_toast, Toast.LENGTH_SHORT);
    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        MagiskManager mm = getMagiskManager();
        if (mm == null)
            return false;

        // Generate a new unhide app with random package name
        File unhideAPK = new File(Environment.getExternalStorageDirectory() + "/MagiskManager", "unhide.apk");
        unhideAPK.getParentFile().mkdirs();
        String pkg;

        try {
            JarMap asset = new JarMap(mm.getAssets().open(UNHIDE_APK));
            JarEntry je = new JarEntry(ANDROID_MANIFEST);
            byte xml[] = asset.getRawData(je);
            int offset = -1;

            // Linear search pattern offset
            for (int i = 0; i < xml.length - UNHIDE_PKG_NAME.length; ++i) {
                boolean match = true;
                for (int j = 0; j < UNHIDE_PKG_NAME.length; ++j) {
                    if (xml[i + j] != UNHIDE_PKG_NAME[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    offset = i;
                    break;
                }
            }
            if (offset < 0)
                return false;

            // Patch binary XML with new package name
            pkg = Utils.genPackageName("com.", UNHIDE_PKG_NAME.length - 1);
            System.arraycopy(pkg.getBytes(), 0, xml, offset, pkg.length());
            asset.getOutputStream(je).write(xml);

            // Sign the APK
            ZipUtils.signZip(mm, asset, unhideAPK, false);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        // Install the application
        List<String> ret = getShell().su("pm install " + unhideAPK + ">/dev/null && echo true || echo false");
        unhideAPK.delete();
        if (!Utils.isValidShellResponse(ret) || !Boolean.parseBoolean(ret.get(0)))
            return false;

        try {
            // Allow the application to gain root by default
            PackageManager pm = mm.getPackageManager();
            int uid = pm.getApplicationInfo(pkg, 0).uid;
            Policy policy = new Policy(uid, pm);
            policy.policy = Policy.ALLOW;
            policy.notification = false;
            policy.logging = false;
            mm.suDB.addPolicy(policy);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return false;
        }

        // Hide myself!
        getShell().su_raw("pm hide " + mm.getPackageName());
        return true;
    }

    @Override
    protected void onPostExecute(Boolean b) {
        MagiskManager mm = getMagiskManager();
        if (mm == null)
            return;
        if (!b) {
            mm.toast(R.string.hide_manager_fail_toast, Toast.LENGTH_LONG);
        }
        super.onPostExecute(b);
    }
}
