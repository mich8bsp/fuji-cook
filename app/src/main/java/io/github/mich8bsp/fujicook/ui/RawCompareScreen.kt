package io.github.mich8bsp.fujicook.ui

import android.app.Application
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mich8bsp.fujicook.FujiCookApplication
import io.github.mich8bsp.fujicook.camera.*
import io.github.mich8bsp.fujicook.metadata.*
import io.github.mich8bsp.fujicook.model.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class RawState(
    val raf: Uri? = null,
    val rafName: String = "image.RAF",
    val chosen: Set<String> = emptySet(),
    val busy: Boolean = false,
    val progress: String = "",
    val results: List<Pair<Recipe, ByteArray>> = emptyList(),
    val message: String? = null,
)

class RawViewModel(app: Application) : AndroidViewModel(app) {
    val recipes = (app as FujiCookApplication).recipes.recipes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var state by mutableStateOf(RawState()); private set

    fun raf(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "image.RAF"
        state = state.copy(raf = uri, rafName = name)
    }

    fun toggle(id: String) {
        state = state.copy(chosen = if (id in state.chosen) state.chosen - id else state.chosen + id)
    }

    fun setGroup(ids: Collection<String>, selected: Boolean) {
        state = state.copy(chosen = if (selected) state.chosen + ids else state.chosen - ids)
    }

    fun process() = viewModelScope.launch {
        val source = state.raf ?: return@launch
        val selected = recipes.value.filter { it.id in state.chosen }
        state = state.copy(busy = true, results = emptyList(), message = null)
        runCatching {
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val manager = app.getSystemService(UsbManager::class.java)
                val device = manager.deviceList.values.firstOrNull { it.vendorId == AndroidFujiCamera.VENDOR && it.productId == AndroidFujiCamera.XT5 } ?: error("X-T5 not connected")
                require(manager.hasPermission(device)) { "USB permission required. Reconnect and approve Fuji Cook." }
                val bytes = app.contentResolver.openInputStream(source)!!.use { it.readBytes() }
                AndroidFujiCamera(manager, device).use { camera ->
                    camera.open()
                    camera.sendRaf(bytes)
                    val original = camera.getProfile()
                    selected.map { recipe ->
                        withContext(Dispatchers.Main) { state = state.copy(progress = "Rendering " + recipe.name) }
                        camera.setProfile(FujiProfile.build(original, recipe.current.settings))
                        recipe to camera.convert()
                    }
                }
            }
        }.onSuccess { state = state.copy(busy = false, progress = "", results = it, message = it.size.toString() + " render(s) complete") }
            .onFailure { state = state.copy(busy = false, progress = "", message = it.message) }
    }

    fun save(index: Int) = viewModelScope.launch {
        val pair = state.results[index]
        val source = state.raf ?: return@launch
        runCatching {
            withContext(Dispatchers.IO) {
                val recipeName = pair.first.name.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
                val base = state.rafName.substringBeforeLast('.', state.rafName)
                val fileName = "${base}_${recipeName}.JPG"
                val uri = createSiblingDocument(getApplication<Application>(), source, fileName)
                val parsed = JpegSegments.read(ByteArrayInputStream(pair.second))
                getApplication<Application>().contentResolver.openOutputStream(uri, "w")!!.use { JpegSegments.write(RecipeMetadata.tag(parsed, pair.first.name), it) }
                fileName
            }
        }.onSuccess { state = state.copy(message = it + " saved next to the RAF") }
            .onFailure { state = state.copy(message = it.message) }
    }
}

internal fun createSiblingDocument(app: Application, source: Uri, fileName: String): Uri {
    val resolver = app.contentResolver
    require(DocumentsContract.isDocumentUri(app, source)) { "The selected file's provider does not support saving beside the source file" }
    val documentId = DocumentsContract.getDocumentId(source)
    val parentId = when {
        '/' in documentId -> documentId.substringBeforeLast('/')
        ':' in documentId -> documentId.substringBeforeLast(':') + ":"
        else -> error("The selected file's provider does not expose its parent folder. Choose it from phone storage or an SD card.")
    }
    val parent = DocumentsContract.buildDocumentUri(source.authority!!, parentId)
    return requireNotNull(DocumentsContract.createDocument(resolver, parent, "image/jpeg", fileName)) { "Could not create output beside the source file" }
}

@Composable
fun RawCompareScreen(vm: RawViewModel = viewModel()) {
    val recipes by vm.recipes.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let(vm::raf) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("X-T5 RAW Compare", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { pick.launch(arrayOf("image/x-fuji-raf", "application/octet-stream")) }, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(if (vm.state.raf == null) "Choose RAF" else "Change RAF")
        }
        Text("Recipes", style = MaterialTheme.typography.titleMedium)
        var collapsed by remember { mutableStateOf(setOf<FilmSimulation>()) }
        val grouped = recipes.filterNot { it.archived }.groupBy { it.current.settings.filmSimulation }
        LazyColumn(Modifier.weight(1f)) {
            FilmSimulation.entries.forEach { sim ->
                val group = grouped[sim] ?: return@forEach
                val expanded = sim !in collapsed
                val allChosen = group.all { it.id in vm.state.chosen }
                item(key = "header_$sim") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allChosen, { vm.setGroup(group.map { it.id }, !allChosen) })
                        Row(
                            Modifier.weight(1f).clickable { collapsed = if (expanded) collapsed + sim else collapsed - sim },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null)
                            Text("${sim.name.replace('_', ' ')} (${group.size})", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (expanded) {
                    items(group, key = { it.id }) { r ->
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp)) {
                            Checkbox(r.id in vm.state.chosen, { vm.toggle(r.id) })
                            Text(r.name)
                        }
                    }
                }
            }
        }
        if (vm.state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(vm.state.progress)
        } else {
            Button(enabled = vm.state.raf != null && vm.state.chosen.isNotEmpty(), onClick = vm::process, modifier = Modifier.fillMaxWidth()) { Text("Render selected recipes") }
        }
        vm.state.results.forEachIndexed { i, p -> TextButton(onClick = { vm.save(i) }) { Text("Save " + p.first.name) } }
        vm.state.message?.let { Text(it) }
    }
}
