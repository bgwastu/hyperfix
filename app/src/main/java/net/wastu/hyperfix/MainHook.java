package net.wastu.hyperfix;

import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.UserHandle;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.UserManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.lang.ref.WeakReference;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.media.ExifInterface;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {
    private static final Set<Object> sWorkTiles = Collections.synchronizedSet(Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>()));
    private static volatile boolean sWorkTileReceiverRegistered = false;

    public static class ClearIdentityHook extends XC_MethodHook {
        private static final ThreadLocal<Long> tokenHolder = new ThreadLocal<Long>();

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            tokenHolder.set(Binder.clearCallingIdentity());
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Long token = tokenHolder.get();
            if (token != null) {
                Binder.restoreCallingIdentity(token.longValue());
                tokenHolder.remove();
            }
        }
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        // 1. Fix Screenshot in Work Profile
        if ("com.miui.screenshot".equals(lpparam.packageName)) {
            XposedBridge.log("[HyperFix] Hooking com.miui.screenshot");
            try {
                XposedHelpers.findAndHookMethod(
                    "com.miui.screenshot.util.MediaUtils",
                    lpparam.classLoader,
                    "j",
                    Context.class,
                    XC_MethodReplacement.returnConstant(null)
                );
                XposedBridge.log("[HyperFix] Successfully hooked MediaUtils.j");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking MediaUtils.j: " + t.getMessage());
            }

            // Modern HyperOS (Android 15-16): MediaUtils.i(Context) -> returns UserInfo (null = defaults to User 0 MediaStore)
            try {
                XposedHelpers.findAndHookMethod(
                    "com.miui.screenshot.util.MediaUtils",
                    lpparam.classLoader,
                    "i",
                    Context.class,
                    XC_MethodReplacement.returnConstant(null)
                );
                XposedBridge.log("[HyperFix] Successfully hooked MediaUtils.i(Context)");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking MediaUtils.i(Context): " + t.getMessage());
            }

            // Legacy MIUI fallback: MediaUtils.i(UserInfo, boolean) -> returns int (0)
            try {
                XposedHelpers.findAndHookMethod(
                    "com.miui.screenshot.util.MediaUtils",
                    lpparam.classLoader,
                    "i",
                    "android.content.pm.UserInfo",
                    boolean.class,
                    XC_MethodReplacement.returnConstant(0)
                );
                XposedBridge.log("[HyperFix] Successfully hooked legacy MediaUtils.i(UserInfo, boolean)");
            } catch (Throwable t) {
                // Ignore if legacy signature is not present
            }

            // Sanitize Screenshot Filenames: Strip foreground app package name from filenames
            try {
                XC_MethodReplacement emptyStringReplacement = XC_MethodReplacement.returnConstant("");
                XposedHelpers.findAndHookMethod(
                    "com.miui.screenshot.util.Util",
                    lpparam.classLoader,
                    "h",
                    Context.class,
                    boolean.class,
                    emptyStringReplacement
                );
                XposedHelpers.findAndHookMethod(
                    "com.miui.screenshot.util.Util",
                    lpparam.classLoader,
                    "i",
                    Context.class,
                    boolean.class,
                    emptyStringReplacement
                );
                XposedBridge.log("[HyperFix] Successfully hooked Util.h and Util.i to strip package names from screenshots");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking Util.h/Util.i: " + t.getMessage());
            }

            try {
                XC_MethodHook sanitizeFormatHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Field[] fields = param.thisObject.getClass().getDeclaredFields();
                        for (Field f : fields) {
                            if (f.getType() == String.class) {
                                f.setAccessible(true);
                                String val = (String) f.get(param.thisObject);
                                if (val != null && val.contains("Screenshot_") && val.contains("%s_%s")) {
                                    String sanitized;
                                    if (val.contains(".png")) {
                                        sanitized = "Screenshot_%s.png";
                                    } else if (val.contains(".webp")) {
                                        sanitized = "Screenshot_%s.webp";
                                    } else {
                                        sanitized = "Screenshot_%s.jpg";
                                    }
                                    f.set(param.thisObject, sanitized);
                                    XposedBridge.log("[HyperFix] Sanitized screenshot filename template: " + f.getName() + " -> " + sanitized);
                                    showToast("📸 HyperFix: Screenshot sanitized\n(App package removed)");
                                }
                            }
                        }
                    }
                };

                try {
                    Class<?> hClass = XposedHelpers.findClass("com.miui.screenshot.H", lpparam.classLoader);
                    XposedBridge.hookAllConstructors(hClass, sanitizeFormatHook);
                    XposedBridge.log("[HyperFix] Successfully hooked com.miui.screenshot.H constructor");
                } catch (Throwable t) {
                    XposedBridge.log("[HyperFix] Could not hook com.miui.screenshot.H: " + t.getMessage());
                }

                try {
                    Class<?> asyncClass = XposedHelpers.findClass("com.miui.annotation.app.AsyncTaskC0326d", lpparam.classLoader);
                    XposedBridge.hookAllConstructors(asyncClass, sanitizeFormatHook);
                    XposedBridge.log("[HyperFix] Successfully hooked AsyncTaskC0326d constructor");
                } catch (Throwable t) {
                    XposedBridge.log("[HyperFix] Could not hook AsyncTaskC0326d: " + t.getMessage());
                }
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking screenshot constructors: " + t.getMessage());
            }
        }

        // 2. Fix Accessibility Cross-User SecurityException in system_server
        if ("android".equals(lpparam.packageName) || "system".equals(lpparam.packageName)) {
            XposedBridge.log("[HyperFix] Hooking system_server for AccessibilitySecurityPolicy");
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.server.accessibility.AccessibilitySecurityPolicy",
                    lpparam.classLoader,
                    "resolveValidReportedPackageLocked",
                    CharSequence.class,
                    int.class,
                    int.class,
                    int.class,
                    new ClearIdentityHook()
                );
                XposedBridge.log("[HyperFix] Successfully hooked AccessibilitySecurityPolicy.resolveValidReportedPackageLocked");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking AccessibilitySecurityPolicy: " + t.getMessage());
            }

            // Fix Cross-Profile Intent Forwarding ("Blocked by your IT admin") for Work Profiles
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.server.pm.ComputerEngine",
                    lpparam.classLoader,
                    "canForwardTo",
                    Intent.class,
                    String.class,
                    int.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            boolean original = ((Boolean) param.getResult()).booleanValue();
                            if (original) return; // already allowed

                            int sourceUserId = ((Integer) param.args[2]).intValue();
                            int targetUserId = ((Integer) param.args[3]).intValue();
                            if (sourceUserId == targetUserId) return;

                            Object userManager = XposedHelpers.getObjectField(param.thisObject, "mUserManager");
                            if (userManager != null) {
                                boolean sameProfile = ((Boolean) XposedHelpers.callMethod(
                                    userManager, "isSameProfileGroup", sourceUserId, targetUserId
                                )).booleanValue();
                                if (sameProfile) {
                                    Intent intent = (Intent) param.args[0];
                                    String resolvedType = (String) param.args[1];
                                    long token = Binder.clearCallingIdentity();
                                    try {
                                        @SuppressWarnings("unchecked")
                                        List<ResolveInfo> list = (List<ResolveInfo>) XposedHelpers.callMethod(
                                            param.thisObject,
                                            "queryIntentActivitiesInternal",
                                            intent,
                                            resolvedType,
                                            65536L, // MATCH_DEFAULT_ONLY
                                            targetUserId
                                        );
                                        if (list == null || list.isEmpty()) {
                                            list = (List<ResolveInfo>) XposedHelpers.callMethod(
                                                param.thisObject,
                                                "queryIntentActivitiesInternal",
                                                intent,
                                                resolvedType,
                                                0L,
                                                targetUserId
                                            );
                                        }
                                        if (list != null) {
                                            for (ResolveInfo ri : list) {
                                                if (ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
                                                    XposedBridge.log("[HyperFix] canForwardTo enabled for profile group: u"
                                                        + sourceUserId + " -> u" + targetUserId + " intent: " + intent);
                                                    param.setResult(true);
                                                    return;
                                                }
                                            }
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log("[HyperFix] Error checking queryIntentActivitiesInternal: " + t.getMessage());
                                    } finally {
                                        Binder.restoreCallingIdentity(token);
                                    }
                                }
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked ComputerEngine.canForwardTo");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking ComputerEngine.canForwardTo: " + t.getMessage());
            }

            // Force AOSP cgroup v2 cached apps freezer in CachedAppOptimizer
            try {
                XposedHelpers.findAndHookMethod(
                    "android.os.SystemProperties",
                    lpparam.classLoader,
                    "getBoolean",
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if ("persist.sys.powmillet.enable".equals(param.args[0])) {
                                param.setResult(false);
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked SystemProperties for powmillet");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking SystemProperties: " + t.getMessage());
            }

            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.server.am.CachedAppOptimizer",
                    lpparam.classLoader,
                    "updateUseFreezer",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object am = XposedHelpers.getObjectField(param.thisObject, "mAm");
                                if (am != null) {
                                    Context ctx = (Context) XposedHelpers.getObjectField(am, "mContext");
                                    if (ctx != null) {
                                        android.provider.Settings.Global.putString(
                                            ctx.getContentResolver(), "cached_apps_freezer", "enabled"
                                        );
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            boolean isFreezerActive = XposedHelpers.getBooleanField(param.thisObject, "mUseFreezer");
                            XposedBridge.log("[HyperFix] CachedAppOptimizer.updateUseFreezer finished (mUseFreezer=" + isFreezerActive + ")");
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked CachedAppOptimizer.updateUseFreezer");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking CachedAppOptimizer.updateUseFreezer: " + t.getMessage());
            }
        }

        // 3. Fix Recents Work Profile app labels in Launcher
        if ("com.mi.android.globallauncher".equals(lpparam.packageName) || "com.miui.home".equals(lpparam.packageName)) {
            XposedBridge.log("[HyperFix] Hooking Launcher PackageManagerWrapper");
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.systemui.shared.recents.system.PackageManagerWrapper",
                    lpparam.classLoader,
                    "getActivityInfo",
                    Context.class,
                    ComponentName.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            int userId = (Integer) param.args[2];
                            if (userId == 0) {
                                return;
                            }
                            Context context = (Context) param.args[0];
                            ComponentName componentName = (ComponentName) param.args[1];
                            if (context == null || componentName == null) {
                                return;
                            }

                            ActivityInfo info = null;
                            try {
                                Object userHandle = XposedHelpers.callStaticMethod(UserHandle.class, "of", userId);
                                Context userContext = (Context) XposedHelpers.callMethod(
                                    context, "createPackageContextAsUser", "android", 0, userHandle
                                );
                                info = userContext.getPackageManager().getActivityInfo(componentName, 128);
                            } catch (Throwable t) {
                                XposedBridge.log("[HyperFix] userContext.getActivityInfo failed: " + t.getMessage());
                            }

                            if (info == null) {
                                try {
                                    Object ipm = XposedHelpers.callStaticMethod(param.thisObject.getClass(), "getPackageManager");
                                    if (ipm != null) {
                                        try {
                                            info = (ActivityInfo) XposedHelpers.callMethod(
                                                ipm, "getActivityInfo", componentName, 128L, userId
                                            );
                                        } catch (Throwable t1) {
                                            info = (ActivityInfo) XposedHelpers.callMethod(
                                                ipm, "getActivityInfo", componentName, 128, userId
                                            );
                                        }
                                    }
                                } catch (Throwable t2) {
                                    XposedBridge.log("[HyperFix] ipm.getActivityInfo failed: " + t2.getMessage());
                                }
                            }

                            if (info != null) {
                                XposedBridge.log("[HyperFix] Resolved ActivityInfo for " + componentName + " (u" + userId + "): " + info.packageName);
                                param.setResult(info);
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked PackageManagerWrapper.getActivityInfo");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking PackageManagerWrapper: " + t.getMessage());
            }
        }

        // 4. Allow FiveGTile to toggle network modes without SecurityException
        if ("com.android.phone".equals(lpparam.packageName)) {
            XposedBridge.log("[HyperFix] Hooking com.android.phone for PhoneInterfaceManager");
            try {
                XC_MethodHook bypassHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int callingUid = Binder.getCallingUid();
                        Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mApp");
                        if (context != null) {
                            String[] packages = context.getPackageManager().getPackagesForUid(callingUid);
                            if (packages != null) {
                                for (String pkg : packages) {
                                    if ("net.wastu.fivegtile".equals(pkg)) {
                                        long token = Binder.clearCallingIdentity();
                                        try {
                                            param.setResult(XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args));
                                        } finally {
                                            Binder.restoreCallingIdentity(token);
                                        }
                                        return;
                                    }
                                }
                            }
                        }
                    }
                };

                XposedHelpers.findAndHookMethod(
                    "com.android.phone.PhoneInterfaceManager",
                    lpparam.classLoader,
                    "getAllowedNetworkTypesForReason",
                    int.class,
                    int.class,
                    bypassHook
                );

                XposedHelpers.findAndHookMethod(
                    "com.android.phone.PhoneInterfaceManager",
                    lpparam.classLoader,
                    "setAllowedNetworkTypesForReason",
                    int.class,
                    int.class,
                    long.class,
                    bypassHook
                );
                XposedBridge.log("[HyperFix] Successfully hooked PhoneInterfaceManager for FiveGTile");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking PhoneInterfaceManager: " + t.getMessage());
            }
        }

        // 5. Allow Work Profile QS Tiles in SystemUI
        if ("com.android.systemui".equals(lpparam.packageName)) {
            XposedBridge.log("[HyperFix] Hooking com.android.systemui for Work Profile QS Tiles");

            // A. Expand QS_TILE discovery to include active Work Profiles
            try {
                XC_MethodHook queryHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Intent intent = (Intent) param.args[0];
                        if (intent != null && "android.service.quicksettings.action.QS_TILE".equals(intent.getAction())) {
                            XposedBridge.log("[HyperFix] queryIntentServicesAsUser called with args: " + java.util.Arrays.toString(param.args) + " for intent: " + intent);
                            Object userArg = param.args[2];
                            int currentUserId = (userArg instanceof Number) ? ((Number) userArg).intValue() : (userArg instanceof UserHandle ? ((Integer) XposedHelpers.callMethod(userArg, "getIdentifier")).intValue() : 0);
                            Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                            if (context == null) return;

                            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                            if (um == null) return;

                            List<UserHandle> profiles = um.getUserProfiles();
                            List<ResolveInfo> currentResults = (List<ResolveInfo>) param.getResult();
                            List<ResolveInfo> merged = new ArrayList<>(currentResults != null ? currentResults : Collections.emptyList());
                            Set<String> seen = new HashSet<>();
                            for (ResolveInfo r : merged) {
                                if (r.serviceInfo != null) {
                                    seen.add(r.serviceInfo.packageName + "/" + r.serviceInfo.name);
                                }
                            }

                            for (UserHandle profile : profiles) {
                                int pId = ((Integer) XposedHelpers.callMethod(profile, "getIdentifier")).intValue();
                                if (pId != currentUserId) {
                                    try {
                                        Object[] newArgs = param.args.clone();
                                        newArgs[2] = pId;
                                        List<ResolveInfo> workServices = (List<ResolveInfo>) XposedBridge.invokeOriginalMethod(
                                            param.method, param.thisObject, newArgs
                                        );
                                        if (workServices != null) {
                                            for (ResolveInfo wr : workServices) {
                                                if (wr.serviceInfo != null) {
                                                    String key = wr.serviceInfo.packageName + "/" + wr.serviceInfo.name;
                                                    if (!seen.contains(key)) {
                                                        seen.add(key);
                                                        try {
                                                            CharSequence orig = wr.serviceInfo.loadLabel((PackageManager) param.thisObject);
                                                            String labelStr = orig != null ? orig.toString() : wr.serviceInfo.name;
                                                            if (!labelStr.startsWith("[WORK] ")) {
                                                                wr.serviceInfo.nonLocalizedLabel = "[WORK] " + labelStr;
                                                            }
                                                        } catch (Throwable ignored) {}
                                                        merged.add(wr);
                                                        XposedBridge.log("[HyperFix] Discovered Work Profile QS_TILE: " + key + " (u" + pId + ")");
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log("[HyperFix] Error querying QS tiles for u" + pId + ": " + t.getMessage());
                                    }
                                }
                            }
                            param.setResult(merged);
                        }
                    }
                };

                Class<?> appPmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", lpparam.classLoader);
                for (java.lang.reflect.Method m : appPmClass.getDeclaredMethods()) {
                    if ("queryIntentServicesAsUser".equals(m.getName())) {
                        XposedBridge.hookMethod(m, queryHook);
                    }
                }
                XposedBridge.log("[HyperFix] Successfully hooked queryIntentServicesAsUser");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking queryIntentServicesAsUser: " + t.getMessage());
            }

            // Hook getComponentEnabledSetting to treat Work Profile tiles as enabled
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.ApplicationPackageManager",
                    lpparam.classLoader,
                    "getComponentEnabledSetting",
                    ComponentName.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            ComponentName cn = (ComponentName) param.args[0];
                            if (cn != null) {
                                Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                                if (context != null) {
                                    PackageManager pm = context.getPackageManager();
                                    try {
                                        pm.getPackageInfo(cn.getPackageName(), 0);
                                    } catch (PackageManager.NameNotFoundException e) {
                                        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                                        if (um != null) {
                                            for (UserHandle uh : um.getUserProfiles()) {
                                                int uhId = ((Integer) XposedHelpers.callMethod(uh, "getIdentifier")).intValue();
                                                if (uhId != 0) {
                                                    try {
                                                        XposedHelpers.callMethod(pm, "getPackageInfoAsUser", cn.getPackageName(), 0, uhId);
                                                        param.setResult(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
                                                        return;
                                                    } catch (Throwable ignored) {}
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked getComponentEnabledSetting");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking getComponentEnabledSetting: " + t.getMessage());
            }

            // Hook getResourcesForApplication to load resources for Work Profile apps
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.ApplicationPackageManager",
                    lpparam.classLoader,
                    "getResourcesForApplication",
                    ApplicationInfo.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            ApplicationInfo ai = (ApplicationInfo) param.args[0];
                            if (ai != null) {
                                Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                                if (context != null) {
                                    PackageManager pm = context.getPackageManager();
                                    try {
                                        pm.getPackageInfo(ai.packageName, 0);
                                    } catch (PackageManager.NameNotFoundException e) {
                                        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                                        if (um != null) {
                                            for (UserHandle uh : um.getUserProfiles()) {
                                                int uhId = ((Integer) XposedHelpers.callMethod(uh, "getIdentifier")).intValue();
                                                if (uhId != 0) {
                                                    try {
                                                        Context workContext = (Context) XposedHelpers.callMethod(context, "createPackageContextAsUser", "android", 0, uh);
                                                        Resources res = (Resources) XposedHelpers.callMethod(workContext.getPackageManager(), "getResourcesForApplication", ai);
                                                        if (res != null) {
                                                            param.setResult(res);
                                                            return;
                                                        }
                                                    } catch (Throwable ignored) {}
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked getResourcesForApplication");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking getResourcesForApplication: " + t.getMessage());
            }

            // Hook getDrawable to load icons for Work Profile apps
            try {
                XposedHelpers.findAndHookMethod(
                    "android.app.ApplicationPackageManager",
                    lpparam.classLoader,
                    "getDrawable",
                    String.class,
                    int.class,
                    ApplicationInfo.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String pkg = (String) param.args[0];
                            int resid = (Integer) param.args[1];
                            ApplicationInfo ai = (ApplicationInfo) param.args[2];
                            Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                            if (context != null && pkg != null) {
                                PackageManager pm = context.getPackageManager();
                                try {
                                    pm.getPackageInfo(pkg, 0);
                                } catch (PackageManager.NameNotFoundException e) {
                                    UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                                    if (um != null) {
                                        for (UserHandle uh : um.getUserProfiles()) {
                                            int uhId = ((Integer) XposedHelpers.callMethod(uh, "getIdentifier")).intValue();
                                            if (uhId != 0) {
                                                try {
                                                    Context workContext = (Context) XposedHelpers.callMethod(context, "createPackageContextAsUser", "android", 0, uh);
                                                    Drawable d = (Drawable) XposedHelpers.callMethod(workContext.getPackageManager(), "getDrawable", pkg, resid, ai);
                                                    if (d != null) {
                                                        param.setResult(d);
                                                        return;
                                                    }
                                                } catch (Throwable ignored) {}
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked getDrawable");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking getDrawable: " + t.getMessage());
            }
            // B. Redirect CustomTile User & Context to Work Profile
            try {
                XposedBridge.hookAllConstructors(
                    XposedHelpers.findClass("com.android.systemui.qs.external.CustomTile", lpparam.classLoader),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            ComponentName component = (ComponentName) XposedHelpers.getObjectField(param.thisObject, "mComponent");
                            if (component != null) {
                                Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mUserContext");
                                if (context != null) {
                                    PackageManager pm = context.getPackageManager();
                                    try {
                                        pm.getPackageInfo(component.getPackageName(), 0);
                                    } catch (PackageManager.NameNotFoundException e) {
                                        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                                        if (um != null) {
                                            for (UserHandle uh : um.getUserProfiles()) {
                                                int uhId = ((Integer) XposedHelpers.callMethod(uh, "getIdentifier")).intValue();
                                                if (uhId != 0) {
                                                    try {
                                                        XposedHelpers.callMethod(pm, "getPackageInfoAsUser", component.getPackageName(), 0, uhId);
                                                        Context workContext = (Context) XposedHelpers.callMethod(context, "createPackageContextAsUser", "android", 0, uh);
                                                        XposedHelpers.setObjectField(param.thisObject, "mUserContext", workContext);
                                                        XposedHelpers.setIntField(param.thisObject, "mUser", uhId);
                                                        XposedBridge.log("[HyperFix] CustomTile redirected to Work Profile: " + component + " -> " + uh);
                                                        sWorkTiles.add(param.thisObject);
                                                        if (!sWorkTileReceiverRegistered && context != null) {
                                                            sWorkTileReceiverRegistered = true;
                                                            IntentFilter filter = new IntentFilter();
                                                            filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
                                                            filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
                                                            try {
                                                                Context regCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                                                                regCtx.registerReceiver(new BroadcastReceiver() {
                                                                    @Override
                                                                    public void onReceive(Context ctx, Intent it) {
                                                                        XposedBridge.log("[HyperFix] Managed profile state changed: " + it.getAction());
                                                                        synchronized (sWorkTiles) {
                                                                            for (Object tile : sWorkTiles) {
                                                                                try {
                                                                                    XposedHelpers.callMethod(tile, "refreshState");
                                                                                } catch (Throwable ignored) {}
                                                                            }
                                                                        }
                                                                    }
                                                                }, filter, Context.RECEIVER_EXPORTED);
                                                                XposedBridge.log("[HyperFix] Registered MANAGED_PROFILE receiver for QS tiles");
                                                            } catch (Throwable t) {
                                                                XposedBridge.log("[HyperFix] Failed to register receiver: " + t.getMessage());
                                                            }
                                                        }
                                                        break;
                                                    } catch (Throwable ignored) {}
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked CustomTile constructor");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking CustomTile constructor: " + t.getMessage());
            }

            // Hook CustomTile.handleUpdateState to add [WORK] prefix to tile label
            try {
                Class<?> customTileClass = XposedHelpers.findClass("com.android.systemui.qs.external.CustomTile", lpparam.classLoader);
                for (java.lang.reflect.Method m : customTileClass.getDeclaredMethods()) {
                    if ("handleUpdateState".equals(m.getName())) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                int user = XposedHelpers.getIntField(param.thisObject, "mUser");
                                if (user != 0) {
                                    sWorkTiles.add(param.thisObject);
                                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                                    boolean isQuiet = false;
                                    if (context != null) {
                                        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                                        if (um != null) {
                                            try {
                                                UserHandle uh = (UserHandle) XposedHelpers.callStaticMethod(UserHandle.class, "of", user);
                                                isQuiet = ((Boolean) XposedHelpers.callMethod(um, "isQuietModeEnabled", uh)).booleanValue()
                                                    || !((Boolean) XposedHelpers.callMethod(um, "isUserRunning", uh)).booleanValue();
                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                    Object state = param.args[0];
                                    if (state != null) {
                                        if (isQuiet) {
                                            XposedHelpers.setIntField(state, "state", 0); // STATE_UNAVAILABLE
                                        }
                                        CharSequence currentLabel = (CharSequence) XposedHelpers.getObjectField(state, "label");
                                        if (currentLabel != null) {
                                            String s = currentLabel.toString();
                                            if (!s.startsWith("[WORK] ")) {
                                                XposedHelpers.setObjectField(state, "label", "[WORK] " + s);
                                            }
                                        }
                                    }
                                }
                            }
                        });
                    }
                    if ("handleClick".equals(m.getName())) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                int user = XposedHelpers.getIntField(param.thisObject, "mUser");
                                if (user != 0) {
                                    Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                                    if (context != null) {
                                        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                                        if (um != null) {
                                            try {
                                                UserHandle uh = (UserHandle) XposedHelpers.callStaticMethod(UserHandle.class, "of", user);
                                                boolean isQuiet = ((Boolean) XposedHelpers.callMethod(um, "isQuietModeEnabled", uh)).booleanValue()
                                                    || !((Boolean) XposedHelpers.callMethod(um, "isUserRunning", uh)).booleanValue();
                                                if (isQuiet) {
                                                    XposedBridge.log("[HyperFix] Blocked click because Work Profile is paused");
                                                    param.setResult(null);
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
                XposedBridge.log("[HyperFix] Successfully hooked CustomTile.handleUpdateState for [WORK] prefix");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking CustomTile.handleUpdateState: " + t.getMessage());
            }

            // C. Redirect TileLifecycleManager service binding to Work Profile user
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.systemui.qs.external.TileLifecycleManager",
                    lpparam.classLoader,
                    "setBindService",
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Intent intent = (Intent) XposedHelpers.getObjectField(param.thisObject, "mIntent");
                            if (intent != null && intent.getComponent() != null) {
                                ComponentName cn = intent.getComponent();
                                Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                                if (context != null) {
                                    PackageManager pm = context.getPackageManager();
                                    try {
                                        pm.getPackageInfo(cn.getPackageName(), 0);
                                    } catch (PackageManager.NameNotFoundException e) {
                                        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                                        if (um != null) {
                                            for (UserHandle uh : um.getUserProfiles()) {
                                                int uhId = ((Integer) XposedHelpers.callMethod(uh, "getIdentifier")).intValue();
                                                if (uhId != 0) {
                                                    try {
                                                        XposedHelpers.callMethod(pm, "getPackageInfoAsUser", cn.getPackageName(), 0, uhId);
                                                        XposedHelpers.setObjectField(param.thisObject, "mUser", uh);
                                                        XposedBridge.log("[HyperFix] TileLifecycleManager bound to Work Profile: " + cn + " -> " + uh);
                                                        break;
                                                    } catch (Throwable ignored) {}
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked TileLifecycleManager.setBindService");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking TileLifecycleManager.setBindService: " + t.getMessage());
            }

            // D. Route Long Click and activity launches for Work Profile packages to the Work Profile UserHandle
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.systemui.statusbar.phone.LegacyActivityStarterInternalImpl",
                    lpparam.classLoader,
                    "getActivityUserHandle",
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Intent intent = (Intent) param.args[0];
                            if (intent == null) return;
                            String pkg = null;
                            if (intent.getComponent() != null) {
                                pkg = intent.getComponent().getPackageName();
                            } else if (intent.getPackage() != null) {
                                pkg = intent.getPackage();
                            } else if (intent.getData() != null && "package".equals(intent.getData().getScheme())) {
                                pkg = intent.getData().getSchemeSpecificPart();
                            }
                            if (pkg != null) {
                                Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "context");
                                if (context != null) {
                                    try {
                                        context.getPackageManager().getPackageInfo(pkg, 0);
                                    } catch (PackageManager.NameNotFoundException e) {
                                        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
                                        if (um != null) {
                                            for (UserHandle uh : um.getUserProfiles()) {
                                                int uhId = ((Integer) XposedHelpers.callMethod(uh, "getIdentifier")).intValue();
                                                if (uhId != 0) {
                                                    try {
                                                        XposedHelpers.callMethod(context.getPackageManager(), "getPackageInfoAsUser", pkg, 0, uhId);
                                                        param.setResult(uh);
                                                        XposedBridge.log("[HyperFix] getActivityUserHandle routed " + pkg + " to Work Profile: " + uh);
                                                        return;
                                                    } catch (Throwable ignored) {}
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked LegacyActivityStarterInternalImpl.getActivityUserHandle");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking LegacyActivityStarterInternalImpl: " + t.getMessage());
            }
        }

        // 6. System-wide Photo Picker Injection & Media Privacy
        if ("com.android.providers.media.module".equals(lpparam.packageName)
                || "com.android.providers.media".equals(lpparam.packageName)
                || "com.google.android.providers.media.module".equals(lpparam.packageName)) {
            XposedBridge.log("[HyperFix] Hooking MediaProvider / PhotoPicker in " + lpparam.packageName);

            // A. Generic Filenames for Photo Picker queries
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.providers.media.PickerUriResolver",
                    lpparam.classLoader,
                    "query",
                    Uri.class,
                    String[].class,
                    int.class,
                    int.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Cursor cursor = (Cursor) param.getResult();
                            if (cursor == null) return;

                            int callingUid = (Integer) param.args[3];
                            String callingPkg = (String) param.args[4];
                            if (callingUid <= 10000 && callingUid != 0) return;
                            if ("com.android.photopicker".equals(callingPkg)
                                    || "com.google.android.photopicker".equals(callingPkg)) {
                                return;
                            }

                            Uri uri = (Uri) param.args[0];
                            param.setResult(wrapPickerCursor(cursor, uri));
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked PickerUriResolver.query for generic names");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking PickerUriResolver.query: " + t.getMessage());
            }

            // B. Force Redaction for Third-Party Apps
            try {
                Class<?> pendingOpenInfoClass = XposedHelpers.findClass(
                    "com.android.providers.media.MediaProvider$PendingOpenInfo",
                    lpparam.classLoader
                );
                XposedBridge.hookAllConstructors(pendingOpenInfoClass, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int uid = (Integer) param.args[0];
                        if (uid > 10000 && uid != android.os.Process.myUid()) {
                            param.args[2] = true; // force shouldRedact = true
                        }
                    }
                });
                XposedBridge.log("[HyperFix] Successfully hooked MediaProvider$PendingOpenInfo constructor for forced redaction");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking PendingOpenInfo constructor: " + t.getMessage());
            }

            // C. Sensitive EXIF Stripping (Preserving Orientation)
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.providers.media.util.RedactionUtils",
                    lpparam.classLoader,
                    "getRedactionRanges",
                    FileInputStream.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            FileInputStream fis = (FileInputStream) param.args[0];
                            String mimeType = (String) param.args[1];
                            if (fis == null || mimeType == null) return;

                            long[] origRanges = (long[]) param.getResult();
                            long[] extraRanges = getExtraSensitiveExifRanges(fis, mimeType);
                            if (extraRanges != null && extraRanges.length > 0) {
                                if (origRanges == null || origRanges.length == 0) {
                                    param.setResult(extraRanges);
                                } else {
                                    long[] merged = new long[origRanges.length + extraRanges.length];
                                    System.arraycopy(origRanges, 0, merged, 0, origRanges.length);
                                    System.arraycopy(extraRanges, 0, merged, origRanges.length, extraRanges.length);
                                    param.setResult(merged);
                                }
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked RedactionUtils.getRedactionRanges for sensitive EXIF redaction");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking RedactionUtils.getRedactionRanges: " + t.getMessage());
            }
        }
    }

    private static final String[] EXTRA_SENSITIVE_EXIF_TAGS = new String[] {
        "Make",
        "Model",
        "Software",
        "BodySerialNumber",
        "CameraOwnerName",
        "DeviceSettingDescription",
        "ImageUniqueID",
        "LensMake",
        "LensModel",
        "LensSerialNumber",
        "LensSpecification",
        "OwnerName",
        "SpectralSensitivity",
        "UserComment",
        "Artist",
        "Copyright"
    };

    private static Cursor wrapPickerCursor(final Cursor cursor, final Uri uri) {
        return new CursorWrapper(cursor) {
            private final int mDisplayNameCol = cursor.getColumnIndex("_display_name");
            private final int mTitleCol = cursor.getColumnIndex("title");

            @Override
            public String getString(int columnIndex) {
                if (columnIndex >= 0) {
                    if (columnIndex == mDisplayNameCol) {
                        String genericName = computeGenericName(cursor, uri, true);
                        String origName = null;
                        try {
                            origName = super.getString(columnIndex);
                        } catch (Throwable ignored) {}
                        if (origName != null && !origName.equals(genericName)) {
                            showToast("🔒 HyperFix: Anonymized filename\n" + origName + " ➔ " + genericName);
                        } else {
                            showToast("🔒 HyperFix: Anonymized filename ➔ " + genericName);
                        }
                        return genericName;
                    } else if (columnIndex == mTitleCol) {
                        return computeGenericName(cursor, uri, false);
                    }
                }
                return super.getString(columnIndex);
            }
        };
    }

    private static String computeGenericName(Cursor cursor, Uri uri, boolean withExtension) {
        String id = null;
        try {
            int idIdx = cursor.getColumnIndex("_id");
            if (idIdx >= 0) {
                id = cursor.getString(idIdx);
            }
        } catch (Throwable ignored) {}

        if (id == null || id.isEmpty()) {
            if (uri != null) {
                id = uri.getLastPathSegment();
            }
        }
        if (id == null || id.isEmpty()) {
            try {
                id = String.valueOf(cursor.getPosition());
            } catch (Throwable ignored) {
                id = "1";
            }
        }

        String prefix = "image";
        String mime = null;
        try {
            int mimeIdx = cursor.getColumnIndex("mime_type");
            if (mimeIdx >= 0) {
                mime = cursor.getString(mimeIdx);
                if (mime != null && mime.startsWith("video/")) {
                    prefix = "video";
                }
            }
        } catch (Throwable ignored) {}

        if (!withExtension) {
            return prefix + "_" + id;
        }

        String ext = null;
        try {
            int nameIdx = cursor.getColumnIndex("_display_name");
            if (nameIdx >= 0) {
                String origName = cursor.getString(nameIdx);
                if (origName != null) {
                    int dot = origName.lastIndexOf('.');
                    if (dot >= 0 && dot < origName.length() - 1) {
                        ext = origName.substring(dot + 1).toLowerCase();
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (ext == null || ext.isEmpty()) {
            if (mime != null) {
                String fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                if (fromMime != null && !fromMime.isEmpty()) {
                    ext = fromMime.toLowerCase();
                } else if ("image/jpeg".equalsIgnoreCase(mime)) {
                    ext = "jpg";
                } else if ("image/png".equalsIgnoreCase(mime)) {
                    ext = "png";
                } else if ("image/webp".equalsIgnoreCase(mime)) {
                    ext = "webp";
                } else if ("video/mp4".equalsIgnoreCase(mime)) {
                    ext = "mp4";
                }
            }
        }

        if (ext == null || ext.isEmpty()) {
            ext = "image".equals(prefix) ? "jpg" : "mp4";
        }

        return prefix + "_" + id + "." + ext;
    }

    private static long[] getExtraSensitiveExifRanges(FileInputStream fis, String mimeType) {
        if (mimeType == null || !ExifInterface.isSupportedMimeType(mimeType)) {
            return null;
        }
        try {
            FileDescriptor fd = fis.getFD();
            if (fd == null || !fd.valid()) return null;

            ExifInterface ex = new ExifInterface(fd);
            List<Long> ranges = new ArrayList<Long>();
            List<String> redactedNames = new ArrayList<String>();

            // Check if GPS tags were present (these are redacted by AOSP RedactionUtils)
            if (ex.getAttribute("GPSLatitude") != null || ex.getAttribute("GPSLongitude") != null || ex.getAttribute("GPSAltitude") != null) {
                redactedNames.add("GPS");
            }

            for (String tag : EXTRA_SENSITIVE_EXIF_TAGS) {
                long[] r = ex.getAttributeRange(tag);
                if (r != null && r.length == 2 && r[1] > 0) {
                    ranges.add(r[0]);
                    ranges.add(r[0] + r[1]);
                    redactedNames.add(tag);
                }
            }

            if (!redactedNames.isEmpty()) {
                String detail = String.join(", ", redactedNames);
                showToast("🛡️ HyperFix: Redacted " + redactedNames.size() + " EXIF tag" + (redactedNames.size() > 1 ? "s" : "") + "\n(" + detail + ")");
            } else {
                showToast("🛡️ HyperFix: EXIF clean (0 sensitive tags)");
            }

            if (ranges.isEmpty()) {
                return null;
            }
            long[] result = new long[ranges.size()];
            for (int i = 0; i < ranges.size(); i++) {
                result[i] = ranges.get(i);
            }
            return result;
        } catch (Throwable t) {
            XposedBridge.log("[HyperFix] Error extracting extra sensitive EXIF ranges: " + t.getMessage());
            return null;
        }
    }

    private static volatile long sLastToastTime = 0;
    private static volatile String sLastToastMsg = "";

    private static void showToast(final String message) {
        long now = System.currentTimeMillis();
        synchronized (MainHook.class) {
            if (message.equals(sLastToastMsg) && (now - sLastToastTime < 3000)) {
                return;
            }
            sLastToastTime = now;
            sLastToastMsg = message;
        }

        try {
            Context targetCtx = null;
            try {
                targetCtx = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentApplication"
                );
            } catch (Throwable ignored) {}

            if (targetCtx != null) {
                final Context appCtx = targetCtx.getApplicationContext() != null
                        ? targetCtx.getApplicationContext()
                        : targetCtx;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Toast.makeText(appCtx, message, Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            XposedBridge.log("[HyperFix] Toast failed: " + t.getMessage());
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}
    }
}
