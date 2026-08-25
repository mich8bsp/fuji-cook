package io.github.mich8bsp.fujicook.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mich8bsp.fujicook.FujiCookApplication
import io.github.mich8bsp.fujicook.metadata.*
import io.github.mich8bsp.fujicook.model.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*

data class TagState(
    val jpeg: ParsedJpeg? = null,
    val extracted: ExtractedSettings? = null,
    val match: MatchResult? = null,
    val selected: MatchCandidate? = null,
    val fileName: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class TaggerViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as FujiCookApplication).recipes
    var state by mutableStateOf(TagState()); private set

    fun load(uri: Uri) = viewModelScope.launch {
        state = TagState(busy = true)
        runCatching {
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                val data = resolver.openInputStream(uri)!!.use { it.readBytes() }
                val jpeg = JpegSegments.read(ByteArrayInputStream(data))
                val ex = FujifilmMakerNote.extract(jpeg)
                val active = RecipeMatcher.match(ex.settings, repo.matchableRevisions())
                val match = if (active.status == MatchStatus.NO_MATCH) RecipeMatcher.match(ex.settings, repo.matchableRevisions(includeArchived = true)) else active
                Triple(jpeg, ex, match) to name
            }
        }.onSuccess { (v, name) -> state = TagState(v.first, v.second, v.third, v.third.candidates.firstOrNull(), fileName = name) }
            .onFailure { state = TagState(message = it.message) }
    }

    fun select(c: MatchCandidate) {
        state = state.copy(selected = c)
    }

    fun save(uri: Uri) = viewModelScope.launch {
        val jpeg = state.jpeg ?: return@launch
        val c = state.selected ?: return@launch
        state = state.copy(busy = true)
        runCatching {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "w")!!.use { JpegSegments.write(RecipeMetadata.tag(jpeg, c.recipe.name, c.modifiedSummary), it) }
            }
        }.onSuccess { state = state.copy(busy = false, message = "Tagged copy saved") }
            .onFailure { state = state.copy(busy = false, message = it.message) }
    }
}

