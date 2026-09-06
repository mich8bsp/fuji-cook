package io.github.mich8bsp.fujicook.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mich8bsp.fujicook.FujiCookApplication
import io.github.mich8bsp.fujicook.model.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as FujiCookApplication).tags
    val tags = repo.tags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recipes = (app as FujiCookApplication).recipes.recipes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var error by mutableStateOf<String?>(null); private set

    fun add(name: String, group: String?, color: Long) = viewModelScope.launch { runCatching { repo.add(name, group, color) }.onFailure { error = it.message } }
    fun update(id: String, name: String, group: String?, color: Long) = viewModelScope.launch { runCatching { repo.update(id, name, group, color) }.onFailure { error = it.message } }
    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun clearError() { error = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagerScreen(onDismiss: () -> Unit, vm: TagsViewModel = viewModel()) {
    val tags by vm.tags.collectAsStateWithLifecycle()
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Tag?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Tag?>(null) }
    val groups = remember(tags) { tags.mapNotNull { it.group }.distinct() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(
                    title = { Text("Tags") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                    actions = { TextButton(onClick = { creating = true }) { Text("New tag") } },
                )
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    tags.grouped().forEach { (group, groupTags) ->
                        item(key = "header_$group") {
                            Text(group, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        items(groupTags, key = { it.id }) { tag ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(16.dp).background(Color(tag.color), CircleShape))
                                Spacer(Modifier.width(12.dp))
                                Text(tag.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { editing = tag }) { Text("Edit") }
                                TextButton(onClick = { deleting = tag }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) TagEditDialog(null, groups, { creating = false }) { name, group, color -> vm.add(name, group, color); creating = false }
    editing?.let { tag ->
        TagEditDialog(tag, groups, { editing = null }) { name, group, color -> vm.update(tag.id, name, group, color); editing = null }
    }
    deleting?.let { tag ->
        val count = recipes.count { tag.id in it.current.settings.tags }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete tag?") },
            text = { Text("Delete \"${tag.name}\"?" + if (count > 0) " It is used by $count recipe(s) and will be removed from them." else "") },
            confirmButton = {
                Button(onClick = { vm.delete(tag.id); deleting = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    vm.error?.let { AlertDialog(onDismissRequest = vm::clearError, confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }, title = { Text("Could not save") }, text = { Text(it) }) }
}

@Composable
private fun TagEditDialog(tag: Tag?, groups: List<String>, onDismiss: () -> Unit, onSave: (String, String?, Long) -> Unit) {
    var name by remember { mutableStateOf(tag?.name ?: "") }
    var group by remember { mutableStateOf(tag?.group ?: "") }
    var color by remember { mutableStateOf(tag?.color ?: TAG_PALETTE.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tag == null) "New tag" else "Edit tag") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(group, { group = it }, label = { Text("Group (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (groups.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        groups.forEach { g -> FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) }) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Colour", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TAG_PALETTE.forEach { c ->
                        val swatch = Modifier.size(30.dp).background(Color(c), CircleShape).clickable { color = c }
                        Box(if (c == color) swatch.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else swatch)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, group, color) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
