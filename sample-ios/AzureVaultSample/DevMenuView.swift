import SwiftUI
import UniformTypeIdentifiers
import AzureVaultUploadSDK

// MARK: - Dev Menu

struct DevMenuView: View {
    @StateObject private var vm = DevMenuViewModel()
    @State private var showSheet = false
    @State private var showFilePicker = false
    @State private var pickerContentTypes: [UTType] = [.item]

    var body: some View {
        NavigationView {
            List {
                // Environment Info
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        InfoLabel(key: "App", value: "centauri")
                        InfoLabel(key: "Backend", value: "YOUR_FUNCTION_APP.azurewebsites.net")
                        InfoLabel(key: "Max Upload", value: "100 MB (image), 5 GB (video)")
                        InfoLabel(key: "Chunk Threshold", value: "4 MB")
                    }
                    .padding(.vertical, 4)
                } header: {
                    Text("Environment")
                }

                // Image Size Tests
                Section {
                    TestRow(
                        title: "Tiny Image (~5 KB)",
                        description: "Cok kucuk dosya, single-shot upload.",
                        color: .blue
                    ) {
                        vm.autoDownload(name: "Tiny Image", url: "https://picsum.photos/100/100", fileName: "tiny.jpg", entityType: "image")
                        showSheet = true
                    }

                    TestRow(
                        title: "Small Image (~50 KB)",
                        description: "Normal boyut, single-shot upload. Chunking yok.",
                        color: .blue
                    ) {
                        vm.autoDownload(name: "Small Image", url: "https://picsum.photos/400/400", fileName: "small.jpg", entityType: "image")
                        showSheet = true
                    }

                    TestRow(
                        title: "Medium Image (~500 KB)",
                        description: "Orta boyut, hala single-shot. 4 MB threshold'a yakin.",
                        color: .blue
                    ) {
                        vm.autoDownload(name: "Medium Image", url: "https://picsum.photos/1500/1500", fileName: "medium.jpg", entityType: "image")
                        showSheet = true
                    }

                    TestRow(
                        title: "Large Image (~5 MB+)",
                        description: "Chunk threshold'u asiyor. Chunked upload + progress.",
                        color: .blue
                    ) {
                        vm.autoDownload(name: "Large Image", url: "https://picsum.photos/5000/5000", fileName: "large.jpg", entityType: "image")
                        showSheet = true
                    }
                } header: {
                    SectionLabel("Image Size Tests", color: .blue)
                }

                // File Picker Tests
                Section {
                    TestRow(
                        title: "Pick Image",
                        description: "Galeriden resim sec. JPEG, PNG, HEIC, WebP.",
                        color: .green
                    ) {
                        vm.activeTestName = "Image Upload"
                        vm.pendingEntityType = "image"
                        pickerContentTypes = [.image]
                        showFilePicker = true
                    }

                    TestRow(
                        title: "Pick Video",
                        description: "Video sec. MP4, MOV, MKV. Buyuk dosyalar chunked.",
                        color: .pink
                    ) {
                        vm.activeTestName = "Video Upload"
                        vm.pendingEntityType = "video"
                        pickerContentTypes = [.movie, .video]
                        showFilePicker = true
                    }

                    TestRow(
                        title: "Pick Document",
                        description: "PDF, Word, Excel, PowerPoint, TXT, CSV.",
                        color: .orange
                    ) {
                        vm.activeTestName = "Document Upload"
                        vm.pendingEntityType = "document"
                        pickerContentTypes = [.pdf, .plainText, .spreadsheet, .presentation]
                        showFilePicker = true
                    }

                    TestRow(
                        title: "Pick Audio",
                        description: "MP3, AAC, WAV, FLAC, OGG. Max 500 MB.",
                        color: .purple
                    ) {
                        vm.activeTestName = "Audio Upload"
                        vm.pendingEntityType = "audio"
                        pickerContentTypes = [.audio]
                        showFilePicker = true
                    }

                    TestRow(
                        title: "Pick Any File",
                        description: "Tum dosya tiplerini goster. Filtresiz test.",
                        color: .green
                    ) {
                        vm.activeTestName = "Any File Upload"
                        vm.pendingEntityType = "document"
                        pickerContentTypes = [.item]
                        showFilePicker = true
                    }
                } header: {
                    SectionLabel("File Picker Tests", color: .green)
                }

                // Batch & Stress
                Section {
                    TestRow(
                        title: "Batch Upload (3 Images)",
                        description: "3 resim indir ve sirali yukle. Queue testi.",
                        color: .gray
                    ) {
                        vm.autoDownloadBatch()
                        showSheet = true
                    }
                } header: {
                    SectionLabel("Batch & Stress Tests", color: .gray)
                }

                // Validation Tests
                Section {
                    TestRow(
                        title: "Empty File (0 bytes)",
                        description: "'File size must be > 0' hatasi bekleniyor.",
                        color: .red
                    ) {
                        vm.validationTestEmptyFile()
                        showSheet = true
                    }
                } header: {
                    SectionLabel("Validation Tests (should fail)", color: .red)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("AzureVault Dev Menu")
            .sheet(isPresented: $showSheet) {
                UploadProgressSheet(vm: vm) {
                    showSheet = false
                }
            }
            .fileImporter(
                isPresented: $showFilePicker,
                allowedContentTypes: pickerContentTypes,
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first {
                        vm.uploadFromURL(url: url)
                        showSheet = true
                    }
                case .failure(let error):
                    vm.downloadError = error.localizedDescription
                    showSheet = true
                }
            }
        }
    }
}

