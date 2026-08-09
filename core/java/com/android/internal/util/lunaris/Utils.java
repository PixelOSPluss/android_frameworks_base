/*
 * Copyright (C) 2017-2025 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.lunaris;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.ActivityThread;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.SensorPrivacyManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncTask;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;

import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;
import android.widget.Toast;
import com.android.internal.R;

import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;

import android.util.Log;

import com.android.internal.statusbar.IStatusBarService;

import java.util.List;

import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
import static android.provider.Settings.Global.ZEN_MODE_OFF;

public class Utils {

    private static final String TAG = "Utils";

    public static void restartApp(String appName, Context context) {
        new RestartAppTask(appName, context).execute();
    }

    private static class RestartAppTask extends AsyncTask<Void, Void, Void> {
        private Context mContext;
        private String mApp;

        public RestartAppTask(String appName, Context context) {
            super();
            mContext = context;
            mApp = appName;
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                ActivityManager am =
                        (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                IActivityManager ams = ActivityManager.getService();
                for (ActivityManager.RunningAppProcessInfo app: am.getRunningAppProcesses()) {
                    if (mApp.equals(app.processName)) {
                        ams.killApplicationProcess(app.processName, app.uid);
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    public static boolean isPackageInstalled(Context context, String packageName, boolean ignoreState) {
        if (packageName != null) {
            try {
                PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
                if (!pi.applicationInfo.enabled && !ignoreState) {
                    return false;
                }
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }
        return true;
    }

    public static boolean isPackageInstalled(Context context, String packageName) {
        return isPackageInstalled(context, packageName, true);
    }

    public static boolean isPackageEnabled(Context context, String packageName) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            return pi.applicationInfo.enabled;
        } catch (PackageManager.NameNotFoundException notFound) {
            return false;
        }
    }

    public static void switchScreenOff(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm!= null) {
            pm.goToSleep(SystemClock.uptimeMillis());
        }
    }

    public static boolean deviceHasFlashlight(Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
    }

    public static boolean hasNavbarByDefault(Context context) {
        boolean needsNav = context.getResources().getBoolean(
                com.android.internal.R.bool.config_showNavigationBar);
        String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
        if ("1".equals(navBarOverride)) {
            needsNav = false;
        } else if ("0".equals(navBarOverride)) {
            needsNav = true;
        }
        return needsNav;
    }

    public static void restartSystemUI(Context context) {
        new RestartAppTask("com.android.systemui", context).execute();
    }

    public static void restartSystemUI() {
        try {
            IActivityManager ams = ActivityManager.getService();
            if (ams != null) {
                List<ActivityManager.RunningAppProcessInfo> pids = ams.getRunningAppProcesses();
                if (pids != null) {
                    for (ActivityManager.RunningAppProcessInfo app : pids) {
                        if ("com.android.systemui".equals(app.processName)) {
                            ams.killApplicationProcess(app.processName, app.uid);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    public static void toggleOverlay(Context context, String overlayName, boolean enable) {
        OverlayManager overlayManager = context.getSystemService(OverlayManager.class);
        if (overlayManager == null) {
            Log.e(TAG, "OverlayManager is not available");
            return;
        }

        OverlayIdentifier overlayId = getOverlayID(overlayManager, overlayName);
        if (overlayId == null) {
            Log.e(TAG, "Overlay ID not found for " + overlayName);
            return;
        }

        OverlayManagerTransaction.Builder transaction = new OverlayManagerTransaction.Builder();
        transaction.setEnabled(overlayId, enable, UserHandle.USER_CURRENT);

        try {
            overlayManager.commit(transaction.build());
        } catch (Exception e) {
            Log.e(TAG, "Error toggling overlay", e);
        }
    }

    private static OverlayIdentifier getOverlayID(OverlayManager overlayManager, String name) {
        try {
            if (name.contains(":")) {
                String[] parts = name.split(":");
                List<OverlayInfo> infos = overlayManager.getOverlayInfosForTarget(parts[0], UserHandle.CURRENT);
                for (OverlayInfo info : infos) {
                    if (parts[1].equals(info.getOverlayName())) return info.getOverlayIdentifier();
                }
            } else {
                OverlayInfo info = overlayManager.getOverlayInfo(name, UserHandle.CURRENT);
                if (info != null) return info.getOverlayIdentifier();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error retrieving overlay ID", e);
        }
        return null;
    }

    public static String getDefaultLauncher(Context context) {
        final RoleManager roleManager = context.getSystemService(RoleManager.class);
        final String packageName = CollectionUtils.firstOrNull(
                roleManager.getRoleHolders(RoleManager.ROLE_HOME));
        return packageName != null ? packageName : "";
    }

    public static void forceStopDefaultLauncher(Context context) {
        final ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        try {
            activityManager.forceStopPackageAsUser(getDefaultLauncher(context), UserHandle.USER_CURRENT);
        } catch (Exception ignored) {}
    }
}
