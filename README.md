# HyperFix

An Xposed / LSPosed module designed to fix specific Xiaomi HyperOS bugs (HyperOS 2.0 / 3.0 on Android 14–16), primarily around Work Profile and system integration.

Tested on: **POCO X8 Pro Max (HyperOS 3.0 / Android 16)**

## Features

- **Work Profile Text Selection Crash:** Fixes text selection force close / `SecurityException` in Work Profile when accessibility services are enabled.
- **Work Profile Screenshot "Couldn't Save":** Fixes HyperOS screenshot failure in Work Profile.
- **Launcher Recents Work Profile App Names:** Fixes missing/blank app titles in POCO / HyperOS System Launcher Recents for apps installed only in Work Profile.

## Installation

### Prerequisites
- Root (KernelSU, Magisk, or APatch)
- LSPosed (or Vector Framework) active

### Steps
1. Download the latest `HyperFix.apk` from [Releases](https://github.com/bgwastu/hyperfix/releases).
2. Install the APK.
3. Open LSPosed / Vector, enable **HyperFix**, and ensure the following scopes are checked:
   - **System Framework** (`android` / `system`)
   - **Screenshot** (`com.miui.screenshot`)
   - **System / POCO Launcher** (`com.mi.android.globallauncher` / `com.miui.home`)
4. Reboot your phone or restart the launcher (`pkill -f com.mi.android.globallauncher`).