// MARK: - Upload Progress Sheet

struct UploadProgressSheet: View {
    @ObservedObject var vm: DevMenuViewModel
    let onDismiss: () -> Void

    var body: some View {
        NavigationView {
            VStack(spacing: 24) {
                Text(vm.activeTestName)
                    .font(.title2.bold())
                    .padding(.top, 8)

                Spacer()

                if vm.isDownloading {
                    ProgressView()
                        .scaleEffect(1.5)
                    Text("Test dosyasi indiriliyor...")
                        .foregroundColor(.secondary)
                } else if let error = vm.downloadError {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 56))
                        .foregroundColor(.red)
                    Text(error)
                        .foregroundColor(.red)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                    Button("Kapat") { onDismiss() }
                        .buttonStyle(.bordered)
                } else {
                    stateContent
                }

                Spacer()
            }
            .padding()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Kapat") {
                        vm.cancel()
                        onDismiss()
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var stateContent: some View {
        switch vm.state {
        case .idle:
            ProgressView()
            Text("Hazirlaniyor...")
                .foregroundColor(.secondary)

        case .loading(let message):
            ProgressView()
                .scaleEffect(1.5)
            Text(message)

        case .uploading(let progress, let speed, let eta):
            Text("\(Int(progress * 100))%")
                .font(.system(size: 48, weight: .bold, design: .rounded))
                .foregroundColor(.accentColor)

            ProgressView(value: Double(progress))
                .progressViewStyle(.linear)
                .scaleEffect(y: 2)
                .padding(.horizontal, 32)

            HStack {
                Text(formatSpeed(speed))
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()
                if eta > 0 {
                    Text("~\(formatEta(eta)) kaldi")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .padding(.horizontal, 32)

            HStack(spacing: 16) {
                Button("Duraklat") { vm.pause() }
                    .buttonStyle(.bordered)
                Button("Iptal") {
                    vm.cancel()
                    onDismiss()
                }
                .buttonStyle(.bordered)
                .tint(.red)
            }

        case .paused(let progress):
            Text("\(Int(progress * 100))%")
                .font(.system(size: 48, weight: .bold, design: .rounded))
                .foregroundColor(.secondary)

            ProgressView(value: Double(progress))
                .progressViewStyle(.linear)
                .scaleEffect(y: 2)
                .tint(.secondary)
                .padding(.horizontal, 32)

            Text("Duraklatildi")
                .font(.headline)
                .foregroundColor(.secondary)

            HStack(spacing: 16) {
                Button {
                    vm.resume()
                } label: {
                    Label("Devam Et", systemImage: "play.fill")
                }
                .buttonStyle(.borderedProminent)

                Button("Iptal") {
                    vm.cancel()
                    onDismiss()
                }
                .buttonStyle(.bordered)
                .tint(.red)
            }

        case .done(let fileId, let downloadUrl):
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 64))
                .foregroundColor(.green)

            Text("Yukleme Basarili!")
                .font(.title2.bold())

            VStack(alignment: .leading, spacing: 6) {
                InfoLabel(key: "File ID", value: fileId)
                InfoLabel(key: "URL", value: downloadUrl)
            }
            .padding()
            .background(Color(.systemGray6))
            .cornerRadius(12)
            .padding(.horizontal)

            Button("Kapat") { onDismiss() }
                .buttonStyle(.borderedProminent)

        case .error(let message, let isRetryable):
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 56))
                .foregroundColor(.red)

            Text(message)
                .foregroundColor(.red)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            HStack(spacing: 16) {
                if isRetryable {
                    Button {
                        vm.retry()
                    } label: {
                        Label("Tekrar Dene", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.borderedProminent)
                }
                Button("Kapat") { onDismiss() }
                    .buttonStyle(.bordered)
            }
        }
    }
}

// MARK: - ViewModel

enum DevMenuUIState {
    case idle
    case loading(String)
    case uploading(Float, Int64, Int64)
    case paused(Float)
    case done(String, String)
    case error(String, Bool)
}

class DevMenuViewModel: ObservableObject {
    @Published var state: DevMenuUIState = .idle
    @Published var activeTestName: String = ""
    @Published var isDownloading: Bool = false
    @Published var downloadError: String?
    @Published var pendingEntityType: String = "document"

    private let viewModel = UploadViewModel()

