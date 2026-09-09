# HyperFix

An Xposed / LSPosed module designed to fix specific Xiaomi HyperOS bugs (HyperOS 2.0 / 3.0 on Android 14–16), primarily around Work Profile, Quick Settings, and system integration.

Tested on: **Xiaomi HyperOS 2.0 / 3.0 (Android 15–16)**

## Features

- **Work Profile Text Selection Crash:** Fixes text selection force close / `SecurityException` in Work Profile when accessibility services are enabled.
- **Work Profile Screenshot "Couldn't Save":** Fixes HyperOS screenshot failure in Work Profile.
- **Launcher Recents Work Profile App Names:** Fixes missing/blank app titles in POCO / HyperOS System Launcher Recents for apps installed only in Work Profile.
- **Work Profile Quick Settings Tiles:**
  - Enables discovery of Quick Settings tiles for apps installed exclusively in Work Profile (hidden by default in HyperOS / AOSP).
  - Automatically prepends `[WORK] ` prefix to Work Profile tiles for clear distinction in the status bar and edit sheet.
  - Fixes lifecycle binding so tile services run directly inside their Work Profile container.
  - Fixes SystemUI crash when long-pressing Work Profile tiles by routing App Info launches into the target user.
  - Automatically disables and dims Work Profile tiles when Work Profile is paused (quiet mode) or stopped.
- **Cross-Profile Link & Intent Sharing ("Blocked by your IT admin" Fix):** Fixes the IT admin block screen when attempting to open web links or deep links in Work Profile apps from the Personal profile by dynamically authorizing cross-profile intent forwarding in `PackageManagerService` for users within the same profile group.
- **Telephony Radio Mode Switching:** Allows companion network tools to toggle between 5G and forced 4G in-process without `SecurityException`.

## Installation

### Prerequisites
- Root (KernelSU, Magisk, or APatch)
- LSPosed (or Vector Framework) active

### Steps
1. Download the latest `HyperFix.apk` from [Releases](https://github.com/bgwastu/hyperfix/releases).
2. Install the APK.
3. Open LSPosed / Vector, enable **HyperFix**, and ensure the following scopes are checked:
   - **System Framework** (`android` / `system`)
   - **System UI** (`com.android.systemui`)
   - **Phone** (`com.android.phone`)
   - **Screenshot** (`com.miui.screenshot`)
   - **System / POCO Launcher** (`com.mi.android.globallauncher` / `com.miui.home`)
4. Reboot your phone or restart the respective system processes.
