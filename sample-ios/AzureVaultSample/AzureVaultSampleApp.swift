import SwiftUI
import AzureVaultUploadSDK

@main
struct AzureVaultSampleApp: App {

    init() {
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
            DevMenuView()
        }
    }
}
