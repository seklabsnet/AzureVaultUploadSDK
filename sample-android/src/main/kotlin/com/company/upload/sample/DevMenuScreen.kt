package com.company.upload.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.company.upload.UploadUiState
import com.company.upload.UploadViewModel
import com.company.upload.toPlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

// ─── Colors ────────────────────────────────────────────────────────────────────

private const val TAG = "AzureVault"

private val ImageColor = Color(0xFF2196F3)
private val VideoColor = Color(0xFFE91E63)
private val DocumentColor = Color(0xFFFF9800)
private val AudioColor = Color(0xFF9C27B0)
private val PickerColor = Color(0xFF4CAF50)
private val ValidationColor = Color(0xFFF44336)

// ─── Main Screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevMenuScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = remember { UploadViewModel() }
    val uiState by viewModel.state.collectAsState()

    var showSheet by remember { mutableStateOf(false) }
    var activeTestName by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    // ─── State change logger ────────────────────────────────────────
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is UploadUiState.Idle -> Log.d(TAG, "━━━ State: IDLE ━━━")
            is UploadUiState.Loading -> Log.i(TAG, "⏳ State: LOADING → ${state.message}")
            is UploadUiState.Uploading -> Log.i(TAG, "📤 State: UPLOADING → ${(state.progress * 100).toInt()}% | ${formatSpeed(state.speed)} | ETA: ${formatEta(state.eta)}")
            is UploadUiState.Paused -> Log.w(TAG, "⏸️ State: PAUSED → ${(state.progress * 100).toInt()}%")
            is UploadUiState.Done -> Log.i(TAG, "✅ State: DONE → fileId=${state.fileId} url=${state.downloadUrl}")
            is UploadUiState.Error -> Log.e(TAG, "❌ State: ERROR → ${state.message} (retryable=${state.isRetryable})")
        }
    }

    // Single file picker — MIME type set before launch
    var pendingEntityType by remember { mutableStateOf("document") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val platformFile = it.toPlatformFile(context.contentResolver)
            Log.i(TAG, "┌─────────────────────────────────────────────")
            Log.i(TAG, "│ 📁 FILE PICKED")
            Log.i(TAG, "│ Name: ${platformFile.name}")
            Log.i(TAG, "│ Size: ${platformFile.size} bytes (${formatFileSize(platformFile.size)})")
            Log.i(TAG, "│ MIME: ${platformFile.mimeType}")
            Log.i(TAG, "│ URI:  $it")
            Log.i(TAG, "│ Entity: $pendingEntityType")
            Log.i(TAG, "└─────────────────────────────────────────────")
            showSheet = true
            // In production: get grant from your backend (POST /api/upload/start)
            // val grant = api.post("/api/upload/start", mapOf("entityType" to pendingEntityType))
            // customMetadata = mapOf("x-upload-grant" to grant.grant)
            // For sample app: upload without grant (backend will receive event.grant = null)
            viewModel.upload(platformFile, pendingEntityType, "test_${System.currentTimeMillis()}")
        }
    }

    fun pickFile(testName: String, mimeType: String, entityType: String) {
        Log.d(TAG, "🔍 Opening file picker: test=$testName mimeType=$mimeType")
        activeTestName = testName
        pendingEntityType = entityType
        downloadError = null
        filePicker.launch(mimeType)
    }

    fun autoDownload(testName: String, url: String, fileName: String, entityType: String) {
        Log.i(TAG, "┌─────────────────────────────────────────────")
        Log.i(TAG, "│ 🚀 TEST STARTED: $testName")
        Log.i(TAG, "│ URL: $url")
        Log.i(TAG, "│ File: $fileName")
        Log.i(TAG, "└─────────────────────────────────────────────")
        activeTestName = testName
        downloadError = null
        showSheet = true
        isDownloading = true
        scope.launch {
            Log.d(TAG, "⬇️ Downloading test file: $url")
            val startTime = System.currentTimeMillis()
            val file = downloadTestFile(context, url, fileName)
            val elapsed = System.currentTimeMillis() - startTime
            isDownloading = false
            if (file != null) {
                Log.i(TAG, "┌─────────────────────────────────────────────")
                Log.i(TAG, "│ ✅ DOWNLOAD COMPLETE (${elapsed}ms)")
                Log.i(TAG, "│ File: ${file.name}")
                Log.i(TAG, "│ Size: ${file.length()} bytes (${formatFileSize(file.length())})")
                Log.i(TAG, "│ Path: ${file.absolutePath}")
                Log.i(TAG, "└─────────────────────────────────────────────")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val platformFile = uri.toPlatformFile(context.contentResolver)
                Log.i(TAG, "📤 Starting upload: name=${platformFile.name} size=${platformFile.size} mime=${platformFile.mimeType}")
                viewModel.upload(platformFile, entityType, "test_${System.currentTimeMillis()}")
            } else {
                Log.e(TAG, "❌ DOWNLOAD FAILED after ${elapsed}ms: $url")
                downloadError = "Test dosyasi indirilemedi. Internet baglantisini kontrol edin."
            }
        }
    }

    fun autoDownloadBatch(testName: String) {
        Log.i(TAG, "🚀 BATCH TEST STARTED: $testName — downloading 3 files...")
        activeTestName = testName
        downloadError = null
        showSheet = true
        isDownloading = true
        scope.launch {
            val files = listOf(
                downloadTestFile(context, "https://picsum.photos/seed/batch1/400/400", "batch_1.jpg"),
                downloadTestFile(context, "https://picsum.photos/seed/batch2/600/600", "batch_2.jpg"),
                downloadTestFile(context, "https://picsum.photos/seed/batch3/800/800", "batch_3.jpg"),
            )
            isDownloading = false
            val downloaded = files.filterNotNull()
            Log.i(TAG, "📦 Batch download: ${downloaded.size}/3 files ready")
            val first = downloaded.firstOrNull()
            if (first != null) {
                Log.i(TAG, "📤 Uploading first batch file: ${first.name} (${formatFileSize(first.length())})")
                viewModel.upload(
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", first).toPlatformFile(context.contentResolver),
                    "image",
                    "batch_${System.currentTimeMillis()}",
                )
            } else {
                Log.e(TAG, "❌ BATCH DOWNLOAD FAILED: no files downloaded")
                downloadError = "Test dosyalari indirilemedi."
            }
        }
    }

    fun validationTest(testName: String, creator: (Context) -> File) {
        Log.w(TAG, "🧪 VALIDATION TEST: $testName — expecting failure!")
        activeTestName = testName
        downloadError = null
        showSheet = true
        val file = creator(context)
        Log.d(TAG, "🧪 Created test file: ${file.name} size=${file.length()} bytes")
        viewModel.upload(
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toPlatformFile(context.contentResolver),
            "document",
            "validation_${System.currentTimeMillis()}",
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AzureVault", fontWeight = FontWeight.Bold)
                        Text(
                            "Dev Test Menu",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ─── Environment Info ───────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Environment", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        InfoRow("App", "centauri")
                        InfoRow("Backend", "YOUR_FUNCTION_APP.azurewebsites.net")
                        InfoRow("Max Upload", "100 MB (image), 5 GB (video)")
                        InfoRow("Chunk Threshold", "4 MB")
                    }
                }
            }

            // ─── Auto-Download: Image Size Tests ────────────────────────
            item { SectionHeader("Image Size Tests", ImageColor) }

            item {
                TestCard(
                    title = "Tiny Image (~5 KB)",
                    description = "Cok kucuk dosya, single-shot upload. Minimum viable test.",
                    accentColor = ImageColor,
                    thumbnailUrl = "https://picsum.photos/seed/azure-tiny/100/100",
                ) {
                    autoDownload("Tiny Image", "https://picsum.photos/seed/azure-tiny/100/100", "tiny.jpg", "image")
                }
            }
            item {
                TestCard(
                    title = "Small Image (~50 KB)",
                    description = "Normal boyut, single-shot upload. Chunking yok.",
                    accentColor = ImageColor,
                    thumbnailUrl = "https://picsum.photos/seed/azure-small/100/100",
                ) {
                    autoDownload("Small Image", "https://picsum.photos/seed/azure-small/400/400", "small.jpg", "image")
                }
            }
            item {
                TestCard(
                    title = "Medium Image (~500 KB)",
                    description = "Orta boyut, hala single-shot. 4 MB threshold'a yakin.",
                    accentColor = ImageColor,
                    thumbnailUrl = "https://picsum.photos/seed/azure-medium/100/100",
                ) {
                    autoDownload("Medium Image", "https://picsum.photos/seed/azure-medium/1500/1500", "medium.jpg", "image")
                }
            }
            item {
                TestCard(
                    title = "Large File (~6 MB)",
                    description = "Chunk threshold'u (4 MB) asiyor. Chunked upload + progress tracking.",
                    accentColor = ImageColor,
                ) {
                    activeTestName = "Large Image (Chunked)"
                    downloadError = null
                    showSheet = true
                    scope.launch {
                        Log.i(TAG, "🚀 CHUNKED TEST: Generating large noisy JPEG (3000x3000)...")
                        val file = withContext(Dispatchers.IO) {
                            val dir = File(context.cacheDir, "test_files")
                            dir.mkdirs()
                            val f = File(dir, "large_chunked.jpg")
                            val size = 3000
                            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                            // Fill with pseudo-random noise — JPEG can't compress this well
                            val pixels = IntArray(size * size)
                            var seed = 42
                            for (i in pixels.indices) {
                                seed = seed * 1103515245 + 12345 // LCG random
                                pixels[i] = (0xFF shl 24) or (seed and 0x00FFFFFF)
                            }
                            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
                            f.outputStream().use { out ->
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            bitmap.recycle()
                            f
                        }
                        Log.i(TAG, "✅ Generated: ${file.name} (${file.length()} bytes, ${formatFileSize(file.length())})")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val platformFile = uri.toPlatformFile(context.contentResolver)
                        Log.i(TAG, "📤 Starting chunked upload: name=${platformFile.name} size=${platformFile.size} mime=${platformFile.mimeType}")
                        viewModel.upload(platformFile, "image", "chunked_test_${System.currentTimeMillis()}")
                    }
                }
            }

            // ─── File Picker Tests ──────────────────────────────────────
            item { SectionHeader("File Picker Tests", PickerColor) }

            item {
                TestCard(
                    title = "Pick Image",
                    description = "Galeriden resim sec. JPEG, PNG, HEIC, WebP desteklenir.",
                    accentColor = PickerColor,
                ) {
                    pickFile("Image Upload", "image/*", "image")
                }
            }
            item {
                TestCard(
                    title = "Pick Video",
                    description = "Video sec. MP4, MOV, MKV, WebM. Buyuk dosyalar chunked yuklenecek.",
                    accentColor = VideoColor,
                ) {
                    pickFile("Video Upload", "video/*", "video")
                }
            }
            item {
                TestCard(
                    title = "Pick Document",
                    description = "PDF, Word, Excel, PowerPoint, TXT, CSV.",
                    accentColor = DocumentColor,
                ) {
                    pickFile("Document Upload", "application/*", "document")
                }
            }
            item {
                TestCard(
                    title = "Pick Audio",
                    description = "MP3, AAC, WAV, FLAC, OGG. Max 500 MB.",
                    accentColor = AudioColor,
                ) {
                    pickFile("Audio Upload", "audio/*", "audio")
                }
            }
            item {
                TestCard(
                    title = "Pick Any File",
                    description = "Tum dosya tiplerini goster. Filtresiz test.",
                    accentColor = PickerColor,
                ) {
                    pickFile("Any File Upload", "*/*", "document")
                }
            }

            // ─── Document Size Tests ─────────────────────────────────────
            item { SectionHeader("Document Size Tests (PDF)", DocumentColor) }

            item {
                TestCard(
                    title = "Tiny PDF (~10 KB)",
                    description = "1 sayfa, sadece text. Single-shot upload.",
                    accentColor = DocumentColor,
                ) {
                    activeTestName = "Tiny PDF"
                    downloadError = null
                    showSheet = true
                    scope.launch {
                        Log.i(TAG, "📄 Generating tiny PDF (1 page, text only)...")
                        val file = withContext(Dispatchers.IO) { generatePdf(context, "tiny_doc.pdf", pages = 1, withImages = false) }
                        Log.i(TAG, "✅ Generated: ${file.name} (${formatFileSize(file.length())})")
                        uploadGeneratedFile(context, file, "document", viewModel)
                    }
                }
            }
            item {
                TestCard(
                    title = "Small PDF (~100 KB)",
                    description = "5 sayfa, text + grafikler. ~2 MB single-shot.",
                    accentColor = DocumentColor,
                ) {
                    activeTestName = "Small PDF"
                    downloadError = null
                    showSheet = true
                    scope.launch {
                        Log.i(TAG, "📄 Generating small PDF (5 pages, text + graphics)...")
                        val file = withContext(Dispatchers.IO) { generatePdf(context, "small_doc.pdf", pages = 5, withImages = true) }
                        Log.i(TAG, "✅ Generated: ${file.name} (${formatFileSize(file.length())})")
                        uploadGeneratedFile(context, file, "document", viewModel)
                    }
                }
            }
            item {
                TestCard(
                    title = "Medium PDF (~500 KB)",
                    description = "8 sayfa, yogun icerik. ~3.5 MB, threshold'a yakin.",
                    accentColor = DocumentColor,
                ) {
                    activeTestName = "Medium PDF"
                    downloadError = null
                    showSheet = true
                    scope.launch {
                        Log.i(TAG, "📄 Generating medium PDF (8 pages)...")
                        val file = withContext(Dispatchers.IO) { generatePdf(context, "medium_doc.pdf", pages = 8, withImages = true) }
                        Log.i(TAG, "✅ Generated: ${file.name} (${formatFileSize(file.length())})")
                        uploadGeneratedFile(context, file, "document", viewModel)
                    }
                }
            }
            item {
                TestCard(
                    title = "Large PDF (~5 MB+)",
                    description = "15 sayfa, buyuk bitmap'ler. ~6 MB, chunked upload.",
                    accentColor = DocumentColor,
                ) {
                    activeTestName = "Large PDF (Chunked)"
                    downloadError = null
                    showSheet = true
                    scope.launch {
                        Log.i(TAG, "📄 Generating large PDF (15 pages, heavy graphics)...")
                        val file = withContext(Dispatchers.IO) { generatePdf(context, "large_doc.pdf", pages = 15, withImages = true) }
                        Log.i(TAG, "✅ Generated: ${file.name} (${formatFileSize(file.length())})")
                        uploadGeneratedFile(context, file, "document", viewModel)
                    }
                }
            }

            // ─── Batch & Stress ─────────────────────────────────────────
            item { SectionHeader("Batch & Stress Tests", Color(0xFF607D8B)) }

            item {
                TestCard(
                    title = "Batch Upload (3 Images)",
                    description = "3 resim indir ve sirali yukle. Queue + concurrency testi.",
                    accentColor = Color(0xFF607D8B),
                ) {
                    autoDownloadBatch("Batch Upload")
                }
            }

            // ─── Validation Tests ───────────────────────────────────────
            item { SectionHeader("Validation Tests (should fail)", ValidationColor) }

            item {
                TestCard(
                    title = "Empty File (0 bytes)",
                    description = "Bos dosya yukleme denemesi. 'File size must be > 0' hatasi bekleniyor.",
                    accentColor = ValidationColor,
                ) {
                    validationTest("Empty File") { ctx ->
                        val dir = File(ctx.cacheDir, "test_files")
                        dir.mkdirs()
                        File(dir, "empty.txt").also { it.writeBytes(ByteArray(0)) }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // ─── Upload Progress Bottom Sheet ───────────────────────────────────────
    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = {
                showSheet = false
                viewModel.clear()
            },
            sheetState = sheetState,
        ) {
            UploadSheetContent(
                testName = activeTestName,
                isDownloading = isDownloading,
                downloadError = downloadError,
                uiState = uiState,
                onPause = {
                    Log.w(TAG, "⏸️ User tapped PAUSE")
                    viewModel.pause()
                },
                onResume = {
                    Log.i(TAG, "▶️ User tapped RESUME")
                    viewModel.resume()
                },
                onCancel = {
                    Log.w(TAG, "🛑 User tapped CANCEL")
                    viewModel.cancel()
                    showSheet = false
                },
                onRetry = {
                    Log.i(TAG, "🔄 User tapped RETRY")
                    viewModel.retry()
                },
                onDismiss = {
                    Log.d(TAG, "━━━ Sheet dismissed ━━━")
                    showSheet = false
                    viewModel.clear()
                },
            )
        }
    }
}

// ─── Upload Sheet Content ──────────────────────────────────────────────────────

@Composable
private fun UploadSheetContent(
    testName: String,
    isDownloading: Boolean,
    downloadError: String?,
    uiState: UploadUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 48.dp)
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            testName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        when {
            isDownloading -> {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Text(
                    "Test dosyasi indiriliyor...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            downloadError != null -> {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    downloadError,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onDismiss) { Text("Kapat") }
            }

            else -> when (val state = uiState) {
                is UploadUiState.Idle -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text("Hazirlaniyor...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                is UploadUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Text(state.message, style = MaterialTheme.typography.bodyLarge)
                }

                is UploadUiState.Uploading -> {
                    Text(
                        "${(state.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            formatSpeed(state.speed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.eta > 0) {
                            Text(
                                "~${formatEta(state.eta)} kaldi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onPause) {
                            Text("Duraklat")
                        }
                        FilledTonalButton(
                            onClick = onCancel,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text("Iptal")
                        }
                    }
                }

                is UploadUiState.Paused -> {
                    Text(
                        "${(state.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.outline,
                    )

                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = MaterialTheme.colorScheme.outline,
                    )

                    Text(
                        "Duraklatildi",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Devam Et")
                        }
                        OutlinedButton(onClick = onCancel) {
                            Text("Iptal")
                        }
                    }
                }

                is UploadUiState.Done -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        "Yukleme Basarili!",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            InfoRow("File ID", state.fileId)
                            InfoRow("URL", state.downloadUrl)
                        }
                    }
                    Button(onClick = onDismiss) { Text("Kapat") }
                }

                is UploadUiState.Error -> {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.isRetryable) {
                            Button(onClick = onRetry) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Tekrar Dene")
                            }
                        }
                        OutlinedButton(onClick = onDismiss) { Text("Kapat") }
                    }
                }
            }
        }
    }
}

