package net.wastu.hyperfix;

import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.UserHandle;
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
    }
}
