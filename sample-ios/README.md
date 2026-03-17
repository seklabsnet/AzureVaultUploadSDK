# AzureVault iOS Sample App

## Setup

1. Build the XCFramework:
```bash
./gradlew :upload-api:assembleAzureVaultUploadSDKReleaseXCFramework
```

2. Open `AzureVaultSample.xcodeproj` in Xcode

3. Select a simulator or device, then Run

## Re-generating the Xcode project

If you modify `project.yml`, regenerate with:
```bash
cd sample-ios
xcodegen generate
```

## What it demonstrates

- SDK initialization
- Dev Test Menu with all upload scenarios
- Auto-download test files from picsum.photos
- File picker for image, video, document, audio
- Upload with real-time progress tracking
- Pause / Resume / Cancel
- Batch upload
- Validation error handling (empty file)
- SKIE interop: StateFlow → AsyncSequence, sealed class → switch/case
