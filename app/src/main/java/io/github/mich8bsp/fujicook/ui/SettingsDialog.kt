package io.github.mich8bsp.fujicook.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mich8bsp.fujicook.model.*

private val toneValues = (0..12).map { -2.0 + it * 0.5 }

@Composable
fun SettingsDialog(initialName: String, initialDescription: String, initial: RecipeSettings, onDismiss: () -> Unit, onSave: (String, String, RecipeSettings) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var settings by remember { mutableStateOf(initial.asCompleteRecipe()) }
    var temperature by remember { mutableStateOf(initial.whiteBalanceTemperature?.toString() ?: "5000") }
    val temperatureValid = settings.whiteBalance != WhiteBalance.TEMPERATURE || temperature.toIntOrNull()?.let { it in 2500..10000 } == true
    RecipeEditorDialog(
        title = "Edit recipe", saveLabel = "Save", saveEnabled = name.isNotBlank() && temperatureValid,
        onDismiss = onDismiss,
        onSave = {
            val result = settings.copy(whiteBalanceTemperature = if (settings.whiteBalance == WhiteBalance.TEMPERATURE) temperature.toInt() else null)
            result.validate()
            onSave(name, description, result)
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        SettingsEditor(settings, temperature, { settings = it }, { temperature = it.filter(Char::isDigit) }, Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeEditorDialog(title: String, saveLabel: String, saveEnabled: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                    actions = { TextButton(onClick = onSave, enabled = saveEnabled) { Text(saveLabel) } },
                )
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), content = content)
            }
        }
    }
}

@Composable
internal fun SettingsEditor(settings: RecipeSettings, temperature: String, onSettingsChange: (RecipeSettings) -> Unit, onTemperatureChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        RequiredSelector("Film simulation", settings.filmSimulation, FilmSimulation.entries) { film ->
            onSettingsChange(
                settings.copy(
                    filmSimulation = film,
                    color = if (film.isBlackAndWhite()) null else settings.color ?: 0,
                    monochromeWarmCool = if (film.isBlackAndWhite()) settings.monochromeWarmCool ?: 0 else null,
                    monochromeMagentaGreen = if (film.isBlackAndWhite()) settings.monochromeMagentaGreen ?: 0 else null,
                ),
            )
        }
        TagSelector(settings.tags) { onSettingsChange(settings.copy(tags = it)) }
        RequiredSelector("Grain strength", settings.grainStrength ?: EffectStrength.OFF, EffectStrength.entries) { strength ->
            onSettingsChange(settings.copy(grainStrength = strength, grainSize = if (strength == EffectStrength.OFF) null else settings.grainSize ?: GrainSize.SMALL))
        }
        if (settings.grainStrength != EffectStrength.OFF) RequiredSelector("Grain size", settings.grainSize ?: GrainSize.SMALL, GrainSize.entries) { onSettingsChange(settings.copy(grainSize = it)) }
        RequiredSelector("Color chrome effect", settings.colorChrome ?: EffectStrength.OFF, EffectStrength.entries) { onSettingsChange(settings.copy(colorChrome = it)) }
        RequiredSelector("Color chrome FX blue", settings.colorChromeBlue ?: EffectStrength.OFF, EffectStrength.entries) { onSettingsChange(settings.copy(colorChromeBlue = it)) }
        RequiredSelector("WB type", settings.whiteBalance ?: WhiteBalance.AUTO, WhiteBalance.entries) { onSettingsChange(settings.copy(whiteBalance = it, whiteBalanceTemperature = null)) }
        if (settings.whiteBalance == WhiteBalance.TEMPERATURE) {
            OutlinedTextField(
                value = temperature,
                onValueChange = onTemperatureChange,
                label = { Text("WB temperature (2500–10000 K)") },
                isError = temperature.isNotEmpty() && temperature.toIntOrNull()?.let { it !in 2500..10000 } != false,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            )
        }
        StepperSelector("WB red", settings.whiteBalanceRed ?: 0, (-9..9).toList()) { onSettingsChange(settings.copy(whiteBalanceRed = it)) }
        StepperSelector("WB blue", settings.whiteBalanceBlue ?: 0, (-9..9).toList()) { onSettingsChange(settings.copy(whiteBalanceBlue = it)) }
        RequiredSelector("Dynamic range", settings.dynamicRange ?: 100, listOf(0, 100, 200, 400), format = ::formatDynamicRange) { onSettingsChange(settings.copy(dynamicRange = it)) }
        StepperSelector("Highlight", settings.highlightTone ?: 0.0, toneValues, format = ::formatNumber) { onSettingsChange(settings.copy(highlightTone = it)) }
        StepperSelector("Shadow", settings.shadowTone ?: 0.0, toneValues, format = ::formatNumber) { onSettingsChange(settings.copy(shadowTone = it)) }
        if (settings.filmSimulation.isBlackAndWhite()) {
            StepperSelector("WC (warm/cool)", settings.monochromeWarmCool ?: 0, (-18..18).toList()) { onSettingsChange(settings.copy(monochromeWarmCool = it)) }
            StepperSelector("MG (magenta/green)", settings.monochromeMagentaGreen ?: 0, (-18..18).toList()) { onSettingsChange(settings.copy(monochromeMagentaGreen = it)) }
        } else {
            StepperSelector("Color", settings.color ?: 0, (-4..4).toList()) { onSettingsChange(settings.copy(color = it)) }
        }
        StepperSelector("Sharpness", settings.sharpness ?: 0, (-4..4).toList()) { onSettingsChange(settings.copy(sharpness = it)) }
        StepperSelector("High ISO noise reduction", settings.highIsoNoiseReduction ?: 0, (-4..4).toList()) { onSettingsChange(settings.copy(highIsoNoiseReduction = it)) }
        StepperSelector("Clarity", settings.clarity ?: 0, (-5..5).toList()) { onSettingsChange(settings.copy(clarity = it)) }
    }
}

