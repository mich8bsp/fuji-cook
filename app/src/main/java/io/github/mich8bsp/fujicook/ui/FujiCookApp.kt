package io.github.mich8bsp.fujicook.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mich8bsp.fujicook.FujiCookApplication
import io.github.mich8bsp.fujicook.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class Destination(val label: String) { RECIPES("Recipes"), TAGGER("JPEG Tagger"), RAW("RAW Compare") }

@Composable fun FujiCookApp() {
    var destination by rememberSaveable { mutableStateOf(Destination.RECIPES) }
    Scaffold(bottomBar = { NavigationBar { Destination.entries.forEach { d -> NavigationBarItem(selected = d == destination, onClick = { destination = d }, icon = { Icon(when(d) { Destination.RECIPES -> Icons.Default.List; Destination.TAGGER -> Icons.Default.Search; Destination.RAW -> Icons.Default.Settings }, null) }, label = { Text(d.label) }) } } }) { padding ->
        Box(Modifier.padding(padding)) { when(destination) { Destination.RECIPES -> RecipeScreen(); Destination.TAGGER -> TaggerScreen(); Destination.RAW -> RawScreen() } }
    }
}

class RecipesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as FujiCookApplication).recipes
    val recipes = repo.recipes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var error by mutableStateOf<String?>(null); private set
    fun create(name: String, settings: RecipeSettings) = viewModelScope.launch { runCatching { repo.create(name, settings) }.onFailure { error = it.message } }
    fun archive(id: String, value: Boolean) = viewModelScope.launch { repo.archive(id, value) }
    fun revise(recipe: Recipe, settings: RecipeSettings) = viewModelScope.launch { runCatching { repo.revise(recipe.id, settings) }.onFailure { error = it.message } }
    fun delete(recipe: Recipe) = viewModelScope.launch { runCatching { repo.delete(recipe.id) }.onFailure { error = it.message } }
    fun clearError() { error = null }
}

@Composable fun RecipeScreen(vm: RecipesViewModel = viewModel()) {
    val recipes by vm.recipes.collectAsStateWithLifecycle(); var adding by remember { mutableStateOf(false) }; var editing by remember { mutableStateOf<Recipe?>(null) }; var showArchived by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf<Recipe?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Recipes", style = MaterialTheme.typography.headlineMedium); FilledTonalButton(onClick={adding=true}) { Icon(Icons.Default.Add,null); Text("New") } }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(showArchived, { showArchived=it }); Text("Show archived") }
        val visible = recipes.filter { showArchived || !it.archived }
        if (visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment=androidx.compose.ui.Alignment.Center) { Text("Your library is empty. Create or import a recipe.") }
        else visible.forEach { recipe -> Card(onClick={editing=recipe},modifier=Modifier.fillMaxWidth().padding(vertical=5.dp)) { Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) { Column { Text(recipe.name, style=MaterialTheme.typography.titleMedium); Text("${recipe.current.settings.filmSimulation.name.replace('_',' ')}") }; Row { TextButton(onClick={ vm.archive(recipe.id, !recipe.archived) }) { Text(if(recipe.archived) "Restore" else "Archive") }; if (recipe.archived) TextButton(onClick={ deleting=recipe }) { Text("Delete", color=MaterialTheme.colorScheme.error) } } } } }
    }
    if (adding) RecipeDialog(onDismiss={adding=false}, onSave={name,settings -> vm.create(name,settings); adding=false})
    editing?.let { recipe -> SettingsDialog(recipe.current.settings,onDismiss={editing=null},onSave={settings->vm.revise(recipe,settings);editing=null}) }
    deleting?.let { recipe -> AlertDialog(onDismissRequest={deleting=null},title={Text("Permanently delete recipe?")},text={Text("Delete \"" + recipe.name + "\"? This cannot be undone.")},confirmButton={Button(onClick={vm.delete(recipe);deleting=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("Delete permanently")}},dismissButton={TextButton(onClick={deleting=null}){Text("Cancel")}}) }
    vm.error?.let { AlertDialog(onDismissRequest=vm::clearError, confirmButton={TextButton(onClick=vm::clearError){Text("OK")}}, title={Text("Could not save")}, text={Text(it)}) }
}

@Composable private fun RecipeDialog(onDismiss:()->Unit,onSave:(String,RecipeSettings)->Unit) {
    var name by remember { mutableStateOf("") }; var settings by remember { mutableStateOf(RecipeSettings(FilmSimulation.PROVIA).asCompleteRecipe()) }; var temperature by remember { mutableStateOf("5000") }
    val temperatureValid = settings.whiteBalance != WhiteBalance.TEMPERATURE || temperature.toIntOrNull()?.let { it in 2500..10000 } == true
    RecipeEditorDialog(
        title="New X-T5 recipe", saveLabel="Save", saveEnabled=name.isNotBlank() && temperatureValid,
        onDismiss=onDismiss,
        onSave={onSave(name,settings.copy(whiteBalanceTemperature=if(settings.whiteBalance==WhiteBalance.TEMPERATURE) temperature.toInt() else null))},
    ) {
        OutlinedTextField(name,{name=it},label={Text("Name")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(vertical=8.dp))
        SettingsEditor(settings,temperature,{settings=it},{temperature=it.filter(Char::isDigit)},Modifier.weight(1f))
    }
}

@Composable fun TaggerScreen() = JpegTaggerScreen()
@Composable fun RawScreen() = RawCompareScreen()
@Composable private fun EmptyTool(icon: androidx.compose.ui.graphics.vector.ImageVector,title:String,body:String,action:String) { Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally){Icon(icon,null,Modifier.size(64.dp));Spacer(Modifier.height(16.dp));Text(title,style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.height(8.dp));Text(body);Spacer(Modifier.height(20.dp));Button(onClick={}){Text(action)}} }
