package io.github.mich8bsp.fujicook.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mich8bsp.fujicook.FujiCookApplication
import io.github.mich8bsp.fujicook.metadata.*
import io.github.mich8bsp.fujicook.model.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*

data class BatchItem(
    val uri: Uri,
    val name: String,
    val thumbnail: Bitmap?,
    val jpeg: ParsedJpeg,
    val match: MatchResult,
    val selected: MatchCandidate?,
)

data class BatchState(
    val folder: Uri? = null,
    val items: List<BatchItem> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

class BatchTaggerViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as FujiCookApplication).recipes
    var state by mutableStateOf(BatchState()); private set

    fun loadFolder(tree: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        state = BatchState(folder = tree, busy = true)
        val resolver = app.contentResolver
        val loaded = withContext(Dispatchers.IO) {
            listJpegs(resolver, tree).mapNotNull { (uri, name) ->
                runCatching {
                    val data = resolver.openInputStream(uri)!!.use { it.readBytes() }
                    val jpeg = JpegSegments.read(ByteArrayInputStream(data))
                    val ex = FujifilmMakerNote.extract(jpeg)
                    val active = RecipeMatcher.match(ex.settings, repo.matchableRevisions())
                    val match = if (active.status == MatchStatus.NO_MATCH) RecipeMatcher.match(ex.settings, repo.matchableRevisions(includeArchived = true)) else active
                    BatchItem(uri, name, decodeThumbnail(data), jpeg, match, match.candidates.firstOrNull())
                }.getOrNull()
            }
        }
        state = state.copy(items = loaded, busy = false, message = if (loaded.isEmpty()) "No JPEGs found in that folder" else null)
    }

    fun select(uri: Uri, candidate: MatchCandidate) {
        state = state.copy(items = state.items.map { if (it.uri == uri) it.copy(selected = candidate) else it })
    }

    fun remove(uri: Uri) {
        state = state.copy(items = state.items.filterNot { it.uri == uri })
    }

    fun saveAll() = viewModelScope.launch {
        val tree = state.folder ?: return@launch
        state = state.copy(busy = true, message = null)
        val app = getApplication<Application>()
        val folderDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var saved = 0
        val errors = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            state.items.forEach { item ->
                val c = item.selected ?: return@forEach
                runCatching {
                    val fileName = suggestedFileName(item.name, c.recipe.name)
                    val out = requireNotNull(DocumentsContract.createDocument(app.contentResolver, folderDoc, "image/jpeg", fileName)) { "Could not create $fileName" }
                    app.contentResolver.openOutputStream(out, "w")!!.use { JpegSegments.write(RecipeMetadata.tag(item.jpeg, c.recipe.name, c.modifiedSummary), it) }
                    saved++
                }.onFailure { errors += item.name + ": " + (it.message ?: it.javaClass.simpleName) }
            }
        }
        state = state.copy(busy = false, message = "$saved tagged photo(s) saved" + if (errors.isNotEmpty()) "; " + errors.take(3).joinToString("; ") else "")
    }
}

private fun listJpegs(resolver: ContentResolver, tree: Uri): List<Pair<Uri, String>> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val out = mutableListOf<Pair<Uri, String>>()
    resolver.query(
        childrenUri,
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
        null, null, null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            val docId = cursor.getString(0)
            val name = cursor.getString(1) ?: continue
            val mime = cursor.getString(2) ?: ""
            if (mime == "image/jpeg" || name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }) {
                out += DocumentsContract.buildDocumentUriUsingTree(tree, docId) to name
            }
        }
    }
    return out
}

private fun decodeThumbnail(data: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 200 || bounds.outHeight / sample > 200) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
}

@Composable
fun BatchTaggerScreen(vm: BatchTaggerViewModel = viewModel()) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::loadFolder) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Batch Tagger", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { pick.launch(null) }, modifier = Modifier.padding(vertical = 12.dp)) { Text(if (vm.state.folder == null) "Choose folder" else "Change folder") }
        if (vm.state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f)) {
            items(vm.state.items, key = { it.uri }) { item -> BatchItemCard(item, onSelect = { vm.select(item.uri, it) }, onRemove = { vm.remove(item.uri) }) }
        }
        if (vm.state.items.any { it.selected != null }) {
            Button(onClick = vm::saveAll, enabled = !vm.state.busy, modifier = Modifier.fillMaxWidth()) { Text("Save tagged") }
        }
        vm.state.message?.let { Text(it, Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun BatchItemCard(item: BatchItem, onSelect: (MatchCandidate) -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                item.thumbnail?.let { Image(it.asImageBitmap(), item.name, Modifier.size(64.dp)) } ?: Text("No preview", style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text("Result: " + item.match.status, style = MaterialTheme.typography.bodySmall)
                item.match.candidates.take(5).forEach { candidate ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(item.selected?.revision?.id == candidate.revision.id, { onSelect(candidate) })
                        Column {
                            Text(candidate.recipe.name + if (candidate.recipe.archived) " (archived)" else "", style = MaterialTheme.typography.bodyMedium)
                            Text((candidate.confidence * 100).toInt().toString() + "% · " + candidate.differences.size + " difference(s)", style = MaterialTheme.typography.bodySmall)
                            candidate.modifiedSummary?.let { Text("Will tag as modified: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove") }
        }
    }
}
