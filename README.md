# LuxGuard for Redmi K90 (HyperOS 4)

A small native Android application with exactly **one button**. The first tap activates a custom sensor-based brightness controller, targeting 85% of an explicitly defined lux-to-brightness reference curve. Tapping again turns it off.

## Important: actual system mode

On successful activation, LuxGuard first saves the original `SCREEN_BRIGHTNESS_MODE` and `SCREEN_BRIGHTNESS` to private SharedPreferences. It then sets **SCREEN_BRIGHTNESS_MODE_MANUAL** and verifies the change before storing `enabled=true`. While enabled, the app uses the phone's ambient light sensor, a median filter, exponential smoothing, logarithmic lux interpolation and transition/dead-zone logic. It writes real `Settings.System.SCREEN_BRIGHTNESS` values and uses no overlay or Extra Dim.

On explicit OFF it stops the service and restores the saved brightness and the **previous mode**. When the original mode was AUTOMATIC, it returns to AUTOMATIC. If the original mode was MANUAL, it restores MANUAL.

This replaces, rather than coexists with, the OEM auto-brightness controller. Reference curve points are design defaults, **not** extracted from Xiaomi's firmware. 85% refers to normalized brightness, **not** necessarily 85% of emitted nits. Manual overrides can train only toward lower brightness.

## Install and use

Install the APK from the GitHub Release. Tap the one button to open Android's Modify system settings permission screen when needed. Grant the permission and return to LuxGuard. Approve notification permission if requested. On Xiaomi's app info screen, enable background autostart and set battery mode to unrestricted if your firmware provides those options. Neither privilege can be silently granted by an ordinary app.

The notification comes from an Android foreground service and the app requests BOOT_COMPLETED recovery. This **cannot** guarantee recovery from Xiaomi force-stop, aggressive process killing, or Android's restrictions on starting foreground services after boot. In those cases reopen LuxGuard. Always switch OFF from the app before uninstalling if possible.

## Debug build and release

GitHub Actions workflow `.github/workflows/android.yml` runs on pushes to `main` and manual dispatch. It uses JDK 17, Gradle 8.11.1, Android SDK 35, runs `gradle :app:assembleDebug`, uploads the APK artifact, and creates/updates GitHub prerelease **v0.1.0-debug** containing `app-debug.apk`, only after a successful build. Debug APKs are automatically debug-signed by Android Gradle Plugin.

No root, Magisk, Shizuku, ADB helper, Tasker or external running application is required. The GitHub Actions build toolchain is a development-time dependency, not an on-device helper.

## Known limits

This is an internal experimental build, not a validated Redmi K90 release. Actual device tests are required for Xiaomi's display brightness handling, background lifecycle, screen-on sensing, notification policy, and sleep/wake behavior. Some apps or ROM features may temporarily override brightness. Force-stop may leave the system in manual mode until the user reopens the app and taps OFF. This utility is not a burn-in prevention guarantee.
