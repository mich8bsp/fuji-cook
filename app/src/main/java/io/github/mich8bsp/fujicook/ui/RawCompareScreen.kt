package io.github.mich8bsp.fujicook.ui

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

enum class ConnectionStatus { NOT_FOUND, NEEDS_PERMISSION, CONNECTED }

data class RawState(
    val raf: Uri? = null,
    val rafName: String = "image.RAF",
    val outputTree: Uri? = null,
    val chosen: Set<String> = emptySet(),
    val busy: Boolean = false,
    val progress: String = "",
    val message: String? = null,
    val connection: ConnectionStatus = ConnectionStatus.NOT_FOUND,
)

private const val ACTION_USB_PERMISSION = "io.github.mich8bsp.fujicook.USB_PERMISSION"

class RawViewModel(app: Application) : AndroidViewModel(app) {
    val recipes = (app as FujiCookApplication).recipes.recipes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var state by mutableStateOf(RawState()); private set

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshConnection()
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(app, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshConnection()
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(usbReceiver)
    }

    private fun device(): UsbDevice? {
        val manager = getApplication<Application>().getSystemService(UsbManager::class.java)
        return manager.deviceList.values.firstOrNull { it.vendorId == AndroidFujiCamera.VENDOR && it.productId == AndroidFujiCamera.XT5 }
    }

    fun refreshConnection() {
        val manager = getApplication<Application>().getSystemService(UsbManager::class.java)
        val device = device()
        state = state.copy(connection = when {
            device == null -> ConnectionStatus.NOT_FOUND
            !manager.hasPermission(device) -> ConnectionStatus.NEEDS_PERMISSION
            else -> ConnectionStatus.CONNECTED
        })
    }

    fun requestPermission() {
        val app = getApplication<Application>()
        val manager = app.getSystemService(UsbManager::class.java)
        val device = device() ?: run { state = state.copy(message = "X-T5 not found. Check the USB cable."); return }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        val pendingIntent = PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.requestPermission(device, pendingIntent)
    }

    fun resetConnection() = viewModelScope.launch {
        val manager = getApplication<Application>().getSystemService(UsbManager::class.java)
        val device = device() ?: run { state = state.copy(message = "X-T5 not found. Check the USB cable."); return@launch }
        runCatching {
            withContext(Dispatchers.IO) {
                require(manager.hasPermission(device)) { "USB permission required. Tap Request permission." }
                AndroidFujiCamera(manager, device).use { it.open() }
            }
        }.onSuccess { state = state.copy(message = "Connection reset") }
            .onFailure { state = state.copy(message = it.message) }
        refreshConnection()
    }

    fun raf(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "image.RAF"
        state = state.copy(raf = uri, rafName = name)
    }

    fun outputFolder(uri: Uri) {
        getApplication<Application>().contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        state = state.copy(outputTree = uri)
    }

    fun toggle(id: String) {
        state = state.copy(chosen = if (id in state.chosen) state.chosen - id else state.chosen + id)
    }

    fun setGroup(ids: Collection<String>, selected: Boolean) {
        state = state.copy(chosen = if (selected) state.chosen + ids else state.chosen - ids)
    }

    fun process() = viewModelScope.launch {
        val source = state.raf ?: return@launch
        val tree = state.outputTree ?: run { state = state.copy(message = "Choose an output folder first"); return@launch }
        val selected = recipes.value.filter { it.id in state.chosen }
        state = state.copy(busy = true, message = null)
        runCatching {
            withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val manager = app.getSystemService(UsbManager::class.java)
                val device = device() ?: error("X-T5 not connected")
                require(manager.hasPermission(device)) { "USB permission required. Tap Request permission." }
                val bytes = app.contentResolver.openInputStream(source)!!.use { it.readBytes() }
                val resolver = app.contentResolver
                val folderDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val base = state.rafName.substringBeforeLast('.', state.rafName)
                var saved = 0
                AndroidFujiCamera(manager, device).use { camera ->
                    camera.open()
                    camera.sendRaf(bytes)
                    val original = camera.getProfile()
                    selected.forEach { recipe ->
                        withContext(Dispatchers.Main) { state = state.copy(progress = "Rendering " + recipe.name) }
                        camera.setProfile(FujiProfile.build(original, recipe.current.settings))
                        val rendered = camera.convert()
                        val recipeName = recipe.name.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
                        val fileName = "${base}_${recipeName}.JPG"
                        val outUri = requireNotNull(DocumentsContract.createDocument(resolver, folderDoc, "image/jpeg", fileName)) { "Could not create output in the chosen folder" }
                        val parsed = JpegSegments.read(ByteArrayInputStream(rendered))
                        resolver.openOutputStream(outUri, "w")!!.use { JpegSegments.write(RecipeMetadata.tag(parsed, recipe.name), it) }
                        saved++
                    }
                }
                saved
            }
        }.onSuccess { state = state.copy(busy = false, progress = "", message = "$it file(s) saved") }
            .onFailure { state = state.copy(busy = false, progress = "", message = it.message) }
        refreshConnection()
    }
}

@Composable
fun RawCompareScreen(vm: RawViewModel = viewModel()) {
    val recipes by vm.recipes.collectAsState()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { t -> t?.let(vm::outputFolder) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { vm.raf(it); pickFolder.launch(null) }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("X-T5 RAW Compare", style = MaterialTheme.typography.headlineMedium)
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val (label, color) = when (vm.state.connection) {
                ConnectionStatus.CONNECTED -> "Connected" to Color(0xFF2E7D32)
                ConnectionStatus.NEEDS_PERMISSION -> "Permission needed" to Color(0xFFF9A825)
                ConnectionStatus.NOT_FOUND -> "Not connected" to Color(0xFFC62828)
            }
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label)
            Spacer(Modifier.weight(1f))
            if (vm.state.connection == ConnectionStatus.NEEDS_PERMISSION) {
                TextButton(onClick = vm::requestPermission) { Text("Request permission") }
            }
            TextButton(onClick = vm::resetConnection, enabled = vm.state.connection != ConnectionStatus.NOT_FOUND) { Text("Reset connection") }
        }
        Button(onClick = { pick.launch(arrayOf("image/x-fuji-raf", "application/octet-stream")) }, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(if (vm.state.raf == null) "Choose RAF" else "Change RAF")
        }
        if (vm.state.raf != null) {
            Text(
                if (vm.state.outputTree == null) "Pick a folder to save renders into" else "Renders will save to the chosen folder",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Recipes", style = MaterialTheme.typography.titleMedium)
        var collapsed by remember { mutableStateOf(setOf<RecipeCategory?>()) }
        val grouped = recipes.filterNot { it.archived }.groupBy { it.current.settings.category }
        LazyColumn(Modifier.weight(1f)) {
            (RecipeCategory.entries + listOf(null)).forEach { cat ->
                val group = grouped[cat] ?: return@forEach
                val expanded = cat !in collapsed
                val allChosen = group.all { it.id in vm.state.chosen }
                item(key = "header_$cat") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allChosen, { vm.setGroup(group.map { it.id }, !allChosen) })
                        Row(
                            Modifier.weight(1f).clickable { collapsed = if (expanded) collapsed + cat else collapsed - cat },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null)
                            Text("${cat?.label() ?: "No Category"} (${group.size})", style = MaterialTheme.typography.titleMedium)
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
            Button(
                enabled = vm.state.raf != null && vm.state.outputTree != null && vm.state.chosen.isNotEmpty(),
                onClick = vm::process,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Render selected recipes") }
        }
        vm.state.message?.let { Text(it) }
    }
}
