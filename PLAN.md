# AzureVault Upload Platform — Implementation Plan

**PRD Version:** 3.0.0 · **Start Date:** 2026-03-15

---

## Overview

Azure Blob Storage üzerine inşa edilmiş, KMP (Kotlin Multiplatform) tabanlı kurumsal dosya yükleme SDK'sı.
- **Upload Modeli:** Direct-to-Storage (SAS token ile client → Azure Blob)
- **Prensip:** Zero Platform Code for Consumer
- **Platformlar:** Android (API 24+), iOS (15.0+)
- **SKIE:** Swift-native interop (Flow → AsyncSequence, sealed class → enum)

---

## Phase 1: Project Skeleton & Gradle Setup
- [x] Git repo oluştur
- [x] PLAN.md oluştur
- [x] Tüm modül dizinlerini oluştur (PRD Section 19)
- [x] `gradle/libs.versions.toml` — dependency catalog
- [x] `settings.gradle.kts` — module includes
- [x] `build.gradle.kts` (root) — plugin management
- [x] `gradle.properties` — JVM args, KMP config
- [x] Gradle wrapper ekle (8.7)
- [x] Her modül için `build.gradle.kts`:
  - [x] upload-api (PUBLIC, KMP + SKIE)
  - [x] upload-core (INTERNAL, KMP common only)
  - [x] upload-domain (INTERNAL, KMP common only)
  - [x] upload-network (INTERNAL, KMP + Ktor)
  - [x] upload-storage (INTERNAL, KMP + SQLDelight)
  - [x] upload-crypto (INTERNAL, KMP expect/actual)
  - [x] upload-platform-android (INTERNAL, Android only)
  - [x] upload-platform-ios (INTERNAL, iOS only)
  - [x] sample-android (Android app)
- [x] `.gitignore`

