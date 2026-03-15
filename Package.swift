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
            // For local development:
            path: "upload-api/build/XCFrameworks/release/AzureVaultUploadSDK.xcframework"
            // For remote distribution, replace with:
            // url: "https://github.com/company/AzureVaultUploadSDK/releases/download/1.0.0/AzureVaultUploadSDK.xcframework.zip",
            // checksum: "<sha256-checksum>"
        ),
    ]
)