@Composable
fun JpegTaggerScreen(vm: TaggerViewModel = viewModel()) {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::load) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri -> uri?.let(vm::save) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val photo = vm.state.extracted?.settings

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Recipe Matcher", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { pick.launch(arrayOf("image/jpeg")) }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Choose JPEG") }
        if (vm.state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.state.extracted?.let {
            Text("Extracted: " + formatComparisonValue(it.settings.filmSimulation))
            if (it.existingRecipeTags.isNotEmpty()) Text("Existing: " + it.existingRecipeTags.joinToString())
        }
        vm.state.match?.let { match ->
            Text("Result: " + match.status, style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f)) {
                if (match.candidates.isEmpty() && photo != null) item { PhotoParameters(photo) }
                items(match.candidates.take(20), key = { it.revision.id }) { candidate ->
                    val isExpanded = candidate.revision.id in expanded
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(candidate.recipe.name + if (candidate.recipe.archived) " (archived)" else "", style = MaterialTheme.typography.titleMedium)
                                    Text((candidate.confidence * 100).toInt().toString() + "% · " + candidate.differences.size + " difference(s)")
                                    candidate.modifiedSummary?.let { Text("Will tag as modified: $it", style = MaterialTheme.typography.bodySmall) }
                                }
                                RadioButton(vm.state.selected?.revision?.id == candidate.revision.id, { vm.select(candidate) })
                            }
                            TextButton(onClick = { expanded = if (isExpanded) expanded - candidate.revision.id else expanded + candidate.revision.id }) {
                                Text(if (isExpanded) "Hide parameter details" else "Show parameter details")
                            }
                            if (isExpanded && photo != null) ParameterComparison(photo, candidate.revision.settings)
                        }
                    }
                }
            }
        }
        vm.state.selected?.let { c ->
            Button(onClick = { save.launch(suggestedFileName(vm.state.fileName, c.recipe.name)) }, modifier = Modifier.fillMaxWidth()) { Text("Save tagged copy") }
        }
        vm.state.message?.let { Text(it, Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun PhotoParameters(photo: RecipeSettings) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Photo parameters", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
                Text("Parameter", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("Photo value", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            }
            comparisonRows(photo, photo).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(row.label, Modifier.weight(1f))
                    Text(formatComparisonValue(row.photo), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ParameterComparison(photo: RecipeSettings, recipe: RecipeSettings) {
    val rows = comparisonRows(photo, recipe)
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Parameter", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
        Text("Photo", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        Text("Recipe", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
    }
    rows.forEach { row ->
        val mismatch = row.photo != row.recipe
        val color = if (mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(row.label, Modifier.weight(1.2f), color = color)
            Text(formatComparisonValue(row.photo), Modifier.weight(1f), color = color)
            Text(formatComparisonValue(row.recipe), Modifier.weight(1f), color = color)
        }
    }
}

private data class ComparisonRow(val label: String, val photo: Any?, val recipe: Any?)

private fun comparisonRows(photo: RecipeSettings, recipe: RecipeSettings): List<ComparisonRow> = buildList {
    add(ComparisonRow("Film simulation", photo.filmSimulation, recipe.filmSimulation))
    add(ComparisonRow("Grain strength", photo.grainStrength, recipe.grainStrength))
    if (photo.grainStrength != EffectStrength.OFF || recipe.grainStrength != EffectStrength.OFF) add(ComparisonRow("Grain size", photo.grainSize, recipe.grainSize))
    add(ComparisonRow("Color Chrome FX", photo.colorChrome, recipe.colorChrome))
    add(ComparisonRow("Color Chrome FX Blue", photo.colorChromeBlue, recipe.colorChromeBlue))
    add(ComparisonRow("White balance", photo.whiteBalance, recipe.whiteBalance))
    if (photo.whiteBalance == WhiteBalance.TEMPERATURE || recipe.whiteBalance == WhiteBalance.TEMPERATURE) add(ComparisonRow("WB temperature", photo.whiteBalanceTemperature, recipe.whiteBalanceTemperature))
    add(ComparisonRow("WB red", photo.whiteBalanceRed, recipe.whiteBalanceRed))
    add(ComparisonRow("WB blue", photo.whiteBalanceBlue, recipe.whiteBalanceBlue))
    add(ComparisonRow("Dynamic range", photo.dynamicRange?.let(::formatDynamicRange), recipe.dynamicRange?.let(::formatDynamicRange)))
    add(ComparisonRow("Highlight", photo.highlightTone, recipe.highlightTone))
    add(ComparisonRow("Shadow", photo.shadowTone, recipe.shadowTone))
    if (!photo.filmSimulation.isBlackAndWhite() && !recipe.filmSimulation.isBlackAndWhite()) add(ComparisonRow("Color", photo.color, recipe.color))
    if (photo.filmSimulation.isBlackAndWhite() || recipe.filmSimulation.isBlackAndWhite()) {
        add(ComparisonRow("WC (warm/cool)", photo.monochromeWarmCool, recipe.monochromeWarmCool))
        add(ComparisonRow("MG (magenta/green)", photo.monochromeMagentaGreen, recipe.monochromeMagentaGreen))
    }
    add(ComparisonRow("Sharpness", photo.sharpness, recipe.sharpness))
    add(ComparisonRow("High ISO NR", photo.highIsoNoiseReduction, recipe.highIsoNoiseReduction))
    add(ComparisonRow("Clarity", photo.clarity, recipe.clarity))
}

internal fun suggestedFileName(originalName: String?, recipeName: String): String {
    val base = (originalName ?: "photo").substringBeforeLast('.')
    val suffix = recipeName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    return "${base}_$suffix.jpg"
}

private fun formatComparisonValue(value: Any?): String = when (value) {
    null -> "Not available"
    is Enum<*> -> value.name.replace("_", " ").lowercase().replaceFirstChar(Char::uppercase)
    is Double -> if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    else -> value.toString()
}