fun RecipeTag.color(): Color = when (this) {
    RecipeTag.SUNNY -> Color(0xFFF9A825)
    RecipeTag.OVERCAST -> Color(0xFF455A64)
    RecipeTag.GOLDEN_HOUR -> Color(0xFFEF6C00)
    RecipeTag.NIGHT -> Color(0xFF1A237E)
    RecipeTag.INDOORS -> Color(0xFF303F9F)
    RecipeTag.RAINY -> Color(0xFF546E7A)
    RecipeTag.PORTRAIT -> Color(0xFFAD1457)
    RecipeTag.WILDLIFE -> Color(0xFF6D4C41)
    RecipeTag.NATURE -> Color(0xFF2E7D32)
    RecipeTag.STREET -> Color(0xFF37474F)
    RecipeTag.ARCHITECTURE -> Color(0xFF5D4037)
    RecipeTag.WARM -> Color(0xFFE65100)
    RecipeTag.COOL -> Color(0xFF0277BD)
    RecipeTag.BW -> Color(0xFF212121)
    RecipeTag.VIVID -> Color(0xFF6A1B9A)
    RecipeTag.MUTED -> Color(0xFF757575)
    RecipeTag.DARK -> Color(0xFF263238)
    RecipeTag.NOSTALGIC -> Color(0xFF8D6E63)
    RecipeTag.EXPERIMENTAL -> Color(0xFF00BFA5)
}

@Composable
fun TagChip(tag: RecipeTag, modifier: Modifier = Modifier) {
    Surface(color = tag.color(), shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(tag.label(), style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
internal fun TagSelector(selected: Set<RecipeTag>, onChange: (Set<RecipeTag>) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("Tags", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 4.dp))
        TagGroup.entries.forEach { group ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                RecipeTag.entries.filter { it.group() == group }.forEach { tag ->
                    FilterChip(selected = tag in selected, onClick = { onChange(if (tag in selected) selected - tag else selected + tag) }, label = { Text(tag.label()) })
                }
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun <T> RequiredSelector(label: String, value: T, values: List<T>, format: (T) -> String = { formatValue(it) }, onValue: (T) -> Unit) {
    Selector(label, format(value), values.map { format(it) to { onValue(it) } })
}

@Composable
private fun <T> StepperSelector(label: String, value: T, values: List<T>, format: (T) -> String = { formatValue(it) }, onValue: (T) -> Unit) {
    val index = values.indexOf(value)
    StepperRow(label, format(value), index > 0, index in 0 until values.lastIndex, { onValue(values[index - 1]) }, { onValue(values[index + 1]) })
}

@Composable
private fun StepperRow(label: String, displayedValue: String, canDecrease: Boolean, canIncrease: Boolean, decrease: () -> Unit, increase: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        FilledTonalIconButton(onClick = decrease, enabled = canDecrease) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(displayedValue, modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        FilledTonalIconButton(onClick = increase, enabled = canIncrease) { Icon(Icons.Default.Add, "Increase value") }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Selector(label: String, selected: String, choices: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Surface(Modifier.fillMaxWidth().clickable { focusManager.clearFocus(); expanded = true }, color = MaterialTheme.colorScheme.background) {
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(selected, color = if (selected == "Inherit") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).navigationBarsPadding()) {
                items(choices, key = { it.first }) { (text, select) ->
                    ListItem(
                        headlineContent = { Text(text) },
                        trailingContent = { RadioButton(text == selected, null) },
                        modifier = Modifier.clickable { select(); expanded = false },
                    )
                }
            }
        }
    }
}

private fun formatValue(value: Any?): String = when (value) {
    is Enum<*> -> value.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
    else -> value.toString()
}

private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
