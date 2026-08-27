import androidx.compose.runtime.Composable
import kotlinx.io.files.Path

data class SaveDocumentRequest(
    val sourcePath: Path,
    val suggestedFileName: String,
    val mimeType: String,
)

enum class SaveDocumentResult {
    Saved,
    Canceled,
    Failed,
}

@Composable
expect fun rememberOpenDocumentLauncher(onResult: (List<DocumentAttachment>?) -> Unit): (mimeTypeFilter: List<String>) -> Unit

@Composable
expect fun rememberOpenPhotoLauncher(onResult: (List<DocumentAttachment>?) -> Unit): () -> Unit

@Composable
expect fun rememberSaveDocumentLauncher(onResult: (SaveDocumentResult) -> Unit): (SaveDocumentRequest) -> Unit
