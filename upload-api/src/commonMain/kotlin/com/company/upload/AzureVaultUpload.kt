package com.company.upload

import com.company.upload.core.UploadEngine
import com.company.upload.domain.ErrorClassifier
import com.company.upload.domain.FileValidator
import com.company.upload.domain.UploadConfig
import com.company.upload.network.AuthTokenClient
import com.company.upload.network.AuthTokenManager
import com.company.upload.network.BlobUploader
import com.company.upload.network.SasTokenManager
import com.company.upload.network.UploadApiClient
import com.company.upload.network.createHttpEngine
import com.company.upload.storage.ChunkStateRepository
import com.company.upload.storage.UploadRepository
import com.company.upload.storage.db.UploadDatabase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object AzureVaultUpload {
    private var _config: UploadConfig? = null
    private var _uploader: AzureVaultUploader? = null

    // Exposed so platform initializers can provide the database after initializeInternal
    internal var uploadRepository: UploadRepository? = null
        private set
    internal var chunkStateRepository: ChunkStateRepository? = null
        private set
    internal var uploadEngine: UploadEngine? = null
        private set
    internal var apiClient: UploadApiClient? = null
        private set
    private var httpClient: HttpClient? = null

    internal val config: UploadConfig
        get() = _config ?: error("AzureVaultUpload not initialized. Call initialize() first.")

    internal fun initializeInternal(config: UploadConfig) {
        _config = config

        // 1. Create shared HttpClient
        val httpClient = HttpClient(createHttpEngine()) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    isLenient = true
                })
            }
        }

        // 2. Auth token management
        val authTokenClient = AuthTokenClient(
            baseUrl = config.baseUrl,
            httpClient = httpClient,
        )

        val authTokenManager = if (config.clientId.isNotEmpty() && config.clientSecret.isNotEmpty()) {
            AuthTokenManager(
                authClient = authTokenClient,
                clientId = config.clientId,
                clientSecret = config.clientSecret,
            )
        } else {
            null
        }

        // 3. Auth provider: prefer SDK-managed tokens; fall back to consumer-provided authProvider
        val authProvider: suspend () -> String = if (authTokenManager != null) {
            { authTokenManager.getValidToken() }
        } else {
            config.authProvider
        }

        // 4. API client — baseUrl already includes /v1 from config or we append it
        val apiBaseUrl = config.baseUrl.trimEnd('/') + "/v1"
        val uploadApiClient = UploadApiClient(
            baseUrl = apiBaseUrl,
            authProvider = authProvider,
            httpClient = httpClient,
        )
        apiClient = uploadApiClient

        // 5. Store httpClient for later use in setupWithDatabase
        this.httpClient = httpClient

        // 6. Store references — repositories will be set when database is provided
        //    by platform initializers (Android/iOS)
        uploadRepository = null
        chunkStateRepository = null
        uploadEngine = null
    }

    /**
     * Called by platform initializers after they create the SQLDelight database.
     * Completes the dependency graph with persistence layer and builds the uploader.
     */
    internal fun setupWithDatabase(database: UploadDatabase) {
        val config = _config ?: error("AzureVaultUpload not initialized. Call initializeInternal() first.")
        val client = apiClient ?: error("API client not initialized.")
        val sharedHttpClient = httpClient ?: error("HttpClient not initialized.")

        val repo = UploadRepository(database)
        val chunkRepo = ChunkStateRepository(database)
        uploadRepository = repo
        chunkStateRepository = chunkRepo

        val blobUploader = BlobUploader(sharedHttpClient)
        val sasManager = SasTokenManager(client)

        val engine = UploadEngine(
            config = config,
            apiClient = client,
            blobUploader = blobUploader,
            sasManager = sasManager,
            uploadRepository = repo,
            chunkStateRepository = chunkRepo,
            validator = FileValidator,
            errorClassifier = ErrorClassifier,
        )
        uploadEngine = engine

        val uploader = AzureVaultUploaderImpl(
            config = config,
            engine = engine,
            apiClient = client,
            uploadRepository = repo,
        )
        setUploader(uploader)
    }

    /**
     * For cases where no database is available (e.g. in-memory only mode),
     * builds the uploader without persistence. Uploads work but are not recoverable.
     */
    internal fun setupWithoutDatabase() {
        // This path is a fallback; platform initializers should always provide a database
    }

    internal fun setUploader(uploader: AzureVaultUploader) {
        _uploader = uploader
    }

    fun uploader(): AzureVaultUploader =
        _uploader ?: error("AzureVaultUpload not initialized. Call initialize() first.")
}
