# LuxGuard for Redmi K90 / HyperOS 4

## v0.1.1-debug: real-device calibration, experimental

v0.1.0 incorrectly used an invented reference curve. The user measured the Xiaomi stock automatic brightness to be **darker** than v0.1.0 LuxGuard in similar conditions. v0.1.1 removes that curve completely and **refuses to control brightness without calibration on the device**.

The APK presents one visible button. On initial tap it asks for Modify system settings permission, turns on Xiaomi Auto Brightness, and enters calibration mode. For each of at least eight distinct lighting environments, keep the phone still for 9+ seconds and tap the *same button* to record the ambient light sensor's lux value and `Settings.System.SCREEN_BRIGHTNESS`. Use a broad range from dark rooms to outdoor shade, NOT direct sunlight as a requirement. When eight valid distinct measurements have been collected, it builds a conservative stepwise lower envelope (a lower measured endpoint across each interval), takes floor(0.85 × max(1, observed brightness minus 2)) and turns on LuxGuard. During control, it switches the OEM mode to MANUAL, writes the actual system brightness, and restores AUTOMATIC on OFF. Long-press the same button **while disabled** to clear calibration and start over.

The calibration engine checks stable lux and brightness during each sampling window, stores samples, rejects duplicate lux, flat data and obviously nonmonotonic brightness samples. It requires at least eight different light levels and 20x lux coverage, and binds the profile to `Build.FINGERPRINT` with 30 day expiration. It never extrapolates beyond calibrated lux; when lux is outside that range or an invalid profile is detected, the service stops and restores the original Xiaomi auto mode. This protects against claims of unknown curve behavior.

**CRITICAL LIMITATION:** On Android, the public `SCREEN_BRIGHTNESS` setting does NOT promise to expose the final applied brightness in automatic mode. Passing the checks does not prove measured settings equal display hardware output or that all intermediate OEM values are monotonic. Manufacturer-specific auto brightness may be hysteretic, adaptive to manual changes, temperature, HBM, and display state. The 85% guarantee applies only to **measured setting-space samples and conservative assumptions**, not actual nits or every possible lux. **Do not claim a proved 15% reduction versus Xiaomi across all light levels.**

For independent testing, keep the same ambient setting stable and compare stock Auto (wait at least 10 seconds) against LuxGuard (same phone orientation and content). Repeat at 8+ tested lux levels. If Xiaomi is darker anywhere, **mark test failed**, disable LuxGuard and collect new measurements. A handheld lux meter or photometer can test physical luminance more accurately. The app intentionally has no user-configurable brightness multiplier until the OEM sampling mechanism is validated.

## Safety and lifecycle

- No root, Magisk, Shizuku, ADB helper, overlays, Extra Dim, or third-party runtime service.
- Only Android APIs, `WRITE_SETTINGS`, `TYPE_LIGHT`, foreground service, notifications and boot broadcast.
- The app cannot override HyperOS force-stop policy and must be reopened if forcibly stopped.
- If the app is killed while active, its mode may remain MANUAL; re-open then tap OFF to restore.
- In the Xiaomi settings enable background autostart and unrestricted battery when available.
- Changes in firmware invalidate the stored profile automatically.

## Build

The Actions workflow runs `gradle :app:testDebugUnitTest :app:assembleDebug`, checks for an APK, attaches it as an Actions artifact and publishes GitHub prerelease `v0.1.1-debug` with APK attached. JDK 17, Android SDK 35 and Gradle 8.11.1. Debug signature only. No Play Store assets needed.
