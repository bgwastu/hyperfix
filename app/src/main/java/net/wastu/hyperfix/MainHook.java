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
import android.os.Binder;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

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

            try {
                XposedHelpers.findAndHookMethod(
                    "com.miui.screenshot.util.MediaUtils",
                    lpparam.classLoader,
                    "i",
                    "android.content.pm.UserInfo",
                    boolean.class,
                    XC_MethodReplacement.returnConstant(0)
                );
                XposedBridge.log("[HyperFix] Successfully hooked MediaUtils.i");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking MediaUtils.i: " + t.getMessage());
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
                                    Object state = param.args[0];
                                    if (state != null) {
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
    }
}
