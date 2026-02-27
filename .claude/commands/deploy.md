# Deploy to Device

Install the APK on a connected Android device and launch it.

## Steps

1. Check which APK exists:
   - Manual build: `build-manual/apk/app-debug.apk`
   - Gradle build: `app/build/outputs/apk/debug/app-debug.apk`
2. If neither exists, ask user which build to run first
3. Install: `adb install -r <apk-path>`
4. Launch: `adb shell am start -n com.example.ipainstaller/.ui.main.MainActivityBasic`
5. Show logcat filtered by app: `adb logcat --pid=$(adb shell pidof com.example.ipainstaller) -v time`

## Notes
- For the Gradle build, the main activity is `.ui.main.MainActivity` (Compose)
- For the manual build, the main activity is `.ui.main.MainActivityBasic` (plain Views)
- Use `adb devices` to verify a device is connected first
- For WiFi ADB: `adb connect <ip>:5555`