// ─── Reusable Components ───────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, color: Color) {
    Row(
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(4.dp, 20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = MaterialTheme.typography.labelLarge.letterSpacing * 1.5f,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestCard(
    title: String,
    description: String,
    accentColor: Color,
    thumbnailUrl: String? = null,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentColor),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ─── Utilities ─────────────────────────────────────────────────────────────────

private suspend fun downloadTestFile(context: Context, url: String, fileName: String): File? {
    return withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "test_files")
            dir.mkdirs()
            val file = File(dir, fileName)
            // Always re-download for fresh tests
            URL(url).openStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

private fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0) return "..."
    val kb = bytesPerSecond / 1024.0
    if (kb < 1024) return "${kb.toInt()} KB/s"
    return "%.1f MB/s".format(kb / 1024.0)
}

private fun formatEta(etaMs: Long): String {
    val seconds = etaMs / 1000
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    val remaining = seconds % 60
    return "${minutes}m ${remaining}s"
}

private fun uploadGeneratedFile(
    context: Context,
    file: File,
    entityType: String,
    viewModel: UploadViewModel,
) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val platformFile = uri.toPlatformFile(context.contentResolver)
    Log.i(TAG, "📤 Uploading: name=${platformFile.name} size=${platformFile.size} mime=${platformFile.mimeType}")
    viewModel.upload(platformFile, entityType, "${entityType}_test_${System.currentTimeMillis()}")
}

