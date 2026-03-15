package com.company.upload.sample

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.company.upload.UploadUiState
import com.company.upload.UploadViewModel
import com.company.upload.toPlatformFile

@Composable
fun UploadScreen(viewModel: UploadViewModel = remember { UploadViewModel() }) {
    val context = LocalContext.current
    val uiState by viewModel.state.collectAsState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val file = it.toPlatformFile(context.contentResolver)
            viewModel.upload(file, "document", "doc_001")
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clear() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Button(onClick = { launcher.launch("*/*") }) {
            Text("Dosya Sec ve Yukle")
        }

        Spacer(modifier = Modifier.height(24.dp))

        when (val state = uiState) {
            is UploadUiState.Loading -> Text(state.message)
            is UploadUiState.Uploading -> {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("${(state.progress * 100).toInt()}%")

                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.pause() }) { Text("Duraklat") }
                    OutlinedButton(onClick = { viewModel.cancel() }) { Text("Iptal") }
                }
            }
            is UploadUiState.Paused -> {
                Text("Duraklatildi - ${(state.progress * 100).toInt()}%")
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.resume() }) { Text("Devam Et") }
            }
            is UploadUiState.Done -> {
                Text("Yukleme tamamlandi!")
                Text("File ID: ${state.fileId}", style = MaterialTheme.typography.bodySmall)
            }
            is UploadUiState.Error -> {
                Text("Hata: ${state.message}", color = MaterialTheme.colorScheme.error)
                if (state.isRetryable) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { viewModel.retry() }) { Text("Tekrar Dene") }
                }
            }
            is UploadUiState.Idle -> Text("Bir dosya secin")
        }
    }
}