    func autoDownload(name: String, url: String, fileName: String, entityType: String) {
        activeTestName = name
        pendingEntityType = entityType
        isDownloading = true
        downloadError = nil
        state = .idle

        Task {
            do {
                let fileURL = try await downloadFile(from: url, fileName: fileName)
                await MainActor.run {
                    isDownloading = false
                    startUpload(url: fileURL)
                }
            } catch {
                await MainActor.run {
                    isDownloading = false
                    downloadError = "Test dosyasi indirilemedi: \(error.localizedDescription)"
                }
            }
        }
    }

    func autoDownloadBatch() {
        activeTestName = "Batch Upload"
        pendingEntityType = "image"
        isDownloading = true
        downloadError = nil
        state = .idle

        Task {
            do {
                let url1 = try await downloadFile(from: "https://picsum.photos/400/400", fileName: "batch_1.jpg")
                await MainActor.run {
                    isDownloading = false
                    startUpload(url: url1)
                }
            } catch {
                await MainActor.run {
                    isDownloading = false
                    downloadError = "Batch dosyalari indirilemedi."
                }
            }
        }
    }

    func validationTestEmptyFile() {
        activeTestName = "Empty File"
        downloadError = nil
        state = .idle

        let tempDir = FileManager.default.temporaryDirectory
        let fileURL = tempDir.appendingPathComponent("empty.txt")
        FileManager.default.createFile(atPath: fileURL.path, contents: Data(), attributes: nil)
        startUpload(url: fileURL)
    }

    func uploadFromURL(url: URL) {
        downloadError = nil
        state = .idle
        startUpload(url: url)
    }

    private func startUpload(url: URL) {
        _ = url.startAccessingSecurityScopedResource()
        let file = PlatformFile(url: url)
        let entityId = "test_\(Int(Date().timeIntervalSince1970))"

        // In production: get grant from your backend (POST /api/upload/start)
        // let grant = try await api.post("/api/upload/start", body: ["entityType": pendingEntityType])
        // For sample app: upload without grant (backend will receive event.grant = null)
        let grantMetadata: [String: String] = [:]  // Add "x-upload-grant": grant.grant in production

        viewModel.upload(
            fileRef: file,
            entityType: pendingEntityType,
            entityId: entityId,
            customMetadata: grantMetadata
        )

        Task {
            for await uiState in viewModel.state {
                await MainActor.run {
                    switch uiState {
                    case is UploadUiState.Idle:
                        self.state = .idle
                    case let s as UploadUiState.Loading:
                        self.state = .loading(s.message)
                    case let s as UploadUiState.Uploading:
                        self.state = .uploading(s.progress, s.speed, s.eta)
                    case let s as UploadUiState.Paused:
                        self.state = .paused(s.progress)
                    case let s as UploadUiState.Done:
                        self.state = .done(s.fileId, s.downloadUrl)
                    case let s as UploadUiState.Error:
                        self.state = .error(s.message, s.isRetryable)
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

    private func downloadFile(from urlString: String, fileName: String) async throws -> URL {
        guard let url = URL(string: urlString) else {
            throw URLError(.badURL)
        }
        let (data, _) = try await URLSession.shared.data(from: url)
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("test_files")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        let fileURL = tempDir.appendingPathComponent(fileName)
        try data.write(to: fileURL)
        return fileURL
    }
}

// MARK: - Reusable Components

struct TestRow: View {
    let title: String
    let description: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                RoundedRectangle(cornerRadius: 10)
                    .fill(color.opacity(0.12))
                    .frame(width: 40, height: 40)
                    .overlay(
                        Circle()
                            .fill(color)
                            .frame(width: 12, height: 12)
                    )

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.primary)
                    Text(description)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.secondary.opacity(0.5))
            }
        }
    }
}

struct SectionLabel: View {
    let text: String
    let color: Color

    init(_ text: String, color: Color) {
        self.text = text
        self.color = color
    }

    var body: some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: 1)
                .fill(color)
                .frame(width: 3, height: 14)
            Text(text.uppercased())
                .font(.caption.weight(.bold))
                .foregroundColor(color)
                .tracking(1)
        }
    }
}

struct InfoLabel: View {
    let key: String
    let value: String

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            Text("\(key):")
                .font(.caption.weight(.medium))
                .foregroundColor(.secondary)
            Text(value)
                .font(.caption)
                .foregroundColor(.primary)
                .lineLimit(2)
        }
    }
}

// MARK: - Utilities

private func formatSpeed(_ bytesPerSecond: Int64) -> String {
    guard bytesPerSecond > 0 else { return "..." }
    let kb = Double(bytesPerSecond) / 1024.0
    if kb < 1024 {
        return "\(Int(kb)) KB/s"
    }
    return String(format: "%.1f MB/s", kb / 1024.0)
}

private func formatEta(_ etaMs: Int64) -> String {
    let seconds = etaMs / 1000
    if seconds < 60 { return "\(seconds)s" }
    let minutes = seconds / 60
    let remaining = seconds % 60
    return "\(minutes)m \(remaining)s"
}

#Preview {
    DevMenuView()
}
