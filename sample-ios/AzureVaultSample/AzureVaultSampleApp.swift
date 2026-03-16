import SwiftUI
import AzureVaultUploadSDK

@main
struct AzureVaultSampleApp: App {

    init() {
        // SDK Initialization — 5 satır, bir kez yazılır
        AzureVaultUpload.shared.initialize(
            config: UploadConfig(
                baseUrl: "https://YOUR_FUNCTION_APP.azurewebsites.net/api",
                appId: "centauri",
                clientId: "centauri",
                clientSecret: "REDACTED_SECRET",
                cdnBaseUrl: "https://YOUR_CDN_ENDPOINT.azurefd.net"
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            UploadView()
        }
    }
}
