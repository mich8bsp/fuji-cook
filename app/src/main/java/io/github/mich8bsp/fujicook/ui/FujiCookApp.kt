package io.github.mich8bsp.fujicook.ui

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mich8bsp.fujicook.FujiCookApplication
import io.github.mich8bsp.fujicook.data.RecipeJson
import io.github.mich8bsp.fujicook.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class Destination(val label: String) { RECIPES("Recipes"), MATCHER("Recipe Matcher"), BATCH_MATCHER("Batch Matcher"), RENDER("Recipe Render") }

@Composable
fun FujiCookApp() {
    var destination by rememberSaveable { mutableStateOf(Destination.RECIPES) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = d == destination,
                        onClick = { destination = d },
                        icon = { Icon(when (d) { Destination.RECIPES -> Icons.Default.List; Destination.MATCHER -> Icons.Default.Search; Destination.BATCH_MATCHER -> Icons.Default.CheckCircle; Destination.RENDER -> Icons.Default.Settings }, null) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (destination) {
                Destination.RECIPES -> RecipeScreen()
                Destination.MATCHER -> RecipeMatcherScreen()
                Destination.BATCH_MATCHER -> BatchMatcherScreen()
                Destination.RENDER -> RecipeRenderScreen()
            }
        }
    }
}

class RecipesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as FujiCookApplication).recipes
    val recipes = repo.recipes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tags = (app as FujiCookApplication).tags.tags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var error by mutableStateOf<String?>(null); private set

    fun create(name: String, settings: RecipeSettings) = viewModelScope.launch { runCatching { repo.create(name, settings) }.onFailure { error = it.message } }
    fun archive(id: String, value: Boolean) = viewModelScope.launch { repo.archive(id, value) }
    fun revise(recipe: Recipe, name: String, description: String, settings: RecipeSettings) = viewModelScope.launch {
        runCatching {
            if (name != recipe.name) repo.rename(recipe.id, name)
            if (description != recipe.description) repo.setDescription(recipe.id, description)
            repo.revise(recipe.id, settings)
        }.onFailure { error = it.message }
    }
    fun delete(recipe: Recipe) = viewModelScope.launch { runCatching { repo.delete(recipe.id) }.onFailure { error = it.message } }
    fun clearError() { error = null }

    fun export(uri: android.net.Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(RecipeJson.exportAll(recipes.value).toByteArray()) }
        }.onFailure { error = it.message }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeScreen(vm: RecipesViewModel = viewModel()) {
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Recipe?>(null) }
    var managingTags by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Recipe?>(null) }
    var collapsed by remember { mutableStateOf(FilmSimulation.entries.toSet()) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recipes") },
                actions = {
                    TextButton(onClick = { managingTags = true }) { Text("Tags") }
                    IconButton(onClick = { exportLauncher.launch("fuji-cook-recipes.json") }) { Icon(Icons.Default.Share, contentDescription = "Export") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New recipe") })
        },
    ) { padding ->
        val visible = recipes.filter { showArchived || !it.archived }
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterChip(
                selected = showArchived,
                onClick = { showArchived = !showArchived },
                label = { Text("Show archived") },
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Your library is empty. Create or import a recipe.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                val grouped = visible.groupBy { it.current.settings.filmSimulation }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilmSimulation.entries.forEach { sim ->
                        val group = grouped[sim] ?: return@forEach
                        val expanded = sim !in collapsed
                        item(key = "header_$sim") {
                            FilmSimGroupHeader(sim, group.size, expanded, onToggle = { collapsed = if (expanded) collapsed + sim else collapsed - sim })
                        }
                        if (expanded) {
                            items(group, key = { it.id }) { recipe ->
                                RecipeListItem(
                                    recipe = recipe,
                                    tags = tags,
                                    onClick = { editing = recipe },
                                    onArchiveToggle = { vm.archive(recipe.id, !recipe.archived) },
                                    onDelete = { deleting = recipe },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) RecipeDialog(tags, onDismiss = { adding = false }, onSave = { name, settings -> vm.create(name, settings); adding = false })
    if (managingTags) TagManagerScreen(onDismiss = { managingTags = false })
    editing?.let { recipe -> SettingsDialog(recipe.name, recipe.description, recipe.current.settings, tags, onDismiss = { editing = null }, onSave = { name, description, settings -> vm.revise(recipe, name, description, settings); editing = null }) }
    deleting?.let { recipe ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Permanently delete recipe?") },
            text = { Text("Delete \"" + recipe.name + "\"? This cannot be undone.") },
            confirmButton = { Button(onClick = { vm.delete(recipe); deleting = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete permanently") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    vm.error?.let { AlertDialog(onDismissRequest = vm::clearError, confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } }, title = { Text("Could not save") }, text = { Text(it) }) }
}

@Composable
internal fun FilmSimGroupHeader(sim: FilmSimulation, count: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        onClick = onToggle,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null)
            Text(
                sim.name.replace('_', ' '),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun RecipeListItem(
    recipe: Recipe,
    tags: List<Tag>,
    onClick: () -> Unit,
    onArchiveToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (recipe.archived) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(recipe.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (recipe.archived) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text("Archived", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                val recipeTags = tags.filter { it.id in recipe.current.settings.tags }
                if (recipeTags.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        recipeTags.forEach { tag -> TagChip(tag) }
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Recipe actions") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (recipe.archived) "Restore" else "Archive") },
                        onClick = { menuOpen = false; onArchiveToggle() },
                    )
                    if (recipe.archived) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeDialog(allTags: List<Tag>, onDismiss: () -> Unit, onSave: (String, RecipeSettings) -> Unit) {
    var name by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(RecipeSettings(FilmSimulation.PROVIA).asCompleteRecipe()) }
    var temperature by remember { mutableStateOf("5000") }
    val temperatureValid = settings.whiteBalance != WhiteBalance.TEMPERATURE || temperature.toIntOrNull()?.let { it in 2500..10000 } == true
    RecipeEditorDialog(
        title = "New recipe", saveLabel = "Save", saveEnabled = name.isNotBlank() && temperatureValid,
        onDismiss = onDismiss,
        onSave = { onSave(name, settings.copy(whiteBalanceTemperature = if (settings.whiteBalance == WhiteBalance.TEMPERATURE) temperature.toInt() else null)) },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        SettingsEditor(settings, temperature, allTags, { settings = it }, { temperature = it.filter(Char::isDigit) }, Modifier.weight(1f))
    }
}
