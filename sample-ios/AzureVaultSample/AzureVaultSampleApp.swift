import SwiftUI
import AzureVaultUploadSDK

@main
struct AzureVaultSampleApp: App {

    init() {
        AzureVaultUpload.shared.initialize(
            config: UploadConfig(
                baseUrl: "https://YOUR_FUNCTION_APP.azurewebsites.net/api",
                appId: "YOUR_APP_ID",
                clientId: "YOUR_CLIENT_ID",
                clientSecret: "YOUR_CLIENT_SECRET_HERE",
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
