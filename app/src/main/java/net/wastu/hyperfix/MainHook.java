package net.wastu.hyperfix;

import android.app.Activity;
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
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
                                    showToast("📸 Screenshot sanitized");
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
                    new XC_MethodHook() {
                        private final ThreadLocal<Long> tokenHolder = new ThreadLocal<Long>();

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
                            if (param.hasThrowable()) {
                                XposedBridge.log("[HyperFix] Suppressed exception in resolveValidReportedPackageLocked: " + param.getThrowable().getMessage());
                                CharSequence pkg = (CharSequence) param.args[0];
                                param.setResult(pkg != null ? pkg.toString() : null);
                            }
                        }
                    }
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
                                        Intent queryIntent = new Intent(intent);
                                        queryIntent.setPackage(null);
                                        queryIntent.setComponent(null);
                                        @SuppressWarnings("unchecked")
                                        List<ResolveInfo> list = (List<ResolveInfo>) XposedHelpers.callMethod(
                                            param.thisObject,
                                            "queryIntentActivitiesInternal",
                                            queryIntent,
                                            resolvedType,
                                            65536L, // MATCH_DEFAULT_ONLY
                                            targetUserId
                                        );
                                        if (list == null || list.isEmpty()) {
                                            list = (List<ResolveInfo>) XposedHelpers.callMethod(
                                                param.thisObject,
                                                "queryIntentActivitiesInternal",
                                                queryIntent,
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

            // Fix Cross-Profile Browser Link Clicking (Discord setting pkg="android" for web links)
            try {
                Class<?> ceClass = XposedHelpers.findClass("com.android.server.pm.ComputerEngine", lpparam.classLoader);
                for (java.lang.reflect.Method m : ceClass.getDeclaredMethods()) {
                    if ("queryIntentActivitiesInternal".equals(m.getName())) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.args.length > 0 && param.args[0] instanceof Intent) {
                                    Intent intent = (Intent) param.args[0];
                                    if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && isWebIntent(intent)) {
                                        if ("android".equals(intent.getPackage())) {
                                            intent.setPackage(null);
                                            XposedBridge.log("[HyperFix] Stripped pkg='android' in queryIntentActivitiesInternal for web intent: " + intent);
                                        }
                                        ComponentName cn = intent.getComponent();
                                        if (cn != null && "android".equals(cn.getPackageName())) {
                                            intent.setComponent(null);
                                            XposedBridge.log("[HyperFix] Stripped component pkg='android' in queryIntentActivitiesInternal for web intent: " + intent);
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
                XposedBridge.log("[HyperFix] Successfully hooked ComputerEngine.queryIntentActivitiesInternal for web links");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking ComputerEngine.queryIntentActivitiesInternal: " + t.getMessage());
            }

            try {
                Class<?> atmsClass = XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", lpparam.classLoader);
                XC_MethodHook sanitizeWebIntentHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        for (Object arg : param.args) {
                            if (arg instanceof Intent) {
                                Intent intent = (Intent) arg;
                                if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && isWebIntent(intent)) {
                                    if ("android".equals(intent.getPackage())) {
                                        intent.setPackage(null);
                                        XposedBridge.log("[HyperFix] Stripped pkg='android' in startActivity for web intent: " + intent);
                                    }
                                    ComponentName cn = intent.getComponent();
                                    if (cn != null && "android".equals(cn.getPackageName())) {
                                        intent.setComponent(null);
                                        XposedBridge.log("[HyperFix] Stripped component pkg='android' in startActivity for web intent: " + intent);
                                    }
                                }
                                break;
                            }
                        }
                    }
                };

                for (java.lang.reflect.Method m : atmsClass.getDeclaredMethods()) {
                    if ("startActivityAsUser".equals(m.getName()) || "startActivity".equals(m.getName())) {
                        XposedBridge.hookMethod(m, sanitizeWebIntentHook);
                    } else if ("startActivityAsCaller".equals(m.getName())) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    Intent intent = param.args.length > 2 && param.args[2] instanceof Intent ? (Intent) param.args[2] : null;
                                    IBinder resultTo = param.args.length > 4 && param.args[4] instanceof IBinder ? (IBinder) param.args[4] : null;
                                    Bundle bOptions = param.args.length > 9 && param.args[9] instanceof Bundle ? (Bundle) param.args[9] : null;
                                    int targetUserId = param.args.length > 11 && param.args[11] instanceof Integer ? ((Integer) param.args[11]).intValue() : 0;

                                    boolean isForwarder = false;
                                    if (resultTo != null) {
                                        Class<?> arClass = XposedHelpers.findClassIfExists("com.android.server.wm.ActivityRecord", lpparam.classLoader);
                                        if (arClass != null) {
                                            Object sourceRecord = XposedHelpers.callStaticMethod(arClass, "isInAnyTask", resultTo);
                                            if (sourceRecord != null) {
                                                Object component = XposedHelpers.getObjectField(sourceRecord, "mActivityComponent");
                                                if (component instanceof ComponentName) {
                                                    String cls = ((ComponentName) component).getClassName();
                                                    if (cls != null && (cls.contains("IntentForwarderActivity")
                                                            || cls.contains("ForwardIntentToParent")
                                                            || cls.contains("ForwardIntentToManagedProfile"))) {
                                                        isForwarder = true;
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (intent != null && isWebIntent(intent)) {
                                        XposedBridge.log("[HyperFix] ATMS.startActivityAsCaller intercepted for forwarder/web: " + intent + " to u" + targetUserId);
                                        Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                                        if (context != null && intent != null) {
                                            Intent launchIntent = new Intent(intent);
                                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                            launchIntent.setComponent(null);
                                            launchIntent.setPackage(null);

                                            int resolvedUserId = (targetUserId <= 0) ? 0 : targetUserId;
                                            Object userHandle = XposedHelpers.callStaticMethod(UserHandle.class, "of", resolvedUserId);

                                            long token = Binder.clearCallingIdentity();
                                            try {
                                                if (bOptions != null) {
                                                    XposedHelpers.callMethod(context, "startActivityAsUser", launchIntent, bOptions, userHandle);
                                                } else {
                                                    XposedHelpers.callMethod(context, "startActivityAsUser", launchIntent, userHandle);
                                                }
                                                XposedBridge.log("[HyperFix] ATMS.startActivityAsCaller successfully launched intent into u" + resolvedUserId + ": " + launchIntent);
                                                param.setResult(0); // ActivityManager.START_SUCCESS
                                            } finally {
                                                Binder.restoreCallingIdentity(token);
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[HyperFix] Error in ATMS.startActivityAsCaller hook: " + t.getMessage());
                                }
                            }
                        });
                    }
                }
                XposedBridge.log("[HyperFix] Successfully hooked ActivityTaskManagerService for web links and startActivityAsCaller");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking ActivityTaskManagerService: " + t.getMessage());
            }

            // Fix Cross-Profile Forwarding via IntentForwarderActivity (Discord -> Browser on User 0)
            try {
                Class<?> ifaClass = XposedHelpers.findClassIfExists("com.android.internal.app.IntentForwarderActivity", lpparam.classLoader);
                if (ifaClass != null) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            ifaClass,
                            "startActivityAsCaller",
                            Intent.class,
                            int.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    Intent intent = (Intent) param.args[0];
                                    if (!isWebIntent(intent)) {
                                        return;
                                    }
                                    int targetUserId = ((Integer) param.args[1]).intValue();
                                    Activity activity = (Activity) param.thisObject;
                                    try {
                                        Intent launchIntent = new Intent(intent);
                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        Object targetUserHandle = XposedHelpers.callStaticMethod(UserHandle.class, "of", targetUserId);
                                        XposedHelpers.callMethod(activity, "startActivityAsUser", launchIntent, targetUserHandle);
                                        XposedBridge.log("[HyperFix] IntentForwarderActivity forwarded intent to u" + targetUserId + ": " + launchIntent);
                                        param.setResult(null);
                                    } catch (Throwable t) {
                                        XposedBridge.log("[HyperFix] Error in IntentForwarderActivity startActivityAsUser: " + t.getMessage());
                                    }
                                }
                            }
                        );
                        XposedBridge.log("[HyperFix] Hooked IntentForwarderActivity.startActivityAsCaller(Intent, int)");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking IntentForwarderActivity.startActivityAsCaller: " + t.getMessage());
                    }
                }

                try {
                    XposedHelpers.findAndHookMethod(
                        Activity.class,
                        "startActivityAsCaller",
                        Intent.class,
                        android.os.Bundle.class,
                        boolean.class,
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.thisObject != null && param.thisObject.getClass().getName().contains("IntentForwarderActivity")) {
                                    Intent intent = (Intent) param.args[0];
                                    if (!isWebIntent(intent)) {
                                        return;
                                    }
                                    int targetUserId = ((Integer) param.args[3]).intValue();
                                    Activity activity = (Activity) param.thisObject;
                                    try {
                                        Intent launchIntent = new Intent(intent);
                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        Object targetUserHandle = XposedHelpers.callStaticMethod(UserHandle.class, "of", targetUserId);
                                        XposedHelpers.callMethod(activity, "startActivityAsUser", launchIntent, targetUserHandle);
                                        XposedBridge.log("[HyperFix] Activity.startActivityAsCaller intercepted for " + param.thisObject.getClass().getName() + " -> u" + targetUserId);
                                        param.setResult(null);
                                    } catch (Throwable t) {
                                        XposedBridge.log("[HyperFix] Error in Activity.startActivityAsCaller hook: " + t.getMessage());
                                    }
                                }
                            }
                        }
                    );
                    XposedBridge.log("[HyperFix] Hooked Activity.startActivityAsCaller");
                } catch (Throwable t) {
                    XposedBridge.log("[HyperFix] Error hooking Activity.startActivityAsCaller: " + t.getMessage());
                }

                Class<?> arClass = XposedHelpers.findClassIfExists("com.android.server.wm.ActivityRecord", lpparam.classLoader);
                if (arClass != null) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            arClass,
                            "isResolverOrChildActivity",
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    if (Boolean.TRUE.equals(param.getResult())) return;
                                    Object component = XposedHelpers.getObjectField(param.thisObject, "mActivityComponent");
                                    if (component instanceof ComponentName) {
                                        String cls = ((ComponentName) component).getClassName();
                                        if (cls != null && (cls.contains("IntentForwarderActivity")
                                                || cls.contains("ForwardIntentToParent")
                                                || cls.contains("ForwardIntentToManagedProfile"))) {
                                            param.setResult(true);
                                        }
                                    }
                                }
                            }
                        );
                        XposedBridge.log("[HyperFix] Hooked ActivityRecord.isResolverOrChildActivity");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking ActivityRecord.isResolverOrChildActivity: " + t.getMessage());
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error applying IntentForwarderActivity hooks: " + t.getMessage());
            }

            // Fix China ROM reverting Power Button Gemini / Google Assistant to Restart Menu on Reboot
            try {
                Class<?> powerKeyRuleClass = XposedHelpers.findClassIfExists("com.android.server.input.shortcut.singlekeyrule.PowerKeyRule", lpparam.classLoader);
                if (powerKeyRuleClass != null) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            powerKeyRuleClass,
                            "isSupportRsa",
                            XC_MethodReplacement.returnConstant(false)
                        );
                        XposedBridge.log("[HyperFix] Hooked PowerKeyRule.isSupportRsa -> false");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking PowerKeyRule.isSupportRsa: " + t.getMessage());
                    }

                    try {
                        XposedHelpers.findAndHookMethod(
                            powerKeyRuleClass,
                            "postTriggerPowerGuide",
                            String.class,
                            String.class,
                            XC_MethodReplacement.returnConstant(false)
                        );
                        XposedBridge.log("[HyperFix] Hooked PowerKeyRule.postTriggerPowerGuide -> false");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking PowerKeyRule.postTriggerPowerGuide: " + t.getMessage());
                    }

                    try {
                        XposedHelpers.findAndHookMethod(
                            powerKeyRuleClass,
                            "triggerLongPress",
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    try {
                                        String function = (String) XposedHelpers.callMethod(param.thisObject, "getFunction", "long_press_power_key");
                                        if ("launch_google_search".equals(function)) {
                                            boolean intercept = (Boolean) XposedHelpers.callMethod(
                                                param.thisObject,
                                                "postTriggerFunction",
                                                "long_press_power_key",
                                                "launch_google_search",
                                                null,
                                                true
                                            );
                                            XposedBridge.log("[HyperFix] PowerKeyRule.triggerLongPress dispatched launch_google_search: " + intercept);
                                            param.setResult(true);
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log("[HyperFix] Error in PowerKeyRule.triggerLongPress hook: " + t.getMessage());
                                    }
                                }
                            }
                        );
                        XposedBridge.log("[HyperFix] Hooked PowerKeyRule.triggerLongPress");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking PowerKeyRule.triggerLongPress: " + t.getMessage());
                    }
                }

                Class<?> shortcutObserverClass = XposedHelpers.findClassIfExists("com.android.server.policy.MiuiShortcutObserver", lpparam.classLoader);
                if (shortcutObserverClass != null) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            shortcutObserverClass,
                            "isFeasibleFunction",
                            String.class,
                            String.class,
                            Context.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    String function = (String) param.args[1];
                                    if ("launch_google_search".equals(function)) {
                                        param.setResult(true);
                                    }
                                }
                            }
                        );
                        XposedBridge.log("[HyperFix] Hooked MiuiShortcutObserver.isFeasibleFunction for launch_google_search");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking MiuiShortcutObserver.isFeasibleFunction: " + t.getMessage());
                    }

                    try {
                        XposedHelpers.findAndHookMethod(
                            shortcutObserverClass,
                            "hasCustomizedFunction",
                            String.class,
                            String.class,
                            boolean.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    String action = (String) param.args[0];
                                    String function = (String) param.args[1];
                                    if ("long_press_power_key".equals(action) && "launch_google_search".equals(function)) {
                                        param.setResult(true);
                                    }
                                }
                            }
                        );
                        XposedBridge.log("[HyperFix] Hooked MiuiShortcutObserver.hasCustomizedFunction for launch_google_search");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking MiuiShortcutObserver.hasCustomizedFunction: " + t.getMessage());
                    }
                }

                Class<?> singleKeyObserverClass = XposedHelpers.findClassIfExists("com.android.server.policy.MiuiSingleKeyObserver", lpparam.classLoader);
                if (singleKeyObserverClass != null) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            singleKeyObserverClass,
                            "setDefaultFunction",
                            boolean.class,
                            new XC_MethodHook() {
                                private String savedFunction = null;

                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    try {
                                        android.content.ContentResolver cr = (android.content.ContentResolver) XposedHelpers.getObjectField(param.thisObject, "mContentResolver");
                                        int userId = XposedHelpers.getIntField(param.thisObject, "mCurrentUserId");
                                        String current = (String) XposedHelpers.callStaticMethod(
                                            android.provider.Settings.System.class,
                                            "getStringForUser",
                                            cr, "long_press_power_key", userId
                                        );
                                        if ("launch_google_search".equals(current)) {
                                            savedFunction = current;
                                        }
                                    } catch (Throwable ignored) {}
                                }

                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    if ("launch_google_search".equals(savedFunction)) {
                                        try {
                                            android.content.ContentResolver cr = (android.content.ContentResolver) XposedHelpers.getObjectField(param.thisObject, "mContentResolver");
                                            int userId = XposedHelpers.getIntField(param.thisObject, "mCurrentUserId");
                                            String current = (String) XposedHelpers.callStaticMethod(
                                                android.provider.Settings.System.class,
                                                "getStringForUser",
                                                cr, "long_press_power_key", userId
                                            );
                                            if (!"launch_google_search".equals(current)) {
                                                XposedHelpers.callStaticMethod(
                                                    android.provider.Settings.System.class,
                                                    "putStringForUser",
                                                    cr, "long_press_power_key", "launch_google_search", userId
                                                );
                                                XposedBridge.log("[HyperFix] Restored long_press_power_key to launch_google_search");
                                            }
                                            android.provider.Settings.Global.putInt(cr, "power_button_long_press", 5);
                                        } catch (Throwable ignored) {}
                                        savedFunction = null;
                                    }
                                }
                            }
                        );
                        XposedBridge.log("[HyperFix] Hooked MiuiSingleKeyObserver.setDefaultFunction to preserve launch_google_search");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking MiuiSingleKeyObserver.setDefaultFunction: " + t.getMessage());
                    }
                }

                Class<?> triggerHelperClass = XposedHelpers.findClassIfExists("com.android.server.policy.MiuiShortcutTriggerHelper", lpparam.classLoader);
                if (triggerHelperClass != null) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            triggerHelperClass,
                            "shouldShowPowerPanel",
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    try {
                                        android.content.ContentResolver cr = (android.content.ContentResolver) XposedHelpers.getObjectField(param.thisObject, "mContentResolver");
                                        int userId = XposedHelpers.getIntField(param.thisObject, "mCurrentUserId");
                                        String func = (String) XposedHelpers.callStaticMethod(
                                            android.provider.Settings.System.class,
                                            "getStringForUser",
                                            cr, "long_press_power_key", userId
                                        );
                                        if ("launch_google_search".equals(func)) {
                                            param.setResult(false);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            }
                        );
                        XposedBridge.log("[HyperFix] Hooked MiuiShortcutTriggerHelper.shouldShowPowerPanel for launch_google_search");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking MiuiShortcutTriggerHelper.shouldShowPowerPanel: " + t.getMessage());
                    }
                    try {
                        XposedHelpers.findAndHookMethod(
                            triggerHelperClass,
                            "setLongPressPowerBehavior",
                            String.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    String function = (String) param.args[0];
                                    if ("launch_google_search".equals(function) || "launch_voice_assistant".equals(function)) {
                                        Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                                        if (context != null) {
                                            android.provider.Settings.Global.putInt(context.getContentResolver(), "power_button_long_press", 5);
                                            XposedBridge.log("[HyperFix] setLongPressPowerBehavior: set power_button_long_press=5 (Assistant)");
                                        }
                                        param.setResult(null);
                                    }
                                }
                            }
                        );
                        XposedBridge.log("[HyperFix] Hooked MiuiShortcutTriggerHelper.setLongPressPowerBehavior");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking MiuiShortcutTriggerHelper.setLongPressPowerBehavior: " + t.getMessage());
                    }
                }

                Class<?> custHelperClass = XposedHelpers.findClassIfExists("com.android.server.policy.util.PolicyCustFeatureHelper", lpparam.classLoader);
                if (custHelperClass != null) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            custHelperClass,
                            "isUnSupportLaunchGoogleSearch",
                            XC_MethodReplacement.returnConstant(false)
                        );
                        XposedBridge.log("[HyperFix] Hooked PolicyCustFeatureHelper.isUnSupportLaunchGoogleSearch -> false");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking PolicyCustFeatureHelper.isUnSupportLaunchGoogleSearch: " + t.getMessage());
                    }

                    try {
                        XposedHelpers.findAndHookMethod(
                            custHelperClass,
                            "isUnSupportGlobalPowerGuide",
                            XC_MethodReplacement.returnConstant(false)
                        );
                        XposedBridge.log("[HyperFix] Hooked PolicyCustFeatureHelper.isUnSupportGlobalPowerGuide -> false");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking PolicyCustFeatureHelper.isUnSupportGlobalPowerGuide: " + t.getMessage());
                    }
                }

                Class<?> phoneWmClass = XposedHelpers.findClassIfExists("com.android.server.policy.MiuiPhoneWindowManager", lpparam.classLoader);
                if (phoneWmClass != null) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            phoneWmClass,
                            "stopGoogleAssistantVoiceMonitoring",
                            XC_MethodReplacement.returnConstant(true)
                        );
                        XposedBridge.log("[HyperFix] Hooked MiuiPhoneWindowManager.stopGoogleAssistantVoiceMonitoring -> true");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking MiuiPhoneWindowManager.stopGoogleAssistantVoiceMonitoring: " + t.getMessage());
                    }
                }

                Class<?> pwmClass = XposedHelpers.findClassIfExists("com.android.server.policy.PhoneWindowManager", lpparam.classLoader);
                if (pwmClass != null) {
                    try {
                        XposedHelpers.findAndHookMethod(
                            pwmClass,
                            "getResolvedLongPressOnPowerBehavior",
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    int current = ((Integer) param.getResult()).intValue();
                                    if (current == 0) {
                                        Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                                        if (context != null) {
                                            String func = android.provider.Settings.System.getString(
                                                context.getContentResolver(), "long_press_power_key"
                                            );
                                            if ("launch_google_search".equals(func) || "launch_voice_assistant".equals(func)) {
                                                param.setResult(5); // LONG_PRESS_POWER_ASSISTANT
                                            }
                                        }
                                    }
                                }
                            }
                        );
                        XposedBridge.log("[HyperFix] Hooked PhoneWindowManager.getResolvedLongPressOnPowerBehavior");
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error hooking PhoneWindowManager.getResolvedLongPressOnPowerBehavior: " + t.getMessage());
                    }

                    try {
                        for (Method m : pwmClass.getDeclaredMethods()) {
                            if ("initSingleKeyGestureRules".equals(m.getName()) || "init".equals(m.getName())) {
                                XposedBridge.hookMethod(m, new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                        try {
                                            XposedHelpers.setBooleanField(param.thisObject, "mSupportLongPressPowerWhenNonInteractive", true);
                                            XposedBridge.log("[HyperFix] Set mSupportLongPressPowerWhenNonInteractive -> true");
                                        } catch (Throwable ignored) {}
                                    }
                                });
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[HyperFix] Error enabling long press power when non-interactive: " + t.getMessage());
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error applying power button assistant fix: " + t.getMessage());
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

            // Stop HyperOS from hijacking ACTION_OPEN_DOCUMENT to com.android.fileexplorer
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.ActivityTaskManagerServiceImpl",
                    lpparam.classLoader,
                    "mayReferToFileExplore",
                    Intent.class,
                    String.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            Intent intent = (Intent) param.args[0];
                            if (intent != null && "android.intent.action.OPEN_DOCUMENT".equals(intent.getAction())) {
                                XposedBridge.log("[HyperFix] Prevented mayReferToFileExplore hijacking for: " + intent);
                            }
                            return param.args[0];
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked ActivityTaskManagerServiceImpl.mayReferToFileExplore");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking ActivityTaskManagerServiceImpl.mayReferToFileExplore: " + t.getMessage());
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

        // 6. Fix Cross-User / Work Profile Photo Picker in com.android.photopicker
        if ("com.android.photopicker".equals(lpparam.packageName)) {
            XposedBridge.log("[HyperFix] Hooking com.android.photopicker");

            // A. In User 11 (Work Profile), redirect ContentResolver queries to User 0 so personal photos are visible
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.photopicker.core.ApplicationModule",
                    lpparam.classLoader,
                    "provideContentResolver",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Context context = (Context) param.args[0];
                            if (context != null) {
                                int userId = ((Integer) XposedHelpers.callMethod(context, "getUserId")).intValue();
                                if (userId != 0) {
                                    try {
                                        UserHandle systemUser = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "SYSTEM");
                                        Context user0Context = (Context) XposedHelpers.callMethod(
                                            context,
                                            "createPackageContextAsUser",
                                            "android",
                                            0,
                                            systemUser
                                        );
                                        if (user0Context != null) {
                                            param.setResult(user0Context.getContentResolver());
                                            XposedBridge.log("[HyperFix] ApplicationModule.provideContentResolver redirected to User 0");
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log("[HyperFix] Failed to redirect provideContentResolver: " + t.getMessage());
                                    }
                                }
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked ApplicationModule.provideContentResolver");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking ApplicationModule.provideContentResolver: " + t.getMessage());
            }

            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.photopicker.extensions.ContextKt",
                    lpparam.classLoader,
                    "getContentResolverForUser$default",
                    Context.class,
                    UserHandle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            UserHandle uh = (UserHandle) param.args[1];
                            if (uh != null) {
                                int uhId = ((Integer) XposedHelpers.callMethod(uh, "getIdentifier")).intValue();
                                if (uhId != 0) {
                                    UserHandle systemUser = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "SYSTEM");
                                    param.args[1] = systemUser;
                                    XposedBridge.log("[HyperFix] ContextKt.getContentResolverForUser$default redirected from u" + uhId + " to User 0");
                                }
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked ContextKt.getContentResolverForUser$default");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking ContextKt.getContentResolverForUser$default: " + t.getMessage());
            }

            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.photopicker.core.user.UserMonitor",
                    lpparam.classLoader,
                    "getContentResolver",
                    Context.class,
                    UserHandle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            UserHandle uh = (UserHandle) param.args[1];
                            if (uh != null) {
                                int uhId = ((Integer) XposedHelpers.callMethod(uh, "getIdentifier")).intValue();
                                if (uhId != 0) {
                                    UserHandle systemUser = (UserHandle) XposedHelpers.getStaticObjectField(UserHandle.class, "SYSTEM");
                                    param.args[1] = systemUser;
                                    XposedBridge.log("[HyperFix] UserMonitor.getContentResolver redirected from u" + uhId + " to User 0");
                                }
                            }
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked UserMonitor.getContentResolver");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking UserMonitor.getContentResolver: " + t.getMessage());
            }

            // B. Restore DocumentsUI for "Browse" / "More" instead of crashing with com.android.fileexplorer
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.photopicker.hyper.HyperMainActivity",
                    lpparam.classLoader,
                    "getHyperFilePickerName",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            ComponentName comp = (ComponentName) XposedHelpers.callMethod(param.thisObject, "getDocumentssUiComponentName");
                            XposedBridge.log("[HyperFix] getHyperFilePickerName redirected to DocumentsUI: " + comp);
                            return comp;
                        }
                    }
                );
                XposedBridge.log("[HyperFix] Successfully hooked HyperMainActivity.getHyperFilePickerName");
            } catch (Throwable t) {
                XposedBridge.log("[HyperFix] Error hooking HyperMainActivity.getHyperFilePickerName: " + t.getMessage());
            }
        }

        // 7. Fix AirPods Pro "Show in Find Device" in Settings
        if ("com.android.settings".equals(lpparam.packageName) || "com.xiaomi.bluetooth".equals(lpparam.packageName)) {
            hookSettingsFindDevice(lpparam);
        }
    }

    private static volatile boolean sFindDeviceJumpHelperHooked = false;
    private static volatile boolean sFindHeadsetHooked = false;

    private static Intent resolveFindDeviceIntent(Context context) {
        if (context == null) return null;
        PackageManager pm = context.getPackageManager();
        if (pm == null) return null;

        // 1. Try Xiaomi Find Device Main Activity (com.xiaomi.finddevice.FINDDEVICE_MAIN)
        Intent miMain = new Intent("com.xiaomi.finddevice.FINDDEVICE_MAIN");
        miMain.setPackage("com.xiaomi.finddevice");
        miMain.putExtra("intent_source", "source_channel_bluetooth");
        if (miMain.resolveActivity(pm) != null) {
            return miMain;
        }

        // 2. Try Xiaomi Find Device Status Activity (com.xiaomi.finddevice.FINDDEVICE_STATUS)
        Intent miStatus = new Intent("com.xiaomi.finddevice.FINDDEVICE_STATUS");
        miStatus.setPackage("com.xiaomi.finddevice");
        if (miStatus.resolveActivity(pm) != null) {
            return miStatus;
        }

        // 3. Try Xiaomi Find Device Launch Intent
        Intent miLaunch = pm.getLaunchIntentForPackage("com.xiaomi.finddevice");
        if (miLaunch != null) {
            return miLaunch;
        }

        // 4. Try Google Find My Device (com.google.android.apps.adm)
        Intent googleLaunch = pm.getLaunchIntentForPackage("com.google.android.apps.adm");
        if (googleLaunch != null) {
            return googleLaunch;
        }

        // 5. Try legacy action SHARE_LOCATION_ENTRANCE
        Intent action2 = new Intent("com.xiaomi.action.SHARE_LOCATION_ENTRANCE");
        if (action2.resolveActivity(pm) != null) {
            return action2;
        }

        return null;
    }

    private static synchronized void tryHookFindDeviceClasses(ClassLoader classLoader) {
        if (!sFindDeviceJumpHelperHooked) {
            Class<?> helperClass = XposedHelpers.findClassIfExists("plugin.settings.java.offline.FindDeviceJumpHelper", classLoader);
            if (helperClass != null) {
                try {
                    XposedHelpers.findAndHookMethod(
                        helperClass,
                        "getFindDeviceIntent",
                        Context.class,
                        new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                Context ctx = (Context) param.args[0];
                                Intent resolved = resolveFindDeviceIntent(ctx);
                                XposedBridge.log("[HyperFix] FindDeviceJumpHelper.getFindDeviceIntent intercepted -> " + resolved);
                                return resolved;
                            }
                        }
                    );
                    XposedHelpers.findAndHookMethod(
                        helperClass,
                        "isNeedShowFindDeviceEntrance",
                        Context.class,
                        new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                Context ctx = (Context) param.args[0];
                                return resolveFindDeviceIntent(ctx) != null;
                            }
                        }
                    );
                    sFindDeviceJumpHelperHooked = true;
                    XposedBridge.log("[HyperFix] Successfully hooked FindDeviceJumpHelper");
                } catch (Throwable t) {
                    XposedBridge.log("[HyperFix] Error hooking FindDeviceJumpHelper: " + t.getMessage());
                }
            }
        }

        if (!sFindHeadsetHooked) {
            Class<?> headsetClass = XposedHelpers.findClassIfExists("plugin.settings.java.offline.FindHeadset", classLoader);
            if (headsetClass != null) {
                try {
                    XposedHelpers.findAndHookMethod(
                        headsetClass,
                        "startFindDeviceMainActivity",
                        Context.class,
                        String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                Context ctx = (Context) param.args[0];
                                String mac = (String) param.args[1];
                                XposedBridge.log("[HyperFix] FindHeadset.startFindDeviceMainActivity called for MAC: " + mac);
                                if (ctx != null) {
                                    Intent intent = resolveFindDeviceIntent(ctx);
                                    if (intent != null) {
                                        Intent target = new Intent(intent);
                                        if (mac != null) {
                                            target.putExtra("mac", mac);
                                        }
                                        target.addCategory(Intent.CATEGORY_DEFAULT);
                                        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        try {
                                            ctx.startActivity(target);
                                            XposedBridge.log("[HyperFix] Successfully started Find Device activity: " + target);
                                            param.setResult(null); // Short-circuit original method to avoid NPE
                                        } catch (Throwable t) {
                                            XposedBridge.log("[HyperFix] Failed starting Find Device activity: " + t.getMessage());
                                        }
                                    }
                                }
                            }
                        }
                    );
                    sFindHeadsetHooked = true;
                    XposedBridge.log("[HyperFix] Successfully hooked FindHeadset.startFindDeviceMainActivity");
                } catch (Throwable t) {
                    XposedBridge.log("[HyperFix] Error hooking FindHeadset: " + t.getMessage());
                }
            }
        }
    }

    private void hookSettingsFindDevice(final LoadPackageParam lpparam) {
        XposedBridge.log("[HyperFix] Setting up hooks in " + lpparam.packageName + " for Find Device");

        // 1. Try immediately in case class is already loaded
        tryHookFindDeviceClasses(lpparam.classLoader);

        // 2. Hook ClassLoader to catch dynamic split loading by Qigsaw
        try {
            XC_MethodHook loadClassHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (sFindDeviceJumpHelperHooked && sFindHeadsetHooked) {
                        return;
                    }
                    String className = (String) param.args[0];
                    if (className != null && className.startsWith("plugin.settings.java.offline.")) {
                        Class<?> clazz = (Class<?>) param.getResult();
                        if (clazz != null && clazz.getClassLoader() != null) {
                            tryHookFindDeviceClasses(clazz.getClassLoader());
                        }
                    }
                }
            };

            XposedHelpers.findAndHookMethod(
                ClassLoader.class,
                "loadClass",
                String.class,
                boolean.class,
                loadClassHook
            );
        } catch (Throwable t) {
            XposedBridge.log("[HyperFix] Error hooking ClassLoader.loadClass in " + lpparam.packageName + ": " + t.getMessage());
        }
    }

    private static boolean isWebIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        Uri data = intent.getData();
        if (data == null) return false;
        String scheme = data.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static volatile long sLastToastTime = 0;
    private static volatile String sLastToastMsg = "";

    private static void showToast(final String message) {
        if (message == null || message.isEmpty()) return;
        long now = System.currentTimeMillis();
        synchronized (MainHook.class) {
            if (now - sLastToastTime < 2000) {
                return;
            }
            if (message.equals(sLastToastMsg) && (now - sLastToastTime < 4000)) {
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
