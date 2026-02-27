# USB Debug

Diagnose USB connection issues between Android and iOS devices.

## Steps

1. Check connected USB devices: `adb shell dumpsys usb`
2. Check USB mode and data role:
   ```
   adb shell cat /sys/class/typec/*/data_role
   adb shell cat /sys/class/typec/*/power_role
   ```
3. Look for Apple devices in USB device list (VID 0x05AC)
4. Check app logs for USB detection: `adb logcat -s "UsbTransport:*" "AppleDeviceDetector:*" "UsbMuxConnection:*"`
5. Report findings and suggest fixes

## Common Issues
- **Phone in device mode (UFP):** Need USB OTG adapter to force host mode (DFP). A USB-C to USB-C cable alone won't force Android into host mode.
- **No USB permission:** The app must request permission via `UsbManager.requestPermission()`. Check if the permission dialog appeared.
- **Apple MUX interface not found:** The iOS device may not expose the usbmuxd interface (subclass 0xFE, protocol 2) without proper host mode.
- **OTG not enabled:** Some Android devices require enabling OTG in Settings > Additional Settings > OTG.