private fun generatePdf(context: Context, fileName: String, pages: Int, withImages: Boolean): File {
    val dir = File(context.cacheDir, "test_files")
    dir.mkdirs()
    val file = File(dir, fileName)

    val doc = PdfDocument()
    val pageWidth = 595  // A4
    val pageHeight = 842

    val textPaint = Paint().apply {
        color = AndroidColor.BLACK
        textSize = 14f
        isAntiAlias = true
    }
    val titlePaint = Paint().apply {
        color = AndroidColor.rgb(0, 120, 212) // Azure blue
        textSize = 24f
        isFakeBoldText = true
        isAntiAlias = true
    }
    val shapePaint = Paint().apply {
        isAntiAlias = true
    }

    var seed = 73

    for (p in 0 until pages) {
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, p + 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        // White background
        canvas.drawColor(AndroidColor.WHITE)

        // Header
        canvas.drawText("AzureVault Upload SDK — Test Document", 40f, 50f, titlePaint)
        canvas.drawText("Page ${p + 1} / $pages", 40f, 80f, textPaint)

        // Text content
        val loremLines = listOf(
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
            "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.",
            "Ut enim ad minim veniam, quis nostrud exercitation ullamco.",
            "Duis aute irure dolor in reprehenderit in voluptate velit esse.",
            "Excepteur sint occaecat cupidatat non proident, sunt in culpa.",
            "Curabitur pretium tincidunt lacus. Nulla gravida orci a odio.",
            "Nullam varius, turpis et commodo pharetra, est eros bibendum elit.",
            "Praesent dapibus, neque id cursus faucibus, tortor neque egestas.",
            "Fusce ac turpis quis ligula lacinia aliquet. Mauris ipsum.",
            "Nulla metus metus, ullamcorper vel tincidunt sed, euismod in nibh.",
        )
        for (i in 0 until 20) {
            val y = 120f + i * 22f
            if (y > pageHeight - 100) break
            canvas.drawText(loremLines[i % loremLines.size], 40f, y, textPaint)
        }

        // Bitmap image per page — makes PDF significantly larger (raster data)
        if (withImages) {
            val imgW = 500
            val imgH = 300
            val bmp = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(imgW * imgH)
            for (i in pixels.indices) {
                seed = seed * 1103515245 + 12345
                pixels[i] = (0xFF shl 24) or (seed and 0x00FFFFFF)
            }
            bmp.setPixels(pixels, 0, imgW, 0, 0, imgW, imgH)
            canvas.drawBitmap(bmp, 40f, 500f, null)
            bmp.recycle()
        }

        doc.finishPage(page)
    }

    file.outputStream().use { out -> doc.writeTo(out) }
    doc.close()

    return file
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
