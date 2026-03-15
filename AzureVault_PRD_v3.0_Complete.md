# AzureVault Upload Platform — Complete PRD & Implementation Guide

**Version:** 3.0.0 · **Date:** March 14, 2026 · **Status:** Draft · **Classification:** Confidential

---

## Table of Contents

### Part A — Product Requirements

1. [Executive Summary](#1-executive-summary)
2. [Scope & Constraints](#2-scope--constraints)
3. [Problem Definition](#3-problem-definition)
4. [System Architecture Overview](#4-system-architecture-overview)
5. [Azure Infrastructure Design](#5-azure-infrastructure-design)
6. [KMP SDK Design — Zero Platform Code](#6-kmp-sdk-design--zero-platform-code)
7. [Backend API Specification](#7-backend-api-specification)
8. [Server-Side File Processing Pipeline](#8-server-side-file-processing-pipeline)
9. [Security Architecture](#9-security-architecture)
10. [Monitoring, Observability & SLA](#10-monitoring-observability--sla)
11. [Multi-Application Management](#11-multi-application-management)
12. [Performance Requirements](#12-performance-requirements)
13. [Error Handling & Edge Cases](#13-error-handling--edge-cases)
14. [Testing Strategy](#14-testing-strategy)
15. [Cost Estimation](#15-cost-estimation)
16. [Implementation Roadmap](#16-implementation-roadmap)

### Part B — Technical Implementation

17. [Azure Infrastructure Provisioning (az cli)](#17-azure-infrastructure-provisioning)
18. [Development Environment & Prerequisites](#18-development-environment--prerequisites)
19. [Project Structure](#19-project-structure)
20. [Gradle Configuration](#20-gradle-configuration)
21. [Source Code — Public API (upload-api)](#21-source-code--public-api)
22. [Source Code — Internal Modules](#22-source-code--internal-modules)
23. [Source Code — Platform Modules](#23-source-code--platform-modules)
24. [Consumer Integration — Android](#24-consumer-integration--android)
25. [Consumer Integration — iOS](#25-consumer-integration--ios)
26. [Build, Test & Run](#26-build-test--run)
27. [SDK Distribution & Publishing](#27-sdk-distribution--publishing)
28. [Troubleshooting](#28-troubleshooting)

### Appendix

29. [Glossary](#29-glossary)
30. [Technology Stack](#30-technology-stack)
31. [Consumer Code Summary](#31-consumer-code-summary)
32. [Version History](#32-version-history)

---

# PART A — PRODUCT REQUIREMENTS

---

## 1. Executive Summary

AzureVault Upload Platform, birden fazla mobil uygulama tarafından kullanılacak, Azure altyapısı üzerine inşa edilmiş kurumsal düzeyde bir dosya yükleme SDK'sıdır. Sistem; resim, video, belge, sıkıştırılmış arşivler ve diğer tüm dosya türlerini destekler.

SDK'nın temel prensibi **"Zero Platform Code for Consumer"** — yani SDK'yı kullanan geliştirici, Android veya iOS fark etmeksizin aynı Kotlin kodunu yazar, hiçbir platform-specific implementasyon yapmaz. Tüm `expect/actual` declaration'lar SDK içinde `internal` visibility ile gizlidir.

> **Vizyon:** Tek bir SDK ve altyapı ile tüm mobil platformlarda (Android, iOS) tutarlı, güvenli, yüksek performanslı ve ölçeklenebilir dosya yükleme deneyimi sunmak. Geliştirici deneyimini (DX) en üst düzeye çıkarmak.

**Consumer'ın yazacağı tek platform-specific kod:**

- **SDK Initialization:** Android'de `Application.onCreate()`, iOS'te `AppDelegate` — 5 satır, bir kez yazılır.
- **Dosya Referansı:** Android'de `uri.toPlatformFile()`, iOS'te `PlatformFile(url:)` — tek satır wrapper.
- **Geri kalan her şey:** upload, pause, resume, cancel, progress, retry, batch — %100 shared Kotlin kodu.

**Upload Modeli:** Direct-to-Storage. Dosyalar backend üzerinden geçmez. Client, backend'den SAS token (signed URL) alır ve dosyayı doğrudan Azure Blob Storage'a yükler. Bu sayede backend'e dosya trafiği binmez, bandwidth maliyeti yarıya düşer ve Azure'un native throughput'undan faydalanılır.

---

## 2. Scope & Constraints

### 2.1 In Scope (v1.0)

- Android (API 24+) ve iOS (15.0+) platformları
- Kotlin Multiplatform SDK ile shared business logic
- SKIE ile Swift-native interop
- Azure Blob Storage'a direct upload (SAS token)
- Chunked upload, resume, retry, progress tracking
- Server-side processing pipeline (scan, BlurHash, transcode, on-demand image transform)
- Multi-app tenant isolation
- Birden fazla uygulama tarafından paylaşılan SDK

### 2.2 Out of Scope (v1.0 — gelecek sürümlerde planlanıyor)

- Web SDK (TypeScript) — Phase 2'de ayrı implementasyon olarak planlandı
- Client-side encryption (AES-256-GCM) — v1.1'de
- Real-time progress push via SignalR — v1.1'de
- Admin dashboard UI — v1.2'de
- On-premise deployment — değerlendirilecek

### 2.3 Key Constraints

- SDK minimum 24 API level (Android) ve iOS 15.0 desteklemelidir.
- Azure Blob Storage'ın tek bir blob için max 50.000 block limiti vardır (32MB chunk ile max ~1.5TB).
- iOS background URLSession, sistem tarafından yönetilir; SDK'nın garanti edebileceği upload süresi yoktur.
- SKIE, yalnızca iOS framework export'larında çalışır; Android tarafını etkilemez.

---

## 3. Problem Definition

### 3.1 Current Challenges

Mevcut ortamda birden fazla mobil uygulama farklı dosya yükleme implementasyonları kullanmaktadır:

- Her uygulama için tekrarlayan upload kodu yazılması (code duplication)
- Platform bazlı farklı davranışlar ve hata yönetimi tutarsızlıkları
- Büyük dosyalarda timeout, memory overflow ve kullanıcı deneyimi sorunları
- Güvenlik politikalarının merkezi olarak yönetilememesi
- Retry mekanizmalarının ve progress tracking özelliklerinin standart olmaması
- Dosya validasyon kurallarının uygulama bazında farklılık göstermesi

### 3.2 Business Impact

| Metric | Current State | Target State |
|--------|--------------|--------------|
| Upload Success Rate | %82 (network instability) | %99.5+ (auto-retry, resume) |
| Development Time (per app) | 4-6 hafta per platform | < 1 hafta (shared SDK, zero platform code) |
| Code Duplication | %70+ ortak upload logic | %0 — tüm upload kodu shared |
| Security Compliance | Inconsistent across apps | Centralized, auditable |
| Max File Size | 50 MB (timeout issues) | 5 GB+ (chunked upload) |
| Platform Parity | Feature gap between Android/iOS | 100% feature parity (aynı kod) |
| Platform-Specific Code (consumer) | Her platform için ayrı impl. | 5 satır init + 1 satır file ref |

---

## 4. System Architecture Overview

Sistem dört ana katmandan oluşur. Consumer açısından sadece en üst katman (Client SDK) görünürdür ve tamamen platform-agnostic'tir.

### 4.1 Architecture Layers

| Layer | Technology | Consumer Visibility |
|-------|-----------|-------------------|
| Client SDK (KMP) | Kotlin Multiplatform + SKIE | **Public API** — consumer bunu kullanır (shared code) |
| API Gateway | Azure API Management | Invisible — SDK internal |
| Upload Service | Azure Functions / AKS | Invisible — SDK internal |
| Storage Layer | Azure Blob Storage | Invisible — SDK internal |

### 4.2 Upload Data Flow (Direct-to-Storage)

Dosyalar backend üzerinden geçmez. Client, SAS token (signed URL) alarak dosyayı doğrudan Azure Blob Storage'a yükler:

1. Consumer, `uploader.upload(file, metadata)` çağrır (shared Kotlin kodu).
2. SDK, dosya boyutuna göre single-shot veya chunked upload stratejisi belirler.
3. SDK, API Gateway aracılığıyla Upload Service'e metadata gönderir ve SAS token alır.
4. SDK, dosyayı doğrudan Azure Blob Storage'a yükler (SAS token ile; bypass backend).
5. Chunked upload'ta her chunk bağımsız olarak yüklenir; başarısız chunk'lar otomatik retry edilir.
6. Tüm chunk'lar yüklendiğinde, SDK Upload Service'e commit isteği gönderir.
7. Upload Service, Block List'i commit eder, metadata kaydeder ve webhook tetikler.
8. Consumer, `Flow<UploadState>` üzerinden completion event'i alır.

### 4.3 Download Data Flow

Upload tamamlandıktan sonra dosyalara erişim:

1. Consumer, `uploader.getDownloadUrl(fileId)` çağrır.
2. SDK, API Gateway üzerinden Upload Service'e istek gönderir.
3. Upload Service, dosyanın erişim seviyesine göre yanıt döner:
   - **Private dosyalar:** Süreli SAS token ile signed download URL üretilir (default 1 saat).
   - **Public dosyalar:** CDN URL döner (Azure Front Door edge cache).
4. Consumer, dönen URL'i doğrudan kullanır (ImageView, WebView, vs.).

---

## 5. Azure Infrastructure Design

### 5.1 Azure Services Matrix

| Service | Purpose | Configuration |
|---------|---------|---------------|
| Azure Blob Storage | Primary file storage | Hot/Cool/Archive tiers, LRS/GRS replication, soft delete |
| Azure CDN (Front Door) | Global content delivery | POP edge caching, custom domain, SSL termination |
| Azure API Management | API Gateway & throttling | OAuth 2.0, rate limiting, request transformation |
| Azure Functions | Serverless upload orchestration | Consumption plan, Event Grid triggers, Durable Functions |
| Azure Cosmos DB | Upload metadata & state | Multi-region writes, TTL, change feed for events |
| Azure Event Grid | Event-driven notifications | Upload complete, virus scan, processing events |
| Azure Key Vault | Secrets management | SAS key rotation, managed identity |
| Azure Application Insights | Telemetry & monitoring | Custom metrics, distributed tracing |
| Azure SignalR Service | Real-time progress (v1.1) | Serverless mode, push notifications |
| Microsoft Defender for Storage | Malware scanning | Automatic blob scan, quarantine |

### 5.2 Storage Architecture

#### 5.2.1 Container Strategy

| Container | Purpose | Access | Lifecycle |
|-----------|---------|--------|-----------|
| `uploads-staging` | Geçici chunk depolama | Private (SAS) | 7 gün TTL, auto-delete |
| `uploads-{appId}` | Uygulama bazlı kalıcı dosyalar | Private (SAS) | Hot 30d → Cool 90d → Archive |
| `uploads-public` | CDN ile sunulacak dosyalar | Blob-level public | Hot tier, CDN edge cache |
| `uploads-quarantine` | Malware taraması bekleyen | Private (internal) | 24 saat TTL |
| `uploads-cache` | On-demand transform sonuçları | Private (CDN origin) | Cool tier, orijinal silinince cascade delete |

#### 5.2.2 Blob Naming Convention

```
{appId}/{entityType}/{entityId}/{year}/{month}/{uploadId}/{originalFileName}
```

**Örnek:** `myapp/user-avatar/usr_12345/2026/03/upl_abc123/profile.jpg`

### 5.3 SAS Token Strategy

| Token Type | Scope | Duration | Permissions |
|------------|-------|----------|-------------|
| Upload SAS | Specific blob path | 15 dakika | Write, Create |
| Download SAS | Specific blob path | 1 saat (configurable) | Read |
| Delegation SAS | User delegation key | Token süresine bağlı | Key Vault managed rotation |

> **Güvenlik Notu:** SAS token'lar IP kısıtlaması, HTTPS zorunluluğu ve minimum yetki prensibi ile oluşturulur. User Delegation SAS tercih edilir; account-level SAS sadece migration senaryolarında kullanılır.

---

## 6. KMP SDK Design — Zero Platform Code

### 6.1 Design Principles

| Principle | Description | Implementation |
|-----------|-------------|----------------|
| Zero Platform Code | Consumer sadece commonMain'de Kotlin yazar | Tüm expect/actual `internal` visibility |
| Single API Surface | Android ve iOS için aynı API | Shared interface, no platform variants |
| Invisible Infrastructure | Network, storage, retry görünmez | SDK içinde kapsüllenmiş |
| Swift-Native Experience | iOS'te Kotlin hissetmez | SKIE ile async/await, sealed class mapping |
| Minimal Touch Points | Platform kodu max 6 satır | Init (5 satır) + file ref (1 satır) |

### 6.2 SDK Internal Module Architecture

Consumer bu modülleri görmez; sadece `upload-api`'nin public API'sini kullanır.

| Module | Type | Visibility | Description |
|--------|------|------------|-------------|
| `upload-api` | Common (KMP) | **PUBLIC** | Consumer-facing API: AzureVaultUploader, UploadState, UploadMetadata, PlatformFile |
| `upload-core` | Common (KMP) | INTERNAL | Upload engine: chunking, retry, queue, state machine, bandwidth estimator |
| `upload-domain` | Common (KMP) | INTERNAL | Validation rules, chunk strategy selection, error classification, use cases |
| `upload-network` | Common (KMP) | INTERNAL | Ktor HTTP client, SAS token lifecycle, Azure Blob API communication |
| `upload-storage` | Common (KMP) | INTERNAL | SQLDelight persistence for upload state, chunk progress, resume data |
| `upload-crypto` | Common (KMP) | INTERNAL | MD5/SHA256 streaming hash calculation, integrity verification |
| `upload-platform-android` | Android | INTERNAL | WorkManager, ForegroundService, ContentResolver, ConnectivityManager |
| `upload-platform-ios` | iOS | INTERNAL | URLSession background, NWPathMonitor, UNNotification, NSFileManager |

> **Kritik Tasarım Kararı:** `upload-api` modülü dışındaki TÜM modüller `internal` visibility kullanır. Consumer, `upload-core` veya `upload-platform-android`'daki hiçbir sınıfı import edemez.

### 6.3 Platform Touch Points (Consumer'ın Yazacağı Tek Platform Kodu)

#### 6.3.1 SDK Initialization (Bir Kez Yazılır)

**Android — Application.onCreate():**

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AzureVaultUpload.initialize(
            context = this,
            config = UploadConfig(
                baseUrl = "https://api.company.com/v1",
                appId = "com.company.app1",
                authProvider = { tokenStore.getAccessToken() }
            )
        )
    }
}
```

**iOS — AppDelegate:**

```swift
func application(_ application: UIApplication,
    didFinishLaunchingWithOptions ...) -> Bool {
    AzureVaultUpload.shared.initialize(
        config: UploadConfig(
            baseUrl: "https://api.company.com/v1",
            appId: "com.company.app1",
            authProvider: { TokenStore.shared.getAccessToken() }
        )
    )
    return true
}
```

> Bu noktadan sonra hiçbir platform kodu yazılmaz.

#### 6.3.2 PlatformFile Abstraction

| Platform | File Reference | Conversion |
|----------|---------------|------------|
| Android | Uri (ContentResolver) | `val file = uri.toPlatformFile(contentResolver)` |
| iOS | URL (NSFileManager) | `let file = PlatformFile(url: pickerURL)` |

### 6.4 Public API Methods

Tüm metodlar `commonMain`'de çağrılır, platform farkı yoktur.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `upload(file, metadata)` | `Flow<UploadState>` | Dosya yüklemeyi başlatır, state Flow olarak döner |
| `uploadBatch(files, metadata)` | `Flow<BatchUploadState>` | Birden fazla dosyayı sıralı/paralel yükler |
| `pause(uploadId)` | `Boolean` | Aktif upload'u duraklatır |
| `resume(uploadId)` | `Flow<UploadState>` | Duraklatılmış upload'u devam ettirir |
| `cancel(uploadId)` | `Boolean` | Upload'u iptal eder, staging temizler |
| `getProgress(uploadId)` | `StateFlow<UploadProgress>` | Real-time progress (bytes, %, speed, ETA) |
| `getPendingUploads()` | `List<UploadInfo>` | Tamamlanmamış upload listesi |
| `retryFailed(uploadId)` | `Flow<UploadState>` | Başarısız upload'u tekrar dener |
| `getDownloadUrl(fileId)` | `suspend String` | Signed download URL döner (orijinal dosya) |
| `getImageUrl(fileId, ...)` | `String` | CDN URL construct eder (network isteği yok, deterministic) |
| `getBlurHash(fileId)` | `String?` | BlurHash string (local cache, network isteği yok) |

### 6.5 Shared ViewModel Pattern

Aşağıdaki ViewModel, Android ve iOS'te değişmeden çalışır:

```kotlin
// commonMain/UploadViewModel.kt — %100 shared, sıfır platform kodu
class UploadViewModel : ViewModel() {

    private val uploader = AzureVaultUpload.uploader()
    private val _state = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val state: StateFlow<UploadUiState> = _state.asStateFlow()

    fun upload(fileRef: PlatformFile, entityType: String, entityId: String) {
        viewModelScope.launch {
            uploader.upload(
                file = fileRef,
                metadata = UploadMetadata(entityType = entityType, entityId = entityId)
            ).collect { uploadState ->
                _state.value = when (uploadState) {
                    is UploadState.Validating -> UploadUiState.Loading("Dosya kontrol ediliyor...")
                    is UploadState.RequestingToken -> UploadUiState.Loading("Bağlantı kuruluyor...")
                    is UploadState.Uploading -> UploadUiState.Uploading(
                        progress = uploadState.progress,
                        speed = uploadState.bytesPerSecond,
                        eta = uploadState.estimatedTimeRemaining
                    )
                    is UploadState.Paused -> UploadUiState.Paused(uploadState.progress)
                    is UploadState.Committing -> UploadUiState.Loading("Tamamlanıyor...")
                    is UploadState.Processing -> UploadUiState.Loading("İşleniyor: ${uploadState.step}")
                    is UploadState.Completed -> UploadUiState.Done(uploadState.downloadUrl, uploadState.fileId)
                    is UploadState.Failed -> UploadUiState.Error(uploadState.error.userMessage, uploadState.isRetryable)
                    is UploadState.Cancelled -> UploadUiState.Idle
                    else -> _state.value
                }
            }
        }
    }

    fun pause(id: String) = uploader.pause(id)
    fun resume(id: String) { viewModelScope.launch { uploader.resume(id).collect {} } }
    fun cancel(id: String) = uploader.cancel(id)
}

sealed class UploadUiState {
    data object Idle : UploadUiState()
    data class Loading(val message: String) : UploadUiState()
    data class Uploading(val progress: Float, val speed: Long, val eta: Long) : UploadUiState()
    data class Paused(val progress: Float) : UploadUiState()
    data class Done(val downloadUrl: String, val fileId: String) : UploadUiState()
    data class Error(val message: String, val isRetryable: Boolean) : UploadUiState()
}
```

### 6.6 SKIE Integration (Swift-Native Experience)

| Kotlin Construct | Without SKIE (Pain) | With SKIE (Native) |
|-----------------|--------------------|--------------------|
| `Flow<UploadState>` | KotlinFlow + FlowCollector callback hell | `AsyncSequence` — `for await state in ...` |
| `sealed class UploadState` | Opaque KotlinBase, manual casting | Native Swift enum with associated values |
| `suspend fun` | KotlinSuspendFunction wrapper | Swift `async func`, `try await` |
| `StateFlow<T>` | Manual observation setup | `@Published` property, SwiftUI compatible |
| coroutines cancellation | Manual CancellationToken | Swift Task cancellation bridge |

### 6.7 Upload State Machine

| State | Description | Transitions |
|-------|-------------|-------------|
| `IDLE` | Upload henüz başlatılmadı | → VALIDATING |
| `VALIDATING` | Dosya boyutu, tipi, hash kontrolleri | → REQUESTING_TOKEN · FAILED |
| `REQUESTING_TOKEN` | Backend'den SAS token alınıyor | → UPLOADING · FAILED |
| `UPLOADING` | Chunk'lar Azure'a yükleniyor | → COMMITTING · PAUSED · FAILED |
| `PAUSED` | Kullanıcı veya network interrupt | → UPLOADING · CANCELLED |
| `COMMITTING` | Block list commit | → PROCESSING · FAILED |
| `PROCESSING` | Server-side (scan, thumbnail) | → COMPLETED · FAILED |
| `COMPLETED` | Upload başarılı | Terminal state |
| `FAILED` | Retry limit aşıldı | → UPLOADING (manual retry) |
| `CANCELLED` | Kullanıcı iptal etti | Terminal state |

### 6.8 Chunking Strategy

| File Size | Strategy | Chunk Size | Concurrency |
|-----------|----------|------------|-------------|
| 0 – 4 MB | Single-shot upload | N/A (whole file) | 1 |
| 4 MB – 256 MB | Block upload | 4 MB | 4 parallel |
| 256 MB – 1 GB | Block upload | 8 MB | 6 parallel |
| 1 GB – 5 GB | Block upload | 16 MB | 8 parallel |
| 5 GB+ | Block upload + resume | 32 MB | 8 parallel |

### 6.9 Retry & Resilience

| Scenario | Max Retries | Backoff | Notes |
|----------|-------------|---------|-------|
| Chunk failure | 5 | Exponential (1s, 2s, 4s, 8s, 16s) | Sadece başarısız chunk retry |
| SAS token expired | 3 | Immediate + new token | Yeni token alınıp devam |
| Network disconnect | ∞ | Connection restore event | Auto-resume on reconnect |
| Server 5xx | 3 | Exponential (2s, 4s, 8s) | Circuit breaker after 3 |
| Rate limited (429) | 5 | Retry-After header | Queue throttling |

### 6.10 Internal expect/actual (Consumer Görmez)

| Internal expect | Android actual | iOS actual |
|----------------|---------------|------------|
| `BackgroundUploader` | WorkManager + ForegroundService | URLSession background config |
| `FileReader` | ContentResolver + Cursor | NSFileManager + NSData |
| `HashCalculator` | java.security.MessageDigest | CommonCrypto (CC_SHA256) |
| `NetworkMonitor` | ConnectivityManager callback | NWPathMonitor |
| `NotificationBridge` | NotificationCompat.Builder | UNUserNotificationCenter |
| `SecureStorage` | EncryptedSharedPreferences | Keychain Services |
| `ImageCompressor` | Bitmap + JPEG compress | UIImage + jpegData |
| `PlatformInitializer` | Context-based init | NSObject-based init |

---

## 7. Backend API Specification

### 7.1 Authentication

Tüm API istekleri `Authorization: Bearer <JWT>` header'ı gerektirir. JWT, Azure AD B2C veya custom identity provider tarafından üretilir. SDK, consumer'ın `UploadConfig.authProvider` lambda'sı üzerinden token'ı alır.

### 7.2 Endpoint Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/v1/uploads/initiate` | Upload başlatır, SAS token ve uploadId döner |
| `POST` | `/v1/uploads/{id}/complete` | Chunked upload'u commit eder |
| `DELETE` | `/v1/uploads/{id}` | Upload'u iptal eder, staging temizler |
| `GET` | `/v1/uploads/{id}/status` | Upload durumunu sorgular |
| `GET` | `/v1/uploads/{id}/download-url` | Signed download URL döner (orijinal dosya) |
| `POST` | `/v1/uploads/batch/initiate` | Toplu upload başlatır |
| `GET` | `/v1/uploads/list` | Upload geçmişi (paginated) |
| `POST` | `/v1/uploads/{id}/webhook` | Webhook konfigürasyonu |

### 7.3 Initiate Upload — Request

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `fileName` | string | Yes | Orijinal dosya adı |
| `fileSize` | long | Yes | Dosya boyutu (bytes) |
| `mimeType` | string | Yes | MIME tipi (image/jpeg, application/pdf, vb.) |
| `md5Hash` | string | No | Client-side MD5 hash (integrity check) |
| `entityType` | string | Yes | İlişkili entity tipi (user-avatar, document, vb.) |
| `entityId` | string | Yes | İlişkili entity ID |
| `metadata` | Map\<String,String\> | No | Custom key-value metadata |
| `isPublic` | boolean | No | CDN ile public erişim (default: false) |

### 7.4 Initiate Upload — Response

| Field | Type | Description |
|-------|------|-------------|
| `uploadId` | string | Unique upload identifier |
| `blobUrl` | string | Azure Blob Storage hedef URL |
| `sasToken` | string | Signed access token (15 dk TTL) |
| `strategy` | enum | `SINGLE_SHOT` veya `CHUNKED` (server-side onay) |
| `maxBlockSize` | long | İzin verilen max chunk boyutu (bytes) |
| `expiresAt` | ISO 8601 | SAS token son kullanma tarihi |

### 7.5 Complete Upload — Request

| Field | Type | Description |
|-------|------|-------------|
| `uploadId` | string | Upload identifier |
| `blockIds` | List\<String\> | Yüklenen block ID listesi (sıralı) |
| `md5Hash` | string | Tüm dosyanın MD5 hash'i (opsiyonel, integrity check) |

### 7.6 Complete Upload — Response

| Field | Type | Description |
|-------|------|-------------|
| `fileId` | string | Kalıcı dosya identifier |
| `downloadUrl` | string | İlk erişim URL'i (SAS veya CDN) |
| `metadata` | Map | Server-side eklenen metadata (dimensions, duration, vb.) |
| `processingStatus` | enum | `PENDING`, `IN_PROGRESS`, `COMPLETED` |
| `blurHash` | string? | BlurHash string (görseller için, processing tamamlandıysa) |

### 7.7 Download URL — Response

| Field | Type | Description |
|-------|------|-------------|
| `downloadUrl` | string | Signed URL (private) veya CDN URL (public) |
| `expiresAt` | ISO 8601 | URL geçerlilik süresi |
| `contentType` | string | Dosyanın MIME tipi |
| `fileSize` | long | Dosya boyutu |
| `blurHash` | string? | BlurHash placeholder string (görseller için) |

### 7.8 Image Transform — CDN Origin Architecture

Görsel dönüşümü için ayrı bir SDK-facing API endpoint yoktur. SDK, CDN URL'ini deterministic olarak construct eder, istek doğrudan CDN'e gider:

```
SDK construct eder:
  https://img.company.com/{fileId}?w=200&h=200&fit=cover&q=85&f=webp

CDN miss olduğunda CDN, origin olarak Azure Function'ı çağırır:
  Function: transform cache'de var mı? → varsa döner
            yoksa orijinalden üretir, cache'e yazar, döner
```

**SDK asla backend API çağırmaz** — URL deterministic olduğu için SDK kendisi üretir. Bu sayede ekstra bir network round trip yok.

**CDN Origin Handler (Azure Function) — İç Mimari:**

| Adım | İşlem | Koşul |
|------|-------|-------|
| 1 | URL parametrelerini parse et (w, h, fit, q, f) | Her istek |
| 2 | Güvenlik: max 2048×2048, allowlist format kontrolü | Her istek |
| 3 | Transform cache blob'u kontrol et | Her istek |
| 4a | Cache HIT → Blob'dan oku, response döner (20ms) | Çoğu istek |
| 4b | Cache MISS → Orijinali oku, Sharp transform, cache'e yaz, response döner (80ms) | Sadece ilk kez |
| 5 | `Cache-Control: public, max-age=604800` header ile döner | Her istek |
| 6 | CDN bu header'a bakarak response'u edge'e otomatik yazar | CDN tarafı |

---

## 8. Server-Side File Processing Pipeline

### 8.1 Processing Steps

| Step | Service | Trigger | Output |
|------|---------|---------|--------|
| 1. Malware Scan | Microsoft Defender for Storage | Blob created event | Clean / Quarantine tagging |
| 2. Metadata Extract | Azure Function | Scan complete event | EXIF, dimensions, duration, page count |
| 3. BlurHash Gen | Azure Function | Image detected | 20-30 byte BlurHash string → Cosmos DB metadata |
| 4. Video Transcode | Azure Media Services | Video file detected | Adaptive bitrate HLS/DASH streams |
| 5. Doc Preview | Azure Function + LibreOffice | Document file detected | PDF preview, first-page thumbnail |
| 6. CDN Propagation | Azure Front Door | Processing complete | Edge cache warm-up for public files |
| 7. Webhook Dispatch | Azure Event Grid | All steps complete | Notify client application(s) |
| 8. Audit Log | Azure Cosmos DB | All events | Immutable upload lifecycle record |

### 8.2 Image Optimization — On-Demand Transform + Transform Cache + CDN

Upload sırasında hiçbir thumbnail veya varyasyon üretilmez. Tek üretilen şey **BlurHash** (20 byte metadata). Tüm görsel dönüşümleri runtime'da on-demand yapılır.

Sistem **iki ayrı cache katmanı** kullanır. Her biri farklı bir problemi çözer:

**Transform Cache (Blob Storage):** "Bu 40x40 daha önce üretildi mi?" sorusunu çözer. Amacı **hesaplama tekrarını önlemek** — orijinal 5MB dosyadan aynı dönüşümü ikinci kez yapmamak. Kalıcıdır, expire olmaz, orijinal silinene kadar durur.

**CDN (Azure Front Door):** "Bu 40x40'ı kullanıcıya en yakın yerden servisle" sorusunu çözer. Amacı **mesafe tekrarını önlemek**. Blob Storage tek bir Azure region'da durur (ör. West Europe, Hollanda). İstanbul'daki kullanıcı her istek'te Hollanda'ya gitmek yerine, CDN sayesinde İstanbul edge node'undan servis alır.

Neden ikisi de lazım — somut senaryo:

```
Blob Storage lokasyonu: West Europe (Hollanda)
Kullanıcı lokasyonu:    İstanbul

CDN OLMADAN (sadece transform cache):
  İstanbul → Hollanda (Blob oku) → İstanbul
  Her istek, her kullanıcı: ~120ms + egress $0.087/GB

CDN İLE:
  İlk istek:  İstanbul → Hollanda (transform cache) → İstanbul → CDN edge'e yaz
              ~120ms (bir kez)
  Sonraki:    İstanbul → İstanbul edge → döner
              ~5ms, Hollanda'ya hiç gitmez, egress $0.065/GB

1000 kullanıcı aynı avatarı görüyorsa:
  CDN olmadan:  1000 × Hollanda round trip = 1000 × ~120ms
  CDN ile:      1 × Hollanda + 999 × İstanbul edge = 1 × 120ms + 999 × 5ms
```

#### 8.2.1 Tam Akış — 3 Katmanlı Cache

Orijinal dosyadan transform işlemi **tüm sistemin ömrü boyunca sadece 1 kez** yapılır:

```
İstek: https://img.company.com/{fileId}?w=40&h=40&fit=cover&q=80&f=webp

┌─────────────────────────────────────────────────────────┐
│ Katman 1 — CDN Edge (coğrafi yakınlık)                  │
│ Soru: "İstanbul edge'de var mı?"                        │
│                                                         │
│ HIT  → Client'a döner (5ms) ✅                          │
│ MISS → Katman 2'ye git ↓                                │
│        (TTL dolmuş veya ilk kez bu edge'de isteniyor)   │
└─────────────────────────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│ Katman 2 — Transform Cache Blob (hesaplama tekrarı)     │
│ Soru: "Bu boyut daha önce üretildi mi?"                 │
│ Bak:  uploads-cache/{fileId}/w40_h40_cover_q80.webp     │
│                                                         │
│ HIT  → Blob'dan oku (1.2KB) → Client'a döner (20ms) ✅ │
│        CDN otomatik olarak bu response'u edge'e yazar   │
│        (sonraki istekler tekrar 5ms)                    │
│ MISS → Katman 3'e git ↓                                 │
│        (bu boyut hiç istenmemiş, ilk kez)               │
└─────────────────────────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│ Katman 3 — Origin Transform (hesaplama)                 │
│ Soru: "5MB orijinalden 40x40 üret"                      │
│                                                         │
│ → Orijinali Blob'dan oku (5MB)                          │
│ → Sharp ile transform (40x40, WebP, q80)                │
│ → Sonuç 1.2KB:                                          │
│   a) uploads-cache/{fileId}/w40_h40_cover_q80.webp'e    │
│      yaz (kalıcı, bir daha orijinale dönülmez)          │
│   b) Client'a döner (80ms) ✅                           │
│   c) CDN otomatik olarak edge'e yazar                   │
│                                                         │
│ ⚠️ Bu adım bir daha asla çalışmaz — aynı boyut için    │
│    tüm ömür boyunca tek seferlik.                       │
└─────────────────────────────────────────────────────────┘
```

**CDN expire olduğunda ne olur?**

CDN TTL dolduğunda edge'deki kopya silinir. Ama bu bir sorun değil — sonraki istek geldiğinde CDN, origin'e (Azure Function) yönlendirir. Function, transform cache blob'da hazır dosyayı bulur (Katman 2 HIT), 20ms'de döner. CDN bu response'u otomatik olarak tekrar edge'e yazar. Manuel işlem yok, CDN kendini yeniler:

```
Gün 1:   İlk istek → Katman 3 (origin transform, 80ms) → CDN + transform cache yazıldı
Gün 2-7: Tüm istekler → Katman 1 CDN HIT (5ms)
Gün 8:   CDN TTL doldu, edge'den silindi
Gün 8:   Sonraki istek → CDN MISS → Katman 2 transform cache HIT (20ms)
          → CDN otomatik olarak edge'e tekrar yazdı
Gün 9-14: Tüm istekler → Katman 1 CDN HIT (5ms)
...       Bu döngü sonsuza kadar devam eder
          Katman 3 (origin transform) bir daha asla çalışmaz
```

**Maliyet karşılaştırması:**

| Metrik | Sadece Transform Cache (CDN yok) | Transform Cache + CDN |
|--------|--------------------------------|----------------------|
| Latency (aynı region) | ~20ms | ~5ms (CDN HIT) |
| Latency (farklı region) | ~120ms (cross-region) | ~5ms (edge node) |
| Egress maliyeti | $0.087/GB (Blob) | $0.065/GB (CDN, %25 ucuz) |
| 1000 kişi aynı avatar | 1000 × Blob oku | 1 × Blob oku + 999 × edge |
| Global kullanıcılar | Her istek origin region'a gider | En yakın edge'den servis |

#### 8.2.2 Transform Cache — Blob Storage (Kalıcı)

Transform sonuçları `uploads-cache` container'ında **kalıcı** olarak saklanır. Expire olmaz, TTL yok. Tek silinme koşulu: orijinal dosya silindiğinde cascade delete.

**Naming Convention (deterministic):**

```
uploads-cache/{fileId}/w{width}_h{height}_{fit}_q{quality}.{format}

Örnekler:
uploads-cache/file_abc123/w40_h40_cover_q80.webp       → 1.2 KB
uploads-cache/file_abc123/w200_h200_cover_q85.webp      → 12 KB
uploads-cache/file_abc123/w1080_h0_contain_q85.webp     → 180 KB
```

**Lifecycle:** Orijinal dosya silindiğinde, `uploads-cache/{fileId}/` prefix'i altındaki tüm transform blob'lar cascade olarak silinir. Azure Event Grid'in blob deleted event'i ile tetiklenir.

**Storage Tier:** Cool tier. Sık erişilen transform'lar CDN'den servis edilir, Blob'a nadiren düşülür. Cool tier maliyeti Hot'un ~%40'ı.

#### 8.2.3 CDN — Azure Front Door (Geçici, Otomatik Yenilenen)

CDN'in rolü sadece coğrafi dağıtım. Transform cache'deki dosyaları dünya genelindeki edge node'lara kopyalar.

| Senaryo | Cache TTL | Purge Policy |
|---------|----------|-------------|
| Transform edilmiş görseller | 7 gün | Orijinal silindiğinde purge |
| Public dosyalar (orijinal) | 7 gün | Manuel purge endpoint |
| Private dosyalar | Cache yok | Her istek SAS token doğrulaması |

**Neden 7 gün?** CDN cache geçici, transform cache kalıcı. CDN expire olduğunda yeni istek gelirse transform cache'den 20ms'de çekilir ve CDN kendini otomatik yeniler. Uzun TTL'e gerek yok — popüler görseller zaten sürekli hit aldığı için CDN sıcak tutar, nadir görseller expire olur ama transform cache'de bekler.

**CDN otomatik yenilenme mekanizması:** CDN miss olduğunda request origin'e (Azure Function) yönlenir. Function, `Cache-Control: public, max-age=604800` header'ı ile response döner. CDN bu header'a bakarak response'u otomatik olarak edge'e yazar. Manuel purge veya warm-up gerekmez.

#### 8.2.4 Transformation Parametreleri

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `w` | int | original | Hedef genişlik (px) |
| `h` | int | original | Hedef yükseklik (px) |
| `fit` | enum | cover | `cover` (crop to fill), `contain` (fit inside), `scale-down` (sadece küçült) |
| `q` | int | 80 | Kalite (1-100) |
| `f` | enum | auto | `auto` (client capability), `webp`, `avif`, `jpeg`, `png` |

**Güvenlik:** Max boyut 2048×2048 px. Üstü reject edilir. Opsiyonel signed URL ile parametreler imzalanabilir.

#### 8.2.5 BlurHash

BlurHash, bir görselin renk dağılımını 20-30 byte'lık bir string'e encode eder. Client tarafında ~1ms'de decode edilip bulanık bir renkli placeholder olarak gösterilir. Gri kutu yerine görselin renklerini yansıtan bir önizleme sağlar.

**Üretim:** Upload pipeline'ında (Step 3) Sharp ile orijinal görsel 32×32'ye küçültülür, `blurhash-encode` ile hash üretilir, Cosmos DB'deki upload metadata'sına `blurHash` field olarak yazılır. Maliyet: ~5ms, ihmal edilebilir.

**Tüketim:** SDK, upload metadata'sı ile birlikte BlurHash string'ini local cache'e alır. Consumer `getBlurHash(fileId)` çağırdığında network isteği yapılmaz — anında döner.

```
UX Timeline:
0ms     → BlurHash decode → renkli bulanık placeholder (20 byte, local)
~5ms    → CDN edge cache hit → keskin görsel (en yaygın senaryo)
~20ms   → CDN miss → transform cache hit → Blob'dan hazır transform
          (CDN kendini otomatik yeniler, sonraki istekler tekrar 5ms)
~80ms   → Her ikisi de miss → orijinalden transform (sadece 1 kez olur)
          (transform cache'e + CDN'e yazılır, bir daha tekrarlanmaz)
```

#### 8.2.6 Neden Pre-Generation Yok?

| Kriter | Pre-Gen Varyasyonlar | On-Demand + Transform Cache + CDN |
|--------|---------------------|----------------------------------|
| Upload processing süresi | +2-3 saniye (thumbnail generation) | +5ms (sadece BlurHash) |
| Ekstra storage | N varyasyon × dosya sayısı (Hot tier) | Sadece istenen boyutlar (Cool tier) |
| Orijinalden transform | Her upload'ta N kez | Tüm ömür boyunca her boyut için 1 kez |
| Tasarım değişikliği | Migration + backfill gerekir | URL parametresini değiştir, biter |
| Yeni boyut ekleme | Backend deploy | Sadece client URL'i değiştir |
| İlk istek latency | 0ms | ~80ms (bir kez, sonra cache'den 5-20ms) |
| Esneklik | N sabit boyut | Sonsuz kombinasyon |
| Bakım karmaşıklığı | Thumbnail cleanup, orphan detection | Cascade delete (orijinal silinince) |

İlk istek'teki ~80ms fark, BlurHash placeholder sayesinde kullanıcı tarafından hissedilmez.

### 8.3 Supported File Types & Limits

| Category | Extensions | Max Size | Processing |
|----------|-----------|----------|------------|
| Images | jpg, png, gif, webp, heic, svg, bmp, tiff | 50 MB | BlurHash, EXIF strip, on-demand resize/format via CDN |
| Videos | mp4, mov, avi, mkv, webm | 5 GB | Transcode, thumbnail, duration extract |
| Documents | pdf, docx, xlsx, pptx, txt, csv | 500 MB | Preview, page count, text extract |
| Archives | zip, rar, 7z, tar.gz | 2 GB | Virus scan, content listing (no extract) |
| Audio | mp3, wav, aac, flac, ogg | 500 MB | Duration, waveform, metadata extract |
| Other | Any (configurable allowlist per app) | 1 GB (default) | Virus scan only |

---

## 9. Security Architecture

### 9.1 Security Layers

| Layer | Mechanism | Details |
|-------|-----------|---------|
| Authentication | OAuth 2.0 + JWT | Azure AD B2C / MSAL, per-app client credentials |
| Authorization | RBAC + ABAC | App-level izolasyon, entity-level erişim kontrolü |
| Transport | TLS 1.3 mandatory | Certificate pinning (mobile), HSTS |
| Storage Encryption | AES-256 at rest | SSE, customer-managed keys opsiyonel |
| Token Security | SAS token short-lived | 15 dk TTL, IP restriction, HTTPS only |
| Input Validation | Multi-layer | Client SDK + API Gateway + Upload Service'te üç katmanlı validasyon |
| Malware Protection | Microsoft Defender | Otomatik blob scan, quarantine, event notification |
| Audit Trail | Immutable logging | Cosmos DB change feed, Azure Monitor, tamper-proof |
| Data Residency | Region-locked storage | GDPR/KVKK uyumlu, data sovereignty compliance |

### 9.2 KVKK & GDPR Compliance

- Kişisel veri içeren dosyalar için data residency garanti edilir; veriler belirtilen Azure Region dışına çıkmaz.
- Kullanıcı silme talebi (right to be forgotten) alındığında, ilgili tüm blob'lar ve metadata'lar cascade olarak silinir.
- Soft-delete süresi KVKK gereksinimlerine göre ayarlanır (default: 30 gün, configurable).
- Upload metadata'sında kişisel veri barındırılmaz; entity referansları kullanılır.
- Audit log'lar, yasal saklama sürelerine uygun olarak retention policy ile yönetilir.

---

## 10. Monitoring, Observability & SLA

### 10.1 Key Metrics

| Metric | Target | Alert Threshold |
|--------|--------|----------------|
| Upload Success Rate | 99.5%+ | < 98% (P1 alert) |
| P95 Upload Latency (<10MB) | < 2 seconds | > 5 seconds |
| P95 Upload Latency (10-100MB) | < 15 seconds | > 30 seconds |
| Chunk Retry Rate | < 2% | > 5% |
| SAS Token Generation P99 | < 200ms | > 500ms |
| API Error Rate (5xx) | < 0.1% | > 0.5% |
| Malware Detection Rate | 100% scanned | Any unscanned blob |
| Storage Throughput | Auto-scaling | > 80% capacity |

### 10.2 Distributed Tracing

Her upload işlemi, unique bir `X-Correlation-Id` taşır. Bu ID, client SDK'dan başlayarak API Gateway, Upload Service, Event Grid ve processing pipeline boyunca tüm log'lara eklenir. Application Insights üzerinden tek bir upload'un tüm yaşam döngüsü end-to-end görüntülenebilir.

---

## 11. Multi-Application Management

### 11.1 App Registration & Configuration

| Configuration | Scope | Example |
|--------------|-------|---------|
| `appId` | Unique identifier | `com.company.app1` |
| `allowedMimeTypes` | Per-app allowlist | `["image/*", "application/pdf"]` |
| `maxFileSize` | Per-app limit | 100 MB (app1), 2 GB (app2) |
| `maxConcurrentUploads` | Per-user throttle | 3 (default), configurable per app |
| `storageQuota` | Per-app total storage | 500 GB |
| `webhookUrl` | Completion notification | `https://app1.company.com/hooks/upload` |
| `processingPipeline` | Enabled processors | `["scan", "blurhash", "preview"]` |
| `retentionPolicy` | Lifecycle rules | Hot:30d, Cool:90d, Archive:365d, Delete:730d |
| `rateLimits` | API throttling | 1000 req/min per user, 10000 req/min per app |

### 11.2 Tenant Isolation

- **Storage Level:** Her appId için ayrı blob container (`uploads-{appId}`), cross-container erişim engellenir.
- **API Level:** API Management policy'leri ile appId bazlı rate limiting ve endpoint erişim kontrolü.
- **Network Level:** Virtual Network service endpoints ile storage erişimi kısıtlanır.
- **Data Level:** Cosmos DB partition key olarak appId kullanılır; cross-partition query engellenir.
- **Monitoring Level:** Application Insights custom dimensions ile app bazlı metrik izolasyonu.

---

## 12. Performance Requirements

| Scenario | Target | Optimization Strategy |
|----------|--------|----------------------|
| Cold start (first upload) | < 3s to first byte | Pre-warmed Azure Functions, connection pooling |
| Small file (<1MB) | < 1 second total | Single-shot, skip chunking overhead |
| Medium file (10MB) | < 5 seconds on 4G | 4 parallel chunks, adaptive chunk sizing |
| Large file (100MB) | < 45 seconds on WiFi | 8 parallel chunks, streaming hash |
| Image thumbnail (client) | < 500ms | BitmapFactory/vImage/Canvas, progressive JPEG |
| Batch upload (10 files) | < 30% overhead vs sequential | Concurrent queue, shared connection pool |
| Resume after app kill | < 2 seconds to resume | SQLDelight persisted state, chunk bitmap |

**Client-Side Optimizations:**

- **Adaptive Chunk Size:** Network hızına göre chunk boyutu dinamik olarak ayarlanır.
- **Streaming Hash:** Dosya chunk'lanırken eş zamanlı MD5/SHA256 hesaplanır (ek file read yok).
- **Connection Pooling:** HTTP/2 multiplexing ile tek TCP bağlantısı üzerinden paralel chunk upload.
- **Memory Mapping:** Büyük dosyalar için memory-mapped I/O; tüm dosyayı RAM'e yükleme yok.
- **Bandwidth Estimation:** İlk chunk'ın upload süresine göre toplam süre tahmini hesaplanır.
- **Queue Prioritization:** Kullanıcı etkileşimindeki upload'lar background sync'ten önce işlenir.

---

## 13. Error Handling & Edge Cases

### 13.1 Error Classification

| Category | Examples | SDK Behavior | User Experience |
|----------|---------|-------------|-----------------|
| Transient Network | Timeout, DNS failure, connection reset | Auto-retry with backoff | Progress pauses, auto-resumes |
| Auth Failure | Expired JWT, invalid SAS token | Refresh token via authProvider, retry | Re-login prompt if refresh fails |
| Validation | File too large, unsupported type | Immediate fail, no retry | Clear error message, actionable |
| Server Error (5xx) | Internal error, service unavailable | Retry with backoff (max 3) | Generic retry prompt after limit |
| Rate Limited (429) | Too many requests | Respect Retry-After, queue | Upload queued, invisible to user |
| Storage Error | Quota exceeded, blob conflict | Fail, report to backend | Storage full notification |
| Client Error | Out of memory, disk full | Graceful abort, cleanup | Device storage warning |

### 13.2 Edge Cases Handled

- **App kill during upload:** Upload state SQLDelight'a persist edilir; app restart'ta otomatik resume.
- **Network switch (WiFi → Cellular):** Aktif chunk tamamlanır, sonraki chunk yeni network'ten devam eder.
- **Duplicate file detection:** MD5 hash ile server-side dedup; aynı dosya tekrar yüklenmez, mevcut referans döner.
- **Concurrent uploads from same user:** Upload queue ile sıralama; configurable max concurrent limit.
- **SAS token expiry mid-upload:** Token expiry 2 dakika öncesinden kontrol edilir; yeni token seamless olarak alınır.
- **Partial chunk upload:** Her chunk'ın durumu tracked edilir; yarım kalan chunk baştan yüklenir (idempotent).
- **Device rotation / configuration change:** ViewModel + StateFlow ile upload state korunur.
- **Low battery mode:** Upload priority düşürülür; kritik seviyede pause, şarj'a takılınca devam eder.

---

## 14. Testing Strategy

| Level | Scope | Tools | Coverage Target |
|-------|-------|-------|----------------|
| Unit Tests | Core engine, state machine, chunking | kotlin.test, JUnit5, XCTest | > 90% |
| Integration Tests | SDK ↔ Azure Blob, API Gateway | Testcontainers, Azurite emulator | > 80% |
| Contract Tests | API endpoint schemas | Pact, OpenAPI validation | 100% endpoints |
| E2E Tests (Mobile) | Full upload flow per platform | Espresso (Android), XCUITest (iOS) | Critical paths |
| Performance Tests | Load, stress, soak testing | k6, Azure Load Testing | P95/P99 benchmarks |
| Chaos Tests | Network failure, service outage | toxiproxy, Chaos Monkey | All retry paths |
| Security Tests | Penetration, SAST, DAST | OWASP ZAP, SonarQube | Zero critical findings |

---

## 15. Cost Estimation (Azure — Monthly)

Aylık 10 TB upload hacmi, 50 TB toplam depolama ve 5 milyon upload işlemi bazında:

| Service | Usage | Est. Cost (USD) |
|---------|-------|----------------|
| Blob Storage (Hot) | 10 TB stored, 10 TB ingress | $200 – $350 |
| Blob Storage (Cool) | 30 TB stored | $150 – $250 |
| Blob Storage (Cool) — Transform Cache | ~2 TB (istenen varyasyonlar) | $30 – $50 |
| Blob Storage (Archive) | 10 TB stored | $15 – $25 |
| CDN (Front Door) | 5 TB egress (orijinal + transform) | $250 – $400 |
| Azure Functions — Upload | 5M upload exec, 2M GB-sec | $50 – $100 |
| Azure Functions — Image Transform | ~10M transform exec (CDN origin) | $40 – $80 |
| Cosmos DB | 10K RU/s, 50 GB | $400 – $600 |
| API Management (Standard) | 1 unit | $700 – $900 |
| Application Insights | 50 GB logs/month | $100 – $150 |
| SignalR + Defender | 1 unit + 5M transactions | $125 – $175 |
| **TOTAL** | | **$2,060 – $3,080** |

---

## 16. Implementation Roadmap

| Phase | Timeline | Deliverables |
|-------|----------|-------------|
| Phase 1: Foundation | Week 1–4 | Azure infra setup, Blob Storage, API Gateway, SAS token service, basic upload API |
| Phase 2: KMP Core | Week 3–8 | upload-api, upload-core, upload-domain, upload-network modules, state machine, chunking |
| Phase 3: Android | Week 6–10 | Internal platform module, WorkManager, ForegroundService, notification, sample app |
| Phase 4: iOS + SKIE | Week 8–12 | Internal platform module, URLSession background, SKIE config, XCFramework, sample app |
| Phase 5: Processing | Week 10–14 | Event Grid pipeline, malware scan, thumbnail generation, video transcode |
| Phase 6: Multi-App | Week 12–16 | App registration, per-app config, quota management, admin API |
| Phase 7: Hardening | Week 14–18 | Performance tuning, chaos testing, security audit, documentation |
| Phase 8: GA Release | Week 18–20 | SDK v1.0, Maven Central + SPM publish, migration guide, SDK docs |

---

# PART B — TECHNICAL IMPLEMENTATION

---

## 17. Azure Infrastructure Provisioning

Bu bölüm, tüm Azure kaynaklarını `az cli` ile sıfırdan kurmak için gereken komutları içerir. Claude Code'a veya terminale kopyala-yapıştır ile çalıştırılabilir.

### 17.1 Prerequisites

```bash
# Azure CLI kurulu olmalı
az --version  # 2.60+ gerekli

# Login
az login

# Subscription seç (birden fazla varsa)
az account set --subscription "YOUR_SUBSCRIPTION_ID"
```

### 17.2 Variables (Tüm script'lerde kullanılacak)

```bash
# ═══ Bu değerleri kendi ortamına göre değiştir ═══
RESOURCE_GROUP="rg-azurevault-prod"
LOCATION="westeurope"                    # Primary region
APP_NAME="azurevault"                    # Prefix for all resources
STORAGE_ACCOUNT="st${APP_NAME}prod"      # Max 24 chars, lowercase, no hyphens
COSMOS_ACCOUNT="cosmos-${APP_NAME}-prod"
APIM_NAME="apim-${APP_NAME}-prod"
FUNCTION_APP="func-${APP_NAME}-upload-prod"
FUNCTION_APP_IMG="func-${APP_NAME}-imgtransform-prod"
SIGNALR_NAME="signalr-${APP_NAME}-prod"
APPINSIGHTS_NAME="ai-${APP_NAME}-prod"
KEYVAULT_NAME="kv-${APP_NAME}-prod"
CDN_PROFILE="cdn-${APP_NAME}-prod"
CDN_ENDPOINT="img"
LOG_WORKSPACE="log-${APP_NAME}-prod"

# Tags (tüm kaynaklara uygulanır)
TAGS="project=azurevault environment=prod managed-by=cli"
```

### 17.3 Resource Group

```bash
az group create \
  --name $RESOURCE_GROUP \
  --location $LOCATION \
  --tags $TAGS
```

### 17.4 Storage Account + Containers

```bash
# Storage Account (LRS, Hot tier, soft delete enabled)
az storage account create \
  --name $STORAGE_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --location $LOCATION \
  --sku Standard_LRS \
  --kind StorageV2 \
  --access-tier Hot \
  --min-tls-version TLS1_2 \
  --allow-blob-public-access false \
  --https-only true \
  --tags $TAGS

# Soft delete (30 gün)
az storage blob service-properties delete-policy update \
  --account-name $STORAGE_ACCOUNT \
  --enable true \
  --days-retained 30

# Container versioning
az storage account blob-service-properties update \
  --account-name $STORAGE_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --enable-versioning true

# ═══ Containers ═══
CONTAINERS=("uploads-staging" "uploads-public" "uploads-quarantine" "uploads-cache")

for CONTAINER in "${CONTAINERS[@]}"; do
  az storage container create \
    --name $CONTAINER \
    --account-name $STORAGE_ACCOUNT \
    --auth-mode login
  echo "Created container: $CONTAINER"
done

# Public container — blob-level public access
az storage container set-permission \
  --name "uploads-public" \
  --account-name $STORAGE_ACCOUNT \
  --public-access blob

# Staging container — 7 gün lifecycle (auto-delete)
az storage account management-policy create \
  --account-name $STORAGE_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --policy '{
    "rules": [
      {
        "name": "staging-cleanup",
        "enabled": true,
        "type": "Lifecycle",
        "definition": {
          "filters": {
            "blobTypes": ["blockBlob"],
            "prefixMatch": ["uploads-staging/"]
          },
          "actions": {
            "baseBlob": {
              "delete": { "daysAfterCreationGreaterThan": 7 }
            }
          }
        }
      },
      {
        "name": "quarantine-cleanup",
        "enabled": true,
        "type": "Lifecycle",
        "definition": {
          "filters": {
            "blobTypes": ["blockBlob"],
            "prefixMatch": ["uploads-quarantine/"]
          },
          "actions": {
            "baseBlob": {
              "delete": { "daysAfterCreationGreaterThan": 1 }
            }
          }
        }
      },
      {
        "name": "cache-cool-tier",
        "enabled": true,
        "type": "Lifecycle",
        "definition": {
          "filters": {
            "blobTypes": ["blockBlob"],
            "prefixMatch": ["uploads-cache/"]
          },
          "actions": {
            "baseBlob": {
              "tierToCool": { "daysAfterCreationGreaterThan": 1 }
            }
          }
        }
      }
    ]
  }'

echo "✅ Storage Account + Containers created"
```

> **Not:** Per-app container'lar (`uploads-{appId}`) uygulama register edildiğinde dinamik olarak oluşturulur. Burada sadece sistem container'ları kuruluyor.

### 17.5 Cosmos DB

```bash
# Cosmos DB Account (ServerlessCapacity, Session consistency)
az cosmosdb create \
  --name $COSMOS_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --locations regionName=$LOCATION failoverPriority=0 \
  --default-consistency-level Session \
  --capabilities EnableServerless \
  --kind GlobalDocumentDB \
  --tags $TAGS

# Database
az cosmosdb sql database create \
  --account-name $COSMOS_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --name "azurevault-db"

# Uploads container (partition key: /appId)
az cosmosdb sql container create \
  --account-name $COSMOS_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --database-name "azurevault-db" \
  --name "uploads" \
  --partition-key-path "/appId" \
  --default-ttl -1

# App config container (partition key: /appId)
az cosmosdb sql container create \
  --account-name $COSMOS_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --database-name "azurevault-db" \
  --name "app-config" \
  --partition-key-path "/appId"

echo "✅ Cosmos DB created"
```

### 17.6 Key Vault

```bash
az keyvault create \
  --name $KEYVAULT_NAME \
  --resource-group $RESOURCE_GROUP \
  --location $LOCATION \
  --sku standard \
  --enable-rbac-authorization true \
  --tags $TAGS

# Storage Account connection string'i Key Vault'a ekle
STORAGE_CS=$(az storage account show-connection-string \
  --name $STORAGE_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --query connectionString -o tsv)

az keyvault secret set \
  --vault-name $KEYVAULT_NAME \
  --name "StorageConnectionString" \
  --value "$STORAGE_CS"

# Cosmos DB connection string
COSMOS_CS=$(az cosmosdb keys list \
  --name $COSMOS_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --type connection-strings \
  --query "connectionStrings[0].connectionString" -o tsv)

az keyvault secret set \
  --vault-name $KEYVAULT_NAME \
  --name "CosmosConnectionString" \
  --value "$COSMOS_CS"

echo "✅ Key Vault created + secrets stored"
```

### 17.7 Application Insights + Log Analytics

```bash
# Log Analytics Workspace
az monitor log-analytics workspace create \
  --workspace-name $LOG_WORKSPACE \
  --resource-group $RESOURCE_GROUP \
  --location $LOCATION \
  --tags $TAGS

LOG_WORKSPACE_ID=$(az monitor log-analytics workspace show \
  --workspace-name $LOG_WORKSPACE \
  --resource-group $RESOURCE_GROUP \
  --query id -o tsv)

# Application Insights
az monitor app-insights component create \
  --app $APPINSIGHTS_NAME \
  --resource-group $RESOURCE_GROUP \
  --location $LOCATION \
  --workspace $LOG_WORKSPACE_ID \
  --kind web \
  --tags $TAGS

APPINSIGHTS_KEY=$(az monitor app-insights component show \
  --app $APPINSIGHTS_NAME \
  --resource-group $RESOURCE_GROUP \
  --query instrumentationKey -o tsv)

echo "✅ Application Insights created (key: $APPINSIGHTS_KEY)"
```

### 17.8 Azure Functions — Upload Service

```bash
# Function App (Upload orchestration — SAS token, commit, metadata)
az functionapp create \
  --name $FUNCTION_APP \
  --resource-group $RESOURCE_GROUP \
  --storage-account $STORAGE_ACCOUNT \
  --consumption-plan-location $LOCATION \
  --runtime dotnet-isolated \
  --runtime-version 8 \
  --functions-version 4 \
  --os-type Linux \
  --app-insights $APPINSIGHTS_NAME \
  --tags $TAGS

# App settings
az functionapp config appsettings set \
  --name $FUNCTION_APP \
  --resource-group $RESOURCE_GROUP \
  --settings \
    "KeyVaultUri=https://${KEYVAULT_NAME}.vault.azure.net/" \
    "CosmosDbName=azurevault-db" \
    "StorageAccountName=$STORAGE_ACCOUNT"

# Managed Identity (Key Vault ve Storage erişimi için)
az functionapp identity assign \
  --name $FUNCTION_APP \
  --resource-group $RESOURCE_GROUP

UPLOAD_FUNC_IDENTITY=$(az functionapp identity show \
  --name $FUNCTION_APP \
  --resource-group $RESOURCE_GROUP \
  --query principalId -o tsv)

# Key Vault erişimi
az role assignment create \
  --assignee $UPLOAD_FUNC_IDENTITY \
  --role "Key Vault Secrets User" \
  --scope $(az keyvault show --name $KEYVAULT_NAME --query id -o tsv)

# Storage erişimi (SAS token generate edebilmesi için)
az role assignment create \
  --assignee $UPLOAD_FUNC_IDENTITY \
  --role "Storage Blob Data Contributor" \
  --scope $(az storage account show --name $STORAGE_ACCOUNT --query id -o tsv)

echo "✅ Upload Function App created with managed identity"
```

### 17.9 Azure Functions — Image Transform Service

```bash
# Function App (CDN origin — on-demand image transformation)
az functionapp create \
  --name $FUNCTION_APP_IMG \
  --resource-group $RESOURCE_GROUP \
  --storage-account $STORAGE_ACCOUNT \
  --consumption-plan-location $LOCATION \
  --runtime node \
  --runtime-version 20 \
  --functions-version 4 \
  --os-type Linux \
  --app-insights $APPINSIGHTS_NAME \
  --tags $TAGS

# App settings
az functionapp config appsettings set \
  --name $FUNCTION_APP_IMG \
  --resource-group $RESOURCE_GROUP \
  --settings \
    "StorageAccountName=$STORAGE_ACCOUNT" \
    "CACHE_CONTAINER=uploads-cache" \
    "MAX_WIDTH=2048" \
    "MAX_HEIGHT=2048"

# Managed Identity
az functionapp identity assign \
  --name $FUNCTION_APP_IMG \
  --resource-group $RESOURCE_GROUP

IMG_FUNC_IDENTITY=$(az functionapp identity show \
  --name $FUNCTION_APP_IMG \
  --resource-group $RESOURCE_GROUP \
  --query principalId -o tsv)

# Storage erişimi (orijinal oku + cache yaz)
az role assignment create \
  --assignee $IMG_FUNC_IDENTITY \
  --role "Storage Blob Data Contributor" \
  --scope $(az storage account show --name $STORAGE_ACCOUNT --query id -o tsv)

echo "✅ Image Transform Function App created"
```

### 17.10 Azure CDN (Front Door)

```bash
# CDN Profile
az cdn profile create \
  --name $CDN_PROFILE \
  --resource-group $RESOURCE_GROUP \
  --sku Standard_Microsoft \
  --tags $TAGS

# CDN Endpoint (Image Transform Function origin)
IMG_FUNC_HOSTNAME="${FUNCTION_APP_IMG}.azurewebsites.net"

az cdn endpoint create \
  --name $CDN_ENDPOINT \
  --resource-group $RESOURCE_GROUP \
  --profile-name $CDN_PROFILE \
  --origin $IMG_FUNC_HOSTNAME \
  --origin-host-header $IMG_FUNC_HOSTNAME \
  --enable-compression true \
  --content-types-to-compress "image/webp" "image/jpeg" "image/png" "image/avif" \
  --query-string-caching-behavior UseQueryString \
  --tags $TAGS

# Caching rule (7 gün)
az cdn endpoint rule add \
  --name $CDN_ENDPOINT \
  --resource-group $RESOURCE_GROUP \
  --profile-name $CDN_PROFILE \
  --order 1 \
  --rule-name "ImageCacheRule" \
  --match-variable UrlFileExtension \
  --operator Equal \
  --match-values "webp" "jpg" "jpeg" "png" "avif" \
  --action-name CacheExpiration \
  --cache-behavior Override \
  --cache-duration "7.00:00:00"

CDN_URL="https://${CDN_ENDPOINT}.azureedge.net"
echo "✅ CDN created: $CDN_URL"
```

### 17.11 API Management

```bash
# APIM (Developer tier for dev/test, Standard for production)
az apim create \
  --name $APIM_NAME \
  --resource-group $RESOURCE_GROUP \
  --location $LOCATION \
  --publisher-name "AzureVault" \
  --publisher-email "admin@company.com" \
  --sku-name Developer \
  --tags $TAGS

# Backend — Upload Function
UPLOAD_FUNC_URL="https://${FUNCTION_APP}.azurewebsites.net"

az apim api create \
  --resource-group $RESOURCE_GROUP \
  --service-name $APIM_NAME \
  --api-id "upload-api" \
  --path "v1/uploads" \
  --display-name "Upload API v1" \
  --service-url $UPLOAD_FUNC_URL \
  --protocols https \
  --subscription-required true

# Rate limiting policy (1000 req/min per user)
az apim api policy set \
  --resource-group $RESOURCE_GROUP \
  --service-name $APIM_NAME \
  --api-id "upload-api" \
  --xml-policy '<policies>
    <inbound>
      <rate-limit calls="1000" renewal-period="60" />
      <validate-jwt header-name="Authorization" require-scheme="Bearer">
        <openid-config url="https://login.microsoftonline.com/{tenant}/.well-known/openid-configuration" />
      </validate-jwt>
      <set-header name="X-Correlation-Id" exists-action="skip">
        <value>@(context.RequestId.ToString())</value>
      </set-header>
    </inbound>
  </policies>'

echo "✅ API Management created"
```

### 17.12 SignalR Service

```bash
az signalr create \
  --name $SIGNALR_NAME \
  --resource-group $RESOURCE_GROUP \
  --location $LOCATION \
  --sku Free_F1 \
  --service-mode Serverless \
  --tags $TAGS

echo "✅ SignalR created"
```

### 17.13 Event Grid (Upload Events)

```bash
# Storage Account üzerinde Event Grid subscription
STORAGE_ID=$(az storage account show \
  --name $STORAGE_ACCOUNT \
  --resource-group $RESOURCE_GROUP \
  --query id -o tsv)

UPLOAD_FUNC_ID=$(az functionapp show \
  --name $FUNCTION_APP \
  --resource-group $RESOURCE_GROUP \
  --query id -o tsv)

az eventgrid event-subscription create \
  --name "upload-blob-events" \
  --source-resource-id $STORAGE_ID \
  --endpoint-type azurefunction \
  --endpoint "${UPLOAD_FUNC_ID}/functions/BlobEventHandler" \
  --included-event-types \
    "Microsoft.Storage.BlobCreated" \
    "Microsoft.Storage.BlobDeleted" \
  --subject-begins-with "/blobServices/default/containers/uploads-"

echo "✅ Event Grid subscription created"
```

### 17.14 Microsoft Defender for Storage

```bash
az security pricing create \
  --name StorageAccounts \
  --tier Standard

echo "✅ Defender for Storage enabled"
```

### 17.15 Doğrulama — Tüm Kaynakları Listele

```bash
echo "═══════════════════════════════════════════"
echo "  AzureVault Infrastructure — Summary"
echo "═══════════════════════════════════════════"
az resource list \
  --resource-group $RESOURCE_GROUP \
  --output table \
  --query "[].{Name:name, Type:type, Location:location}"

echo ""
echo "Storage Account:    $STORAGE_ACCOUNT"
echo "Cosmos DB:          $COSMOS_ACCOUNT"
echo "Key Vault:          $KEYVAULT_NAME"
echo "Upload Function:    $FUNCTION_APP"
echo "Image Function:     $FUNCTION_APP_IMG"
echo "CDN Endpoint:       $CDN_URL"
echo "API Management:     https://${APIM_NAME}.azure-api.net"
echo "Application Insights: $APPINSIGHTS_NAME"
echo "SignalR:            $SIGNALR_NAME"
echo "═══════════════════════════════════════════"
```

### 17.16 Teardown (Tüm Altyapıyı Sil)

```bash
# ⚠️ DİKKAT: Bu komut TÜM kaynakları geri dönüşümsüz siler
az group delete --name $RESOURCE_GROUP --yes --no-wait
```

---

## 18. Development Environment & Prerequisites

### 18.1 Required Tools

| Tool | Min Version | Purpose |
|------|------------|---------|
| **JDK** | 17+ | Amazon Corretto veya Azul Zulu önerilir |
| **Android Studio** | Ladybug (2024.2+) | KMP plugin built-in, primary IDE |
| **Xcode** | 15.4+ | iOS build, simulator, signing |
| **Kotlin** | 2.0.21+ | `gradle/libs.versions.toml`'da tanımlanır |
| **Gradle** | 8.5+ | Wrapper ile gelir, ayrı kurulum yok |
| **CocoaPods** veya **SPM** | Latest | iOS framework dağıtımı |
| **SKIE** | 0.9+ | Gradle plugin olarak eklenir |
| **Ruby** (opsiyonel) | 3.0+ | Sadece CocoaPods kullanılacaksa |

### 18.2 Installation

```bash
# 1. JDK 17
brew install openjdk@17   # macOS
# veya sdkman: sdk install java 17.0.10-zulu

# 2. Android Studio — https://developer.android.com/studio

# 3. Xcode — App Store'dan

# 4. Xcode Command Line Tools
xcode-select --install

# 5. CocoaPods (opsiyonel)
sudo gem install cocoapods
```

### 18.3 IDE: Neden Android Studio, Neden VS Code Değil?

- **KMP desteği:** Kotlin Multiplatform, JetBrains IDE'lerinde first-class desteklenir. VS Code'da KMP plugin'i yok.
- **Multi-module Gradle:** Android Studio'da native çalışır. VS Code'da manuel config gerekir.
- **expect/actual navigation:** IDE, expect declaration'dan actual implementation'a tek tıkla götürür.
- **iOS simulator:** Android Studio'dan doğrudan iOS simulator'u çalıştırabilirsin.

---

## 19. Project Structure

```
AzureVaultUploadSDK/
│
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml
│
├── upload-api/                            # ★ PUBLIC — Consumer bunu görür
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/com/company/upload/
│       │   ├── AzureVaultUpload.kt        # Singleton entry point
│       │   ├── AzureVaultUploader.kt      # Upload API interface + impl
│       │   ├── UploadConfig.kt            # Configuration
│       │   ├── UploadMetadata.kt          # Upload metadata
│       │   ├── UploadState.kt             # Sealed class — all states
│       │   ├── UploadProgress.kt          # Progress data
│       │   ├── UploadInfo.kt              # Pending upload info
│       │   ├── UploadError.kt             # Error types
│       │   └── PlatformFile.kt            # expect class
│       ├── androidMain/kotlin/com/company/upload/
│       │   ├── PlatformFile.android.kt    # actual (Uri-based)
│       │   └── AndroidInitializer.kt      # Context init
│       └── iosMain/kotlin/com/company/upload/
│           ├── PlatformFile.ios.kt        # actual (NSURL-based)
│           └── IOSInitializer.kt          # iOS init
│
├── upload-core/                           # INTERNAL — Upload engine
│   └── src/commonMain/kotlin/com/company/upload/core/
│       ├── UploadEngine.kt                # Orchestrator
│       ├── UploadStateMachine.kt          # FSM implementation
│       ├── ChunkManager.kt               # Chunking strategy & execution
│       ├── UploadQueue.kt                 # Priority queue
│       ├── RetryHandler.kt               # Exponential backoff
│       └── BandwidthEstimator.kt          # Speed & ETA
│
├── upload-domain/                         # INTERNAL — Business logic
│   └── src/commonMain/kotlin/com/company/upload/domain/
│       ├── FileValidator.kt               # Size, MIME, name validation
│       ├── ChunkStrategy.kt              # Chunk size selection
│       ├── UploadUseCase.kt              # Main use case
│       └── ErrorClassifier.kt            # Error categorization
│
├── upload-network/                        # INTERNAL — HTTP & Azure
│   └── src/
│       ├── commonMain/kotlin/com/company/upload/network/
│       │   ├── UploadApiClient.kt         # Ktor API client
│       │   ├── SasTokenManager.kt         # SAS token lifecycle
│       │   ├── BlobUploader.kt            # Azure direct upload
│       │   └── ApiModels.kt              # Request/response DTOs
│       ├── androidMain/.../
│       │   └── HttpEngineFactory.kt       # OkHttp engine
│       └── iosMain/.../
│           └── HttpEngineFactory.kt       # Darwin engine
│
├── upload-storage/                        # INTERNAL — Persistence
│   └── src/
│       ├── commonMain/
│       │   ├── kotlin/.../
│       │   │   ├── UploadRepository.kt
│       │   │   └── ChunkStateRepository.kt
│       │   └── sqldelight/.../
│       │       ├── Upload.sq
│       │       └── ChunkState.sq
│       ├── androidMain/.../DriverFactory.android.kt
│       └── iosMain/.../DriverFactory.ios.kt
│
├── upload-crypto/                         # INTERNAL — Hashing
│   └── src/
│       ├── commonMain/.../HashCalculator.kt   # expect
│       ├── androidMain/.../HashCalculator.android.kt  # MessageDigest
│       └── iosMain/.../HashCalculator.ios.kt  # CommonCrypto
│
├── upload-platform-android/               # INTERNAL — Android specifics
│   └── src/androidMain/kotlin/.../
│       ├── AndroidBackgroundUploader.kt   # WorkManager
│       ├── AndroidForegroundService.kt    # Large file notification
│       ├── AndroidNetworkMonitor.kt       # ConnectivityManager
│       └── AndroidImageCompressor.kt      # Bitmap compression
│
├── upload-platform-ios/                   # INTERNAL — iOS specifics
│   └── src/iosMain/kotlin/.../
│       ├── IOSBackgroundUploader.kt       # URLSession background
│       ├── IOSNetworkMonitor.kt           # NWPathMonitor
│       └── IOSImageCompressor.kt          # UIImage compression
│
└── sample-android/                        # Sample consumer app
    └── src/main/kotlin/.../
        ├── SampleApp.kt                  # SDK init (5 satır)
        └── UploadScreen.kt               # Compose UI
```

---

## 20. Gradle Configuration

### 20.1 gradle/libs.versions.toml

```toml
[versions]
kotlin = "2.0.21"
agp = "8.5.2"
ktor = "2.3.12"
sqldelight = "2.0.2"
coroutines = "1.8.1"
serialization = "1.7.1"
koin = "3.5.6"
skie = "0.9.3"
androidx-work = "2.9.1"
androidx-lifecycle = "2.8.4"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-native-driver = { module = "app.cash.sqldelight:native-driver", version.ref = "sqldelight" }
androidx-work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "androidx-work" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
android-library = { id = "com.android.library", version.ref = "agp" }
android-application = { id = "com.android.application", version.ref = "agp" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
skie = { id = "co.touchlab.skie", version.ref = "skie" }
```

### 20.2 settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AzureVaultUploadSDK"

include(":upload-api")
include(":upload-core")
include(":upload-domain")
include(":upload-network")
include(":upload-storage")
include(":upload-crypto")
include(":upload-platform-android")
include(":upload-platform-ios")
include(":sample-android")
```

### 20.3 upload-api/build.gradle.kts (★ PUBLIC module)

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.skie)
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions { jvmTarget = "17" } }
        publishLibraryVariants("release")
    }

    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "AzureVaultUploadSDK"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":upload-core"))
            implementation(project(":upload-domain"))
            implementation(project(":upload-network"))
            implementation(project(":upload-storage"))
            implementation(project(":upload-crypto"))
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(project(":upload-platform-android"))
        }
        iosMain.dependencies {
            implementation(project(":upload-platform-ios"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

skie {
    features {
        enableFlowInterop.set(true)
        enableSealedInterop.set(true)
        enableSuspendInterop.set(true)
    }
}

android {
    namespace = "com.company.upload"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

### 20.4 upload-network/build.gradle.kts (Ktor example)

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget { compilations.all { kotlinOptions { jvmTarget = "17" } } }
    iosX64(); iosArm64(); iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(project(":upload-domain"))
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
    }
}

android {
    namespace = "com.company.upload.network"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

### 20.5 upload-storage/build.gradle.kts (SQLDelight example)

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget { compilations.all { kotlinOptions { jvmTarget = "17" } } }
    iosX64(); iosArm64(); iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies { implementation(libs.sqldelight.android.driver) }
        iosMain.dependencies { implementation(libs.sqldelight.native.driver) }
    }
}

sqldelight {
    databases {
        create("UploadDatabase") {
            packageName.set("com.company.upload.storage.db")
        }
    }
}

android {
    namespace = "com.company.upload.storage"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

### 20.6 gradle.properties

```properties
kotlin.code.style=official
kotlin.mpp.stability.nowarn=true
kotlin.mpp.androidSourceSetLayoutVersion=2
android.useAndroidX=true
android.nonTransitiveRClass=true
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC
```

---

## 21. Source Code — Public API

### 21.1 UploadConfig.kt

```kotlin
package com.company.upload

data class UploadConfig(
    val baseUrl: String,
    val appId: String,
    val authProvider: suspend () -> String = { "" },  // JWT token provider (not serializable)
    val maxConcurrentUploads: Int = 3,
    val chunkStrategy: ChunkStrategy = ChunkStrategy.AUTO,
    val retryPolicy: RetryPolicy = RetryPolicy.EXPONENTIAL,
    val enableCompression: Boolean = true,
    val maxFileSize: Long = 5L * 1024 * 1024 * 1024,  // 5 GB
    val chunkTimeoutMs: Long = 30_000L,                // 30 seconds per chunk
)

enum class ChunkStrategy { AUTO, SINGLE_SHOT, CHUNKED }
enum class RetryPolicy { NONE, LINEAR, EXPONENTIAL }
```

### 21.2 UploadState.kt

```kotlin
package com.company.upload

sealed class UploadState {
    data object Idle : UploadState()
    data object Validating : UploadState()
    data object RequestingToken : UploadState()

    data class Uploading(
        val uploadId: String,
        val progress: Float,           // 0.0 — 1.0
        val bytesUploaded: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val estimatedTimeRemaining: Long,
        val currentChunk: Int,
        val totalChunks: Int,
    ) : UploadState()

    data class Paused(
        val uploadId: String,
        val progress: Float,
        val bytesUploaded: Long,
        val totalBytes: Long,
    ) : UploadState()

    data object Committing : UploadState()

    data class Processing(
        val uploadId: String,
        val step: String,
    ) : UploadState()

    data class Completed(
        val uploadId: String,
        val fileId: String,
        val downloadUrl: String,
        val metadata: Map<String, String>,
        val blurHash: String? = null,          // Görseller için placeholder
    ) : UploadState()

    data class Failed(
        val uploadId: String?,
        val error: UploadError,
        val isRetryable: Boolean,
    ) : UploadState()

    data object Cancelled : UploadState()
}
```

### 21.3 UploadError.kt

```kotlin
package com.company.upload

sealed class UploadError(
    open val code: String,
    open val userMessage: String,
    open val technicalMessage: String? = null,
) {
    data class Validation(override val code: String, override val userMessage: String, val field: String)
        : UploadError(code, userMessage)
    data class Network(override val code: String, override val userMessage: String, val httpStatus: Int? = null)
        : UploadError(code, userMessage)
    data class Authentication(override val code: String = "AUTH_FAILED",
        override val userMessage: String = "Oturum süresi doldu, lütfen tekrar giriş yapın.")
        : UploadError(code, userMessage)
    data class Storage(override val code: String, override val userMessage: String)
        : UploadError(code, userMessage)
    data class Unknown(override val code: String = "UNKNOWN",
        override val userMessage: String = "Beklenmeyen bir hata oluştu.",
        override val technicalMessage: String? = null)
        : UploadError(code, userMessage, technicalMessage)
}
```

### 21.4 PlatformFile.kt (expect/actual)

```kotlin
// commonMain
package com.company.upload

expect class PlatformFile {
    val name: String
    val size: Long
    val mimeType: String?
}
```

```kotlin
// androidMain
package com.company.upload

import android.net.Uri
import android.content.ContentResolver
import android.provider.OpenableColumns

actual class PlatformFile(
    internal val uri: Uri,
    internal val contentResolver: ContentResolver,
) {
    actual val name: String get() {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            it.moveToFirst()
            it.getString(idx)
        } ?: uri.lastPathSegment ?: "unknown"
    }
    actual val size: Long get() {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.SIZE)
            it.moveToFirst()
            it.getLong(idx)
        } ?: 0L
    }
    actual val mimeType: String? get() = contentResolver.getType(uri)
}

fun Uri.toPlatformFile(contentResolver: ContentResolver) = PlatformFile(this, contentResolver)
```

```kotlin
// iosMain
package com.company.upload

import platform.Foundation.*

actual class PlatformFile(internal val url: NSURL) {
    actual val name: String get() = url.lastPathComponent ?: "unknown"
    actual val size: Long get() {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(url.path ?: "", error = null)
        return (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0L
    }
    actual val mimeType: String? get() = null  // Resolved internally via UTType
}
```

### 21.5 AzureVaultUpload.kt (Entry Point + Platform Initializers)

```kotlin
// commonMain
package com.company.upload

object AzureVaultUpload {
    private var _config: UploadConfig? = null
    private var _uploader: AzureVaultUploaderImpl? = null

    internal val config: UploadConfig
        get() = _config ?: error("AzureVaultUpload not initialized. Call initialize() first.")

    internal fun initializeInternal(config: UploadConfig) {
        _config = config
        // Wire up internal modules (engine, api client, repository, etc.)
        _uploader = AzureVaultUploaderImpl(/* ... */)
    }

    fun uploader(): AzureVaultUploader =
        _uploader ?: error("AzureVaultUpload not initialized. Call initialize() first.")
}
```

```kotlin
// androidMain
package com.company.upload

import android.content.Context

fun AzureVaultUpload.initialize(context: Context, config: UploadConfig) {
    PlatformDependencies.initialize(context.applicationContext)
    initializeInternal(config)
}

internal object PlatformDependencies {
    internal lateinit var appContext: Context; private set
    fun initialize(context: Context) { appContext = context }
}
```

```kotlin
// iosMain
package com.company.upload

fun AzureVaultUpload.initialize(config: UploadConfig) {
    IOSPlatformDependencies.initialize()
    initializeInternal(config)
}

internal object IOSPlatformDependencies {
    fun initialize() { /* URLSession background config, NWPathMonitor setup */ }
}
```

---

### 21.6 Supporting Data Classes

**UploadMetadata.kt:**

```kotlin
package com.company.upload

import kotlinx.serialization.Serializable

@Serializable
data class UploadMetadata(
    val entityType: String,
    val entityId: String,
    val isPublic: Boolean = false,
    val customMetadata: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
)
```

**AzureVaultUploader.kt (Interface):**

```kotlin
package com.company.upload

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AzureVaultUploader {
    fun upload(file: PlatformFile, metadata: UploadMetadata): Flow<UploadState>
    fun uploadBatch(files: List<PlatformFile>, metadata: UploadMetadata): Flow<BatchUploadState>
    fun pause(uploadId: String): Boolean
    fun resume(uploadId: String): Flow<UploadState>
    fun cancel(uploadId: String): Boolean
    fun getProgress(uploadId: String): StateFlow<UploadProgress>
    suspend fun getPendingUploads(): List<UploadInfo>
    fun retryFailed(uploadId: String): Flow<UploadState>
    suspend fun getDownloadUrl(fileId: String): String

    /** Optimize edilmiş görsel CDN URL — deterministic, network isteği yok */
    fun getImageUrl(
        fileId: String,
        width: Int? = null,
        height: Int? = null,
        fit: ImageFit = ImageFit.COVER,
        quality: Int = 80,
        format: ImageFormat = ImageFormat.AUTO,
    ): String

    /** BlurHash placeholder — local cache, sıfır network */
    fun getBlurHash(fileId: String): String?
}

enum class ImageFit { COVER, CONTAIN, SCALE_DOWN }
enum class ImageFormat { AUTO, WEBP, AVIF, JPEG, PNG }
```

**UploadProgress.kt:**

```kotlin
package com.company.upload

data class UploadProgress(
    val uploadId: String,
    val progress: Float,           // 0.0 — 1.0
    val bytesUploaded: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val estimatedTimeRemaining: Long,  // milliseconds
)
```

**UploadInfo.kt:**

```kotlin
package com.company.upload

data class UploadInfo(
    val uploadId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String?,
    val progress: Float,
    val state: UploadState,
    val createdAt: Long,          // epoch millis
    val entityType: String,
    val entityId: String,
)
```

**BatchUploadState.kt:**

```kotlin
package com.company.upload

data class BatchUploadState(
    val totalFiles: Int,
    val completedFiles: Int,
    val failedFiles: Int,
    val currentFile: UploadState,
    val overallProgress: Float,    // 0.0 — 1.0
)
```

---

## 22. Source Code — Internal Modules

### 22.1 UploadEngine.kt (upload-core) — Abbreviated

```kotlin
// upload-core/commonMain
package com.company.upload.core

internal class UploadEngine(
    private val config: UploadConfig,
    private val apiClient: UploadApiClient,
    private val sasManager: SasTokenManager,
    private val repository: UploadRepository,
    private val validator: FileValidator,
) {
    private val chunkManager = ChunkManager(config)
    private val retryHandler = RetryHandler(config.retryPolicy)
    private val bandwidthEstimator = BandwidthEstimator()

    internal fun upload(file: PlatformFile, metadata: UploadMetadata): Flow<UploadState> = flow {
        // 1. Validate
        emit(UploadState.Validating)
        val validation = validator.validate(file)
        if (!validation.isValid) { emit(UploadState.Failed(/* ... */)); return@flow }

        // 2. Request SAS token
        emit(UploadState.RequestingToken)
        val initResponse = apiClient.initiateUpload(file, metadata)

        // 3. Persist state for resume capability
        repository.saveUpload(initResponse.uploadId, file.name, file.size, metadata)

        // 4. Upload (single-shot or chunked based on file size)
        val strategy = chunkManager.determineStrategy(file.size)
        when (strategy) {
            is UploadStrategy.SingleShot -> uploadSingleShot(initResponse, file).collect { emit(it) }
            is UploadStrategy.Chunked -> uploadChunked(initResponse, file, strategy).collect { emit(it) }
        }
    }

    // ... uploadSingleShot(), uploadChunked(), etc.
}
```

### 22.2 SQLDelight Schema (upload-storage)

```sql
-- Upload.sq
CREATE TABLE upload (
    upload_id TEXT NOT NULL PRIMARY KEY,
    file_name TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    mime_type TEXT,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    progress REAL NOT NULL DEFAULT 0.0,
    blob_url TEXT,
    sas_token TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

selectAll: SELECT * FROM upload WHERE status != 'COMPLETED' ORDER BY created_at DESC;
selectById: SELECT * FROM upload WHERE upload_id = ?;
insert: INSERT INTO upload(upload_id, file_name, file_size, mime_type, entity_type, entity_id, status, progress, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
updateStatus: UPDATE upload SET status = ?, progress = ?, updated_at = ? WHERE upload_id = ?;
delete: DELETE FROM upload WHERE upload_id = ?;
```

```sql
-- ChunkState.sq
CREATE TABLE chunk_state (
    upload_id TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    block_id TEXT NOT NULL,
    size INTEGER NOT NULL,
    uploaded INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (upload_id, chunk_index)
);

selectByUpload: SELECT * FROM chunk_state WHERE upload_id = ? ORDER BY chunk_index;
markUploaded: UPDATE chunk_state SET uploaded = 1 WHERE upload_id = ? AND chunk_index = ?;
insert: INSERT INTO chunk_state(upload_id, chunk_index, block_id, size) VALUES (?, ?, ?, ?);
deleteByUpload: DELETE FROM chunk_state WHERE upload_id = ?;
```

---

## 23. Source Code — Platform Modules

### 23.1 AndroidBackgroundUploader.kt (Abbreviated)

```kotlin
// upload-platform-android/androidMain
package com.company.upload.platform

internal class AndroidBackgroundUploader(private val context: Context) {
    fun enqueueUpload(uploadId: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("uploadId" to uploadId))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("upload_$uploadId")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("upload_$uploadId", ExistingWorkPolicy.KEEP, request)
    }
}
```

### 23.2 IOSBackgroundUploader.kt (Abbreviated)

```kotlin
// upload-platform-ios/iosMain
package com.company.upload.platform

internal class IOSBackgroundUploader {
    private val sessionConfig = NSURLSessionConfiguration
        .backgroundSessionConfigurationWithIdentifier("com.company.upload.bg")
        .apply {
            isDiscretionary = false
            sessionSendsLaunchEvents = true
        }

    private val session: NSURLSession by lazy {
        NSURLSession.sessionWithConfiguration(sessionConfig, delegate = null, delegateQueue = null)
    }

    fun startBackgroundUpload(uploadId: String, fileUrl: NSURL, destinationUrl: String, sasToken: String) {
        val request = NSMutableURLRequest(NSURL(string = "$destinationUrl?$sasToken")).apply {
            HTTPMethod = "PUT"
            setValue("BlockBlob", forHTTPHeaderField = "x-ms-blob-type")
        }
        val task = session.uploadTaskWithRequest(request, fromFile = fileUrl)
        task.taskDescription = uploadId
        task.resume()
    }
}
```

---

## 24. Consumer Integration — Android

### 24.1 Application (SDK Init — 5 satır)

```kotlin
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AzureVaultUpload.initialize(
            context = this,
            config = UploadConfig(
                baseUrl = "https://api.company.com/v1",
                appId = "com.company.sampleapp",
                authProvider = { tokenStore.getAccessToken() }
            )
        )
    }
}
```

### 24.2 Compose UI (Shared ViewModel kullanır)

```kotlin
@Composable
fun UploadScreen(viewModel: UploadViewModel) {  // shared ViewModel
    val uiState by viewModel.state.collectAsState()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val file = it.toPlatformFile(context.contentResolver)  // tek satır platform kodu
            viewModel.upload(file, "document", "doc_001")          // shared method
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Button(onClick = { launcher.launch("*/*") }) { Text("Dosya Seç ve Yükle") }

        when (val state = uiState) {
            is UploadUiState.Uploading -> {
                LinearProgressIndicator(progress = { state.progress })
                Text("${(state.progress * 100).toInt()}%")
            }
            is UploadUiState.Done -> Text("Yükleme tamamlandı!")
            is UploadUiState.Error -> Text("Hata: ${state.message}")
            else -> {}
        }
    }
}
```

---

## 25. Consumer Integration — iOS

### 25.1 AppDelegate (SDK Init — 5 satır)

```swift
AzureVaultUpload.shared.initialize(
    config: UploadConfig(
        baseUrl: "https://api.company.com/v1",
        appId: "com.company.sampleios",
        authProvider: { TokenStore.shared.getAccessToken() }
    )
)
```

### 25.2 SwiftUI View (SKIE ile native hissiyat)

```swift
struct UploadView: View {
    @StateObject private var vm = UploadObservable()
    @State private var showPicker = false

    var body: some View {
        VStack(spacing: 20) {
            Button("Dosya Seç") { showPicker = true }
                .fileImporter(isPresented: $showPicker, allowedContentTypes: [.item]) { result in
                    if case .success(let url) = result {
                        vm.upload(url: url)
                    }
                }

            if case .uploading(let progress, _) = vm.state {
                ProgressView(value: Double(progress))
                Text("\(Int(progress * 100))%")
            } else if case .success = vm.state {
                Text("Yükleme tamamlandı!")
            } else if case .error(let msg) = vm.state {
                Text("Hata: \(msg)")
            }
        }
    }
}

class UploadObservable: ObservableObject {
    @Published var state: UploadUIState = .idle
    private let viewModel = UploadViewModel()  // shared KMP ViewModel

    func upload(url: URL) {
        let file = PlatformFile(url: url)
        viewModel.upload(fileRef: file, entityType: "document", entityId: "doc_001")

        Task {
            for await uiState in viewModel.state {
                await MainActor.run {
                    if let uploading = uiState as? UploadUiState.Uploading {
                        self.state = .uploading(uploading.progress, uploading.speed)
                    } else if uiState is UploadUiState.Done {
                        self.state = .success
                    } else if let error = uiState as? UploadUiState.Error {
                        self.state = .error(error.message)
                    }
                }
            }
        }
    }
}

enum UploadUIState {
    case idle
    case uploading(Float, Int64)
    case success
    case error(String)
}
```

---

## 26. Build, Test & Run

### 26.1 Build Commands

| Command | Description |
|---------|------------|
| `./gradlew build` | Tüm modülleri build et |
| `./gradlew :upload-api:assembleRelease` | Android AAR üret |
| `./gradlew :upload-api:linkReleaseFrameworkIosArm64` | iOS Framework üret (device) |
| `./gradlew :upload-api:linkDebugFrameworkIosSimulatorArm64` | iOS Framework üret (simulator) |
| `./gradlew :upload-api:assembleXCFramework` | Universal XCFramework (tüm arch) |
| `./gradlew :sample-android:installDebug` | Sample app'i Android cihaza yükle |

### 26.2 Test Commands

| Command | Description |
|---------|------------|
| `./gradlew :upload-core:allTests` | Common (shared) testleri çalıştır |
| `./gradlew :upload-api:testDebugUnitTest` | Android unit testleri |
| `./gradlew :upload-api:iosSimulatorArm64Test` | iOS testleri (simulator) |
| `./gradlew :upload-platform-android:connectedAndroidTest` | Android instrumented testleri |

---

## 27. SDK Distribution & Publishing

### 27.1 Android — Maven

```bash
# Local Maven (~/.m2)
./gradlew :upload-api:publishToMavenLocal

# Remote Maven (CI/CD)
./gradlew :upload-api:publish
```

**Consumer build.gradle.kts:**
```kotlin
dependencies {
    implementation("com.company:upload-api:1.0.0")
}
```

### 27.2 iOS — Swift Package Manager

```bash
# XCFramework oluştur
./gradlew :upload-api:assembleXCFramework

# Çıktı: upload-api/build/XCFrameworks/release/AzureVaultUploadSDK.xcframework
# Git repo'ya koy, Package.swift ile yayınla
```

### 27.3 iOS — CocoaPods (Alternatif)

```ruby
Pod::Spec.new do |spec|
  spec.name         = 'AzureVaultUploadSDK'
  spec.version      = '1.0.0'
  spec.vendored_frameworks = 'AzureVaultUploadSDK.xcframework'
end
```

---

## 28. Troubleshooting

| Sorun | Çözüm |
|-------|-------|
| `Unresolved reference: AzureVaultUpload` | `implementation("com.company:upload-api:x.y.z")` dependency eksik |
| iOS: "Framework not found" | `./gradlew :upload-api:linkDebugFrameworkIosSimulatorArm64` çalıştır |
| SKIE: Flow Swift'te görünmüyor | SKIE plugin build.gradle'da aktif mi? Clean build: `./gradlew clean build` |
| SQLDelight schema hatası | `./gradlew :upload-storage:generateCommonMainUploadDatabaseInterface` |
| `Context not initialized` | `AzureVaultUpload.initialize(context, config)` Application.onCreate()'da çağrıldı mı? |
| iOS background upload durmuyor | `Info.plist` → `UIBackgroundModes` → `fetch` ve `processing` ekle |
| Chunk upload timeout | `UploadConfig` içinde chunk timeout süresini artır veya chunk boyutunu düşür |
| ProGuard/R8 sorunları | SDK consumer-rules.pro ile keep rule'lar sağlamalı |
| XCFramework "missing architecture" | `assembleXCFramework` task'ını kullan, tekli link task değil |
| Kotlin version mismatch | Tüm modüllerde aynı Kotlin version olmalı (libs.versions.toml) |

---

# APPENDIX

---

## 29. Glossary

| Term | Definition |
|------|-----------|
| SAS Token | Shared Access Signature — Azure Blob Storage'a kısıtlı ve süreli erişim sağlayan signed URL |
| KMP | Kotlin Multiplatform — Tek codebase ile birden fazla platformda ortak kod paylaşımı |
| SKIE | Touchlab'ın Kotlin-Swift interop aracı — Flow→AsyncSequence, sealed→enum mapping |
| Block Upload | Azure Blob Storage'ın büyük dosyaları parça halinde yükleme mekanizması |
| Chunking | Büyük dosyaların küçük parçalara bölünerek paralel ve resumable yüklenmesi |
| expect/actual | KMP'nin platform-specific implementasyon mekanizması |
| ABAC | Attribute-Based Access Control — Kaynak özelliklerine dayalı erişim kontrolü |

---

## 30. Technology Stack

| Layer | Technologies |
|-------|-------------|
| Client SDK | Kotlin 2.0+, KMP, Ktor, SQLDelight, kotlinx.coroutines, kotlinx.serialization |
| iOS Bridge | SKIE (Touchlab), XCFramework, Swift Package Manager |
| Android Internal | Jetpack WorkManager, ContentResolver, NotificationCompat, Hilt/Koin |
| iOS Internal | URLSession, PhotoKit, Core Image, Keychain, NWPathMonitor |
| Backend | Azure Functions (.NET 8 or Kotlin/JVM), Azure API Management, Event Grid |
| Storage | Azure Blob Storage, Cosmos DB, Key Vault |
| Processing | Azure Media Services, Sharp (image), Microsoft Defender |
| Monitoring | Application Insights, Azure Monitor, Log Analytics, Grafana |
| CI/CD | Azure DevOps Pipelines, GitHub Actions, Gradle (KMP), Fastlane |
| Testing | kotlin.test, JUnit5, XCTest, k6, Azurite, Testcontainers |

---

## 31. Consumer Code Summary

| Code Area | Platform | Lines | Frequency |
|-----------|----------|-------|-----------|
| SDK Init | Android (Application) | 5 satır | Bir kez, proje başında |
| SDK Init | iOS (AppDelegate) | 5 satır | Bir kez, proje başında |
| File Reference | Android | 1 satır (`uri.toPlatformFile()`) | Her upload öncesi |
| File Reference | iOS | 1 satır (`PlatformFile(url:)`) | Her upload öncesi |
| Upload Logic | Shared (commonMain) | Sınırsız | Tüm upload operasyonları |
| State Management | Shared (commonMain) | Sınırsız | Tüm state yönetimi |
| Error Handling | Shared (commonMain) | Sınırsız | Tüm hata yönetimi |
| **Platform-Specific Upload Code** | **Her iki platform** | **0 satır** | **Asla** |

---

## 32. Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | March 14, 2026 | İlk PRD — Azure infra, KMP SDK, tüm platform desteği (Android, iOS, Web) |
| 2.0.0 | March 14, 2026 | Direct-to-storage (SAS token) yaklaşımı netleştirildi |
| 2.1.0 | March 14, 2026 | Zero Platform Code Architecture, SKIE entegrasyonu, web scope dışına alındı |
| **3.0.0** | **March 14, 2026** | **Konsolide doküman: PRD + Implementation Guide birleştirildi. Scope bölümü, Backend API response şemaları, Download flow, AuthProvider entegrasyonu eklendi. On-Demand Image Transform: 3 katmanlı cache (CDN + Transform Cache Blob + Origin), BlurHash, SDK deterministic CDN URL construction (backend API çağrısı yok). Maliyet tahmini transform cache ve Function invocations ile güncellendi.** |

---

*AzureVault Upload Platform — Complete PRD & Implementation Guide v3.0 — March 2026*
