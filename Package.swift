// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "AzureVaultUploadSDK",
    platforms: [
        .iOS(.v15),
    ],
    products: [
        .library(
            name: "AzureVaultUploadSDK",
            targets: ["AzureVaultUploadSDK"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "AzureVaultUploadSDK",
            url: "https://github.com/seklabsnet/AzureVaultUploadSDK/releases/download/v1.0.0/AzureVaultUploadSDK.xcframework.zip",
            checksum: "316383cb9ced850d34ebbc67c439941412044c7808bbb2e255321d18f26c54ff"
        ),
    ]
)
