# Build APK (Gradle)

Build the debug APK using the standard Gradle build system.

## Steps

1. Run `chmod +x gradlew && ./gradlew assembleDebug`
2. Report the APK path and size

## Output
- APK: `app/build/outputs/apk/debug/app-debug.apk`

## Notes
- Requires JDK 17+
- Requires Android SDK with compileSdk 35 and build-tools
- The Gradle build uses Jetpack Compose + Hilt (full-featured UI)
- The manual build (`/build-manual`) uses plain Android Views (no Compose/Hilt)
