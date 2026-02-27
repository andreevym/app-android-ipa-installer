# Create Test IPA

Generate a dummy IPA file for testing the installer without a real iOS app.

## Steps

1. Run `make ipa-test` to create `test-ipa/TestApp.ipa`
2. Optionally push to device: `make ipa-push` (sends to `/sdcard/Download/TestApp.ipa`)

## What it creates
A minimal valid IPA structure:
```
TestApp.ipa
└── Payload/
    └── TestApp.app/
        ├── Info.plist (CFBundleIdentifier: com.example.testapp)
        └── TestApp (dummy executable)
```

This IPA is NOT signed and won't actually install on a real iOS device, but it exercises the full upload pipeline (AFC file transfer).
