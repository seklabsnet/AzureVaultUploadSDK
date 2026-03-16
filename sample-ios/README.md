# AzureVault iOS Sample App

## Setup

1. Build the XCFramework:
```bash
./gradlew :upload-api:assembleAzureVaultUploadSDKReleaseXCFramework
```

2. Open Xcode, create new iOS App project in this directory

3. Add the XCFramework:
   - Drag `upload-api/build/XCFrameworks/release/AzureVaultUploadSDK.xcframework` into the project
   - Or add via SPM using the root `Package.swift`

4. Copy `AzureVaultSampleApp.swift` and `UploadView.swift` into the project

5. Run on simulator or device

## What it demonstrates

- SDK initialization (5 lines)
- File picker integration
- Upload with progress tracking
- Pause/Resume/Cancel
- Error handling with retry
- SKIE: StateFlow → AsyncSequence, sealed class → switch/case
