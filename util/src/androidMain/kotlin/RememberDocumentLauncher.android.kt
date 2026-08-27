import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File

@Composable
actual fun rememberOpenDocumentLauncher(onResult: (List<DocumentAttachment>?) -> Unit): (mimeTypeFilter: List<String>) -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            val contentResolver = context.contentResolver
            val sources = it.map { uri ->
                val stream = contentResolver.openInputStream(uri) ?: error("Provider crashed")
                val size = stream.available().toLong()
                val source = stream
                    .asSource()
                    .buffered()
                val mimeType = if (uri.scheme == "content") {
                    contentResolver.getType(uri)
                } else {
                    val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                }
                val sourceName = getNameFromUri(uri, contentResolver) ?: "unknown"
                DocumentAttachment(
                    fileName = sourceName,
                    mimeType = mimeType,
                    source = source,
                    size = size,
                )
            }
            if (sources.isEmpty()) {
                onResult(null)
            } else {
                onResult(sources)
            }
        }
    return { mimeTypeFilter ->
        launcher.launch(mimeTypeFilter.toTypedArray())
    }
}

private fun getNameFromUri(uri: Uri, contentResolver: ContentResolver): String? {
    if (uri.scheme == "content") {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                check(idx > -1)
                return it.getString(idx)
            }
        }
    } else {
        return uri.lastPathSegment
    }
    return null
}

@Composable
actual fun rememberOpenPhotoLauncher(onResult: (List<DocumentAttachment>?) -> Unit): () -> Unit {
    val launcher = rememberOpenDocumentLauncher(onResult)
    return {
        launcher(listOf("image/*"))
    }
}

private class SaveDocumentContract : ActivityResultContract<SaveDocumentRequest, Uri?>() {
    override fun createIntent(context: Context, input: SaveDocumentRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.mimeType
            putExtra(Intent.EXTRA_TITLE, input.suggestedFileName)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

@Composable
actual fun rememberSaveDocumentLauncher(onResult: (SaveDocumentResult) -> Unit): (SaveDocumentRequest) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    var pendingRequest by remember { mutableStateOf<SaveDocumentRequest?>(null) }
    val launcher = rememberLauncherForActivityResult(SaveDocumentContract()) { destination ->
        val request = pendingRequest
        pendingRequest = null
        if (destination == null || request == null) {
            currentOnResult(SaveDocumentResult.Canceled)
        } else {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = checkNotNull(context.contentResolver.openOutputStream(destination))
                        File(request.sourcePath.toString()).inputStream().use { input ->
                            output.use(input::copyTo)
                        }
                    }.isSuccess
                }
                currentOnResult(if (saved) SaveDocumentResult.Saved else SaveDocumentResult.Failed)
            }
        }
    }
    return { request ->
        pendingRequest = request
        launcher.launch(request)
    }
}
