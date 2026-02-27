# Build APK (Manual Pipeline)

Build the debug APK using the manual build pipeline (no Gradle required).

## Steps

1. Read `build-manual/sources.txt` and `build-manual/d8files.txt` for current file lists
2. Set up toolchain paths:
   - **Build tools:** `$ANDROID_HOME/build-tools/` (use latest available version)
   - **Platform:** `$ANDROID_HOME/platforms/` (use latest android-XX/android.jar)
   - **Libs:** `build-manual/libs/` (kotlin-stdlib, kotlinx-coroutines-core-jvm, dd-plist, bcprov, bcpkix, bcutil)
   - **Keystore:** `build-manual/debug.keystore` (alias: `key`, pass: `android`)
   - **kotlinc:** Find via `which kotlinc` or locate in Android Studio plugins
3. Execute the full manual build pipeline:
   - **aapt2 compile** — compile individual resource files from `app/src/main/res/` (compile each file separately, `--dir` doesn't work with build-tools 36+)
   - **aapt2 link** — link compiled resources with `build-manual/AndroidManifest.xml`, generate R.java into `build-manual/gen/`
   - **kotlinc** — compile all sources from `build-manual/sources.txt` with classpath including android.jar and all libs
   - **d8** — convert class files + library JARs to DEX (use `@filelist.txt` syntax for long arg lists)
   - **zip** — add `classes.dex` to the base APK from aapt2 link
   - **zipalign** — 4-byte align the APK
   - **apksigner** — sign with debug keystore
4. Regenerate `build-manual/d8files.txt` from compiled classes if new sources were added
5. Report the final APK path and size

## Output
- APK: `build-manual/apk/app-debug.apk`