## Phase 2: Public API — upload-api Module
- [x] `UploadConfig.kt` — configuration data class (upload-domain'de)
- [x] `UploadState.kt` — sealed class (10 state) (upload-domain'de)
- [x] `UploadError.kt` — sealed class error types (upload-domain'de)
- [x] `PlatformFile.kt` — expect/actual (Android: Uri, iOS: NSURL)
- [x] `AzureVaultUpload.kt` — singleton entry point + platform initializers
- [x] `AzureVaultUploader.kt` — interface (upload, pause, resume, cancel, batch, etc.)
- [x] `UploadMetadata.kt` — serializable metadata (upload-domain'de)
- [x] `UploadProgress.kt` — progress data class (upload-domain'de)
- [x] `UploadInfo.kt` — pending upload info (upload-domain'de)
- [x] `BatchUploadState.kt` — batch state (upload-domain'de)

## Phase 3: Core Engine — upload-core Module
- [x] `UploadEngine.kt` — orchestrator (validate → token → upload → commit)
- [x] `UploadStateMachine.kt` — FSM (IDLE→VALIDATING→...→COMPLETED/FAILED)
- [x] `ChunkManager.kt` — chunking strategy & parallel execution
- [x] `UploadQueue.kt` — priority queue with max concurrency
- [x] `RetryHandler.kt` — exponential backoff (1s, 2s, 4s, 8s, 16s)
- [x] `BandwidthEstimator.kt` — speed & ETA calculation

## Phase 4: Domain Logic — upload-domain Module
- [x] `FileValidator.kt` — size, MIME, name validation
- [x] `ChunkStrategy.kt` — chunk size selection by file size
- [x] `UploadUseCase.kt` — main use case orchestration
- [x] `ErrorClassifier.kt` — error categorization (transient/auth/validation/etc.)

## Phase 5: Network Layer — upload-network Module
- [x] `UploadApiClient.kt` — Ktor HTTP client (initiate, complete, status, download-url)
- [x] `SasTokenManager.kt` — SAS token lifecycle (request, refresh, expiry check)
- [x] `BlobUploader.kt` — Azure Blob direct upload (PUT block, PUT block list)
- [x] `ApiModels.kt` — request/response DTOs
- [x] `HttpEngineFactory.kt` — expect/actual (OkHttp / Darwin)

## Phase 6: Persistence — upload-storage Module
- [x] `Upload.sq` — SQLDelight schema (upload table)
- [x] `ChunkState.sq` — SQLDelight schema (chunk_state table)
- [x] `UploadRepository.kt` — upload CRUD operations
- [x] `ChunkStateRepository.kt` — chunk state tracking
- [x] `DriverFactory.kt` — expect/actual (Android/Native driver)

## Phase 7: Crypto — upload-crypto Module
- [x] `HashCalculator.kt` — expect/actual
  - Android: `java.security.MessageDigest` (MD5, SHA256)
  - iOS: `CommonCrypto` (CC_MD5, CC_SHA256)
- [x] Streaming hash calculation (chunk-by-chunk)

## Phase 8: Platform Modules
### upload-platform-android
- [x] `AndroidBackgroundUploader.kt` — WorkManager integration
- [x] `UploadWorker.kt` + `AndroidNotificationHelper.kt` — foreground notification
- [x] `AndroidNetworkMonitor.kt` — ConnectivityManager callback
- [x] `AndroidImageCompressor.kt` — Bitmap JPEG compression

### upload-platform-ios
- [x] `IOSBackgroundUploader.kt` — URLSession background config
- [x] `IOSNetworkMonitor.kt` — NWPathMonitor
- [x] `IOSImageCompressor.kt` — UIImage jpegData

## Phase 9: Sample App — sample-android
- [x] `SampleApp.kt` — SDK init (5 satır)
- [x] `UploadScreen.kt` — Compose UI
- [x] `AndroidManifest.xml` — permissions, service declaration

## Phase 10: Shared ViewModel
- [x] `UploadViewModel.kt` — commonMain, %100 shared (upload, pause, resume, cancel, retry)
- [x] `UploadUiState.kt` — UI state sealed class (Idle, Loading, Uploading, Paused, Done, Error)
- [x] Sample app ViewModel entegrasyonu

## Phase 11: Testing
- [x] Unit tests — upload-domain: FileValidator (17), ChunkStrategy (13), ErrorClassifier (19) = 49 tests
- [x] Unit tests — upload-core: StateMachine (21), ChunkManager (8), RetryHandler (8), BandwidthEstimator (8), UploadQueue (8) = 53 tests
- [ ] Integration tests — upload-network (Ktor mock engine)
- [ ] Contract tests — API endpoint schemas

## Phase 12: Build & Distribution
- [x] Android AAR → Maven publish config (maven-publish plugin, local + remote repo)
- [x] iOS XCFramework → SPM Package.swift (XCFramework DSL + Package.swift)
- [x] ProGuard/R8 consumer rules (consumer-rules.pro)
- [x] CI/CD pipeline (GitHub Actions — build, test, publish workflow)

## Phase 13: Azure Infrastructure (Backend)
- [ ] Resource Group, Storage Account, Containers
- [ ] Cosmos DB (metadata store)
- [ ] Key Vault (secrets)
- [ ] Azure Functions — Upload Service (.NET)
- [ ] Azure Functions — Image Transform (Node.js + Sharp)
- [ ] CDN (Front Door) — image delivery
- [ ] API Management — gateway + rate limiting
- [ ] Event Grid — blob events
- [ ] Application Insights + Log Analytics
- [ ] SignalR (v1.1)
- [ ] Microsoft Defender for Storage

---

## Architecture Notes

**Shared types (UploadState, UploadConfig, etc.) upload-domain modülünde yaşar.**
upload-api bu tipleri `api(project(":upload-domain"))` ile re-export eder, böylece consumer'lar
sadece upload-api'ye bağımlı olup domain tiplerini de görebilir.

## Architecture Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Upload model | Direct-to-Storage (SAS) | Backend'e dosya trafiği binmez, Azure native throughput |
| Image optimization | On-demand transform + 3-layer cache | Pre-gen'e göre daha esnek, storage tasarrufu |
| State persistence | SQLDelight | KMP native, type-safe, migration support |
| HTTP client | Ktor | KMP first-class support, engine per platform |
| DI | Koin | Lightweight, KMP compatible |
| iOS interop | SKIE | Flow→AsyncSequence, sealed→enum, suspend→async |
| Chunking | Adaptive (4MB-32MB) | File size'a göre optimal chunk + concurrency |

---

## Module Dependency Graph

```
upload-api (PUBLIC)
  ├── upload-domain (api — shared types, re-exported to consumers)
  ├── upload-core (INTERNAL)
  │   ├── upload-domain
  │   ├── upload-network (INTERNAL)
  │   │   └── upload-domain
  │   ├── upload-storage (INTERNAL)
  │   └── upload-crypto (INTERNAL)
  ├── upload-platform-android (INTERNAL, Android only)
  └── upload-platform-ios (INTERNAL, iOS only)

sample-android
  └── upload-api
```
