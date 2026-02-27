# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**app-android-ipa-installer** — An Android application that installs IPA files onto iOS devices connected via USB OTG cable, **without requiring Android root access**. Inspired by [OTGLocation](https://github.com/cczhr/OTGLocation) which communicates with iOS over USB from Android (but requires root).

### Key Difference from OTGLocation

OTGLocation uses cross-compiled `usbmuxd` + `libimobiledevice` native binaries executed via `su` (root). This project must achieve the same iOS USB communication **without root** by implementing the usbmuxd protocol directly in Kotlin/JVM using Android's USB Host API (`android.hardware.usb`).

## Architecture

### Communication Stack

```
Android App (Kotlin)
    │
    ├── UI Layer (Jetpack Compose)
    │   └── IPA file picker, device status, install progress
    │
    ├── USB Layer (android.hardware.usb — NO ROOT)
    │   ├── UsbManager — device detection (VID 0x05AC = Apple)
    │   ├── UsbDeviceConnection — raw USB bulk transfers
    │   └── UsbEndpoint — read/write endpoints
    │
    ├── usbmuxd Protocol (pure Kotlin implementation)
    │   ├── MuxHeader (version, type, tag, length)
    │   ├── Plist serialization (binary plist / XML plist)
    │   ├── Device pairing (TLS handshake, pair record)
    │   └── TCP tunnel multiplexing
    │
    ├── lockdownd Protocol (pure Kotlin)
    │   ├── Device info queries
    │   ├── Service start requests
    │   └── TLS session upgrade
    │
    ├── AFC Protocol (Apple File Conduit — pure Kotlin)
    │   ├── File upload to iOS staging area
    │   └── Directory operations
    │
    └── installation_proxy Protocol (pure Kotlin)
        ├── IPA install command
        ├── Progress callbacks
        └── App management (list, uninstall)
```

### Protocol Details

| Protocol | Port | Purpose |
|----------|------|---------|
| usbmuxd | USB bulk transfer | TCP multiplexing over USB, device discovery |
| lockdownd | mux:62078 | Device info, pairing, starting services |
| AFC (`com.apple.afc`) | dynamic | File transfer to iOS media/PublicStaging |
| installation_proxy (`com.apple.mobile.installation_proxy`) | dynamic | IPA install/uninstall/list apps |

### IPA Installation Flow

1. **USB Detection** — Android UsbManager detects Apple device (VID `0x05AC`)
2. **Permission** — Request USB permission from user (Android USB Host API)
3. **usbmuxd Handshake** — Establish mux connection over USB bulk endpoints
4. **Pairing** — Exchange pair records with lockdownd (TLS, certificates)
5. **Start AFC** — Request `com.apple.afc` service from lockdownd
6. **Upload IPA** — Transfer IPA file to `/PublicStaging/` via AFC protocol
7. **Start installation_proxy** — Request `com.apple.mobile.installation_proxy`
8. **Install** — Send `Install` command with IPA path, monitor progress via callbacks
9. **Cleanup** — Remove staged IPA, close connections

### No-Root USB Access Strategy

Android's USB Host API provides userspace USB access without root:
- `UsbManager.getDeviceList()` — enumerate connected USB devices
- `UsbManager.requestPermission()` — user grants access per-device
- `UsbDeviceConnection.bulkTransfer()` — raw USB I/O to Apple device
- Apple devices expose bulk IN/OUT endpoints on configuration 1, interface 1 (usbmuxd interface), with subclass 0xFE and protocol 2

The key insight: OTGLocation's root requirement comes from running `usbmuxd` daemon with `libusb`. By reimplementing the usbmuxd wire protocol in Kotlin and using Android's USB Host API directly, root is unnecessary.

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin (latest stable) |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 34 (Android 14) |
| UI | Jetpack Compose + Material 3 |
| Build | Gradle (Kotlin DSL), AGP |
| Architecture | MVVM, Kotlin Coroutines + Flow |
| USB | android.hardware.usb (USB Host API) |
| TLS | BouncyCastle (for Apple pairing certificates) |
| Plist | dd-plist or custom binary plist parser |
| DI | Hilt |
| File picker | SAF (Storage Access Framework) |

## Build & Development

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run single test class
./gradlew test --tests "com.example.ipainstaller.protocol.UsbmuxdProtocolTest"

# Run instrumented tests (connected device)
./gradlew connectedAndroidTest

# Lint
./gradlew lint

# Clean build
./gradlew clean
```

## Package Structure

```
com.example.ipainstaller/
├── ui/                     # Compose UI screens
│   ├── main/              # Main screen (device status, file picker)
│   ├── install/           # Installation progress screen
│   └── theme/             # Material 3 theme
├── usb/                    # Android USB Host API wrapper
│   ├── AppleDeviceDetector # VID 0x05AC detection + permission
│   ├── UsbTransport        # Raw bulk read/write abstraction
│   └── UsbMuxConnection    # USB → usbmuxd bridge
├── protocol/               # Apple protocol implementations
│   ├── usbmuxd/           # usbmuxd wire protocol (header, plist messages)
│   ├── lockdownd/         # lockdownd protocol (pairing, service start)
│   ├── afc/               # AFC file transfer protocol
│   └── installproxy/      # installation_proxy protocol
├── plist/                  # Binary & XML plist serialization
├── crypto/                 # TLS pairing, certificate generation (BouncyCastle)
├── model/                  # Data classes (DeviceInfo, InstallProgress, etc.)
├── viewmodel/              # ViewModels for MVVM
└── di/                     # Hilt modules
```

## Critical Implementation Notes

### usbmuxd Wire Protocol (over USB)
- **Header:** 16 bytes — `uint32 length, uint32 version(1), uint32 type, uint32 tag`
- **Payload:** binary plist or XML plist depending on version
- Message types: `Result(1)`, `Connect(2)`, `Listen(3)`, `Attached(4)`, `Detached(5)`
- The Android app acts as the usbmuxd **client AND daemon** — it speaks directly to the iPhone's USB interface

### Apple USB Interface Selection
- Apple devices have multiple USB configurations/interfaces
- The correct interface for usbmuxd communication: **subclass 0xFE, protocol 2** (Apple USB Multiplexor)
- Must call `UsbDeviceConnection.claimInterface()` on this interface

### Pairing Without iTunes/Finder
- First-time pairing requires generating a root CA, host certificate, and device certificate
- Pair records must be stored locally (SharedPreferences or app files)
- The "Trust This Computer?" dialog appears on the iOS device during first pairing
- Subsequent connections use stored pair records

### iOS Version Considerations
- iOS 17+ changed the DeveloperDiskImage approach to use "personalized" disk images (DDI)
- For IPA installation, DeveloperDiskImage is NOT needed — AFC and installation_proxy are available without it
- Sideloading IPAs requires the IPA to be properly signed (enterprise, ad-hoc, or dev profile matching the device)

## Reference Projects

- [OTGLocation](https://github.com/cczhr/OTGLocation) — Android→iOS USB communication (root required)
- [libimobiledevice](https://github.com/libimobiledevice/libimobiledevice) — C library implementing Apple protocols (reference for protocol details)
- [pymobiledevice3](https://github.com/doronz88/pymobiledevice3) — Python implementation (excellent protocol reference)
- [tidevice](https://github.com/alibaba/tidevice) — Python tool for iOS device management
- [cczhr/libimobiledevice_android](https://github.com/cczhr/libimobiledevice_android) — Cross-compiled binaries used by OTGLocation
