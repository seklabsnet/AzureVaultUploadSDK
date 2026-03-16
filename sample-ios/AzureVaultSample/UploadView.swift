import SwiftUI
import UniformTypeIdentifiers
import AzureVaultUploadSDK

struct UploadView: View {
    @StateObject private var vm = UploadObservable()
    @State private var showFilePicker = false

    var body: some View {
        NavigationView {
            VStack(spacing: 24) {
                Spacer()

                // Upload button
                Button(action: { showFilePicker = true }) {
                    Label("Dosya Sec ve Yukle", systemImage: "arrow.up.doc.fill")
                        .font(.headline)
                        .padding()
                        .frame(maxWidth: .infinity)
                        .background(Color.accentColor)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                }
                .padding(.horizontal)

                // State display
                stateView

                Spacer()
            }
            .navigationTitle("AzureVault Sample")
            .fileImporter(
                isPresented: $showFilePicker,
                allowedContentTypes: [.item],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first {
                        vm.upload(url: url)
                    }
                case .failure(let error):
                    vm.errorMessage = error.localizedDescription
                }
            }
        }
    }

    @ViewBuilder
    private var stateView: some View {
        switch vm.state {
        case .idle:
            Text("Bir dosya secin")
                .foregroundColor(.secondary)

        case .loading(let message):
            VStack(spacing: 8) {
                ProgressView()
                Text(message)
                    .foregroundColor(.secondary)
            }

        case .uploading(let progress, let speed):
            VStack(spacing: 12) {
                ProgressView(value: Double(progress))
                    .progressViewStyle(.linear)
                    .padding(.horizontal)

                Text("\(Int(progress * 100))%")
                    .font(.title2.bold())

                if speed > 0 {
                    Text("\(formatSpeed(speed)) / sn")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }

                HStack(spacing: 16) {
                    Button("Duraklat") { vm.pause() }
                        .buttonStyle(.bordered)
                    Button("Iptal") { vm.cancel() }
                        .buttonStyle(.bordered)
                        .tint(.red)
                }
            }
            .padding(.horizontal)

        case .paused(let progress):
            VStack(spacing: 12) {
                Text("Duraklatildi — \(Int(progress * 100))%")
                    .font(.headline)
                Button("Devam Et") { vm.resume() }
                    .buttonStyle(.borderedProminent)
            }

        case .done(let fileId):
            VStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 48))
                    .foregroundColor(.green)
                Text("Yukleme tamamlandi!")
                    .font(.headline)
                Text("File ID: \(fileId)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

        case .error(let message, let isRetryable):
            VStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 48))
                    .foregroundColor(.red)
                Text(message)
                    .foregroundColor(.red)
                    .multilineTextAlignment(.center)
                if isRetryable {
                    Button("Tekrar Dene") { vm.retry() }
                        .buttonStyle(.borderedProminent)
                }
            }
            .padding(.horizontal)
        }
    }

    private func formatSpeed(_ bytesPerSecond: Int64) -> String {
        let kb = Double(bytesPerSecond) / 1024.0
        if kb < 1024 {
            return String(format: "%.0f KB", kb)
        }
        let mb = kb / 1024.0
        return String(format: "%.1f MB", mb)
    }
}

// MARK: - ViewModel Bridge (SKIE makes this native)

enum UploadUIState {
    case idle
    case loading(String)
    case uploading(Float, Int64)
    case paused(Float)
    case done(String)
    case error(String, Bool)
}

class UploadObservable: ObservableObject {
    @Published var state: UploadUIState = .idle
    @Published var errorMessage: String?

    private let viewModel = UploadViewModel()

    func upload(url: URL) {
        let file = PlatformFile(url: url)
        viewModel.upload(fileRef: file, entityType: "document", entityId: "doc_001")

        // SKIE converts StateFlow to AsyncSequence
        Task {
            for await uiState in viewModel.state {
                await MainActor.run {
                    switch uiState {
                    case is UploadUiState.Idle:
                        self.state = .idle
                    case let loading as UploadUiState.Loading:
                        self.state = .loading(loading.message)
                    case let uploading as UploadUiState.Uploading:
                        self.state = .uploading(uploading.progress, uploading.speed)
                    case let paused as UploadUiState.Paused:
                        self.state = .paused(paused.progress)
                    case let done as UploadUiState.Done:
                        self.state = .done(done.fileId)
                    case let error as UploadUiState.Error:
                        self.state = .error(error.message, error.isRetryable)
                    default:
                        break
                    }
                }
            }
        }
    }

    func pause() { viewModel.pause() }
    func resume() { viewModel.resume() }
    func cancel() { viewModel.cancel() }
    func retry() { viewModel.retry() }
}

#Preview {
    UploadView()
}
