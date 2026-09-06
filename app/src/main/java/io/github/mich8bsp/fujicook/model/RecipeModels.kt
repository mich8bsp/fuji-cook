package io.github.mich8bsp.fujicook.model

enum class FilmSimulation {
    PROVIA, VELVIA, ASTIA, PRO_NEG_HI, PRO_NEG_STD,
    MONOCHROME, MONOCHROME_YE, MONOCHROME_R, MONOCHROME_G, SEPIA,
    CLASSIC_CHROME, ACROS, ACROS_YE, ACROS_R, ACROS_G,
    ETERNA, CLASSIC_NEGATIVE, ETERNA_BLEACH_BYPASS, NOSTALGIC_NEGATIVE, REALA_ACE,
}

enum class EffectStrength { OFF, WEAK, STRONG }
enum class GrainSize { SMALL, LARGE }
enum class WhiteBalance {
    AUTO, AUTO_WHITE_PRIORITY, AUTO_AMBIENCE_PRIORITY,
    DAYLIGHT, SHADE, INCANDESCENT,
    FLUORESCENT_1, FLUORESCENT_2, FLUORESCENT_3,
    UNDERWATER, TEMPERATURE,
    CUSTOM_1, CUSTOM_2, CUSTOM_3,
}
/** A tag in the user-editable vocabulary. [id] is stable and stored in recipe JSON; [name] is display-only. */
data class Tag(val id: String, val name: String, val group: String?, val color: Long, val sortOrder: Int)

/**
 * Built-in tags seeded on first run / on the v4→v5 migration. Ids match the names of the pre-v5
 * `RecipeTag` enum so existing recipe revision JSON needs no migration.
 */
val SEED_TAGS: List<Tag> = listOf(
    Tag("SUNNY", "Sunny", "Light", 0xFFF9A825, 0),
    Tag("OVERCAST", "Overcast", "Light", 0xFF455A64, 1),
    Tag("GOLDEN_HOUR", "Golden Hour", "Light", 0xFFEF6C00, 2),
    Tag("NIGHT", "Night", "Light", 0xFF1A237E, 3),
    Tag("INDOORS", "Indoors", "Light", 0xFF303F9F, 4),
    Tag("RAINY", "Rainy", "Light", 0xFF546E7A, 5),
    Tag("PORTRAIT", "Portrait", "Subject", 0xFFAD1457, 6),
    Tag("WILDLIFE", "Wildlife", "Subject", 0xFF6D4C41, 7),
    Tag("NATURE", "Nature", "Subject", 0xFF2E7D32, 8),
    Tag("STREET", "Street", "Subject", 0xFF37474F, 9),
    Tag("ARCHITECTURE", "Architecture", "Subject", 0xFF5D4037, 10),
    Tag("WARM", "Warm", "Style", 0xFFE65100, 11),
    Tag("COOL", "Cool", "Style", 0xFF0277BD, 12),
    Tag("BW", "B&W", "Style", 0xFF212121, 13),
    Tag("VIVID", "Vivid", "Style", 0xFF6A1B9A, 14),
    Tag("MUTED", "Muted", "Style", 0xFF757575, 15),
    Tag("DARK", "Dark", "Style", 0xFF263238, 16),
    Tag("NOSTALGIC", "Nostalgic", "Style", 0xFF8D6E63, 17),
    Tag("EXPERIMENTAL", "Experimental", "Style", 0xFF00BFA5, 18),
    Tag("SPRING", "Spring", "Season", 0xFF7CB342, 19),
    Tag("SUMMER", "Summer", "Season", 0xFFFBC02D, 20),
    Tag("AUTUMN", "Autumn", "Season", 0xFFD84315, 21),
    Tag("WINTER", "Winter", "Season", 0xFF4FC3F7, 22),
)

/** Colour choices offered when creating/editing a tag. */
val TAG_PALETTE: List<Long> = SEED_TAGS.map { it.color }.distinct()

/** Tags split into display groups, groups ordered by their first tag, ungrouped tags under "Other". */
fun List<Tag>.grouped(): List<Pair<String, List<Tag>>> =
    sortedBy { it.sortOrder }
        .groupBy { it.group ?: "Other" }
        .toList()

data class RecipeSettings(
    val filmSimulation: FilmSimulation,
    val tags: Set<String> = emptySet(),
    val monochromeWarmCool: Int? = null,
    val monochromeMagentaGreen: Int? = null,
    val grainStrength: EffectStrength? = null,
    val grainSize: GrainSize? = null,
    val colorChrome: EffectStrength? = null,
    val colorChromeBlue: EffectStrength? = null,
    val whiteBalance: WhiteBalance? = null,
    val whiteBalanceTemperature: Int? = null,
    val whiteBalanceRed: Int? = null,
    val whiteBalanceBlue: Int? = null,
    val dynamicRange: Int? = null,
    val highlightTone: Double? = null,
    val shadowTone: Double? = null,
    val color: Int? = null,
    val sharpness: Int? = null,
    val highIsoNoiseReduction: Int? = null,
    val clarity: Int? = null,
) {
    fun validate() {
        fun range(name: String, value: Int?, valid: IntRange) {
            require(value == null || value in valid) { "$name must be in ${valid.first}..${valid.last}" }
        }
        range("monochromeWarmCool", monochromeWarmCool, -18..18)
        range("monochromeMagentaGreen", monochromeMagentaGreen, -18..18)
        range("whiteBalanceTemperature", whiteBalanceTemperature, 2500..10000)
        range("whiteBalanceRed", whiteBalanceRed, -9..9)
        range("whiteBalanceBlue", whiteBalanceBlue, -9..9)
        require(dynamicRange == null || dynamicRange in setOf(0, 100, 200, 400)) { "dynamicRange must be 0 (Auto), 100, 200, or 400" }
        require(highlightTone == null || highlightTone in -2.0..4.0) { "highlightTone must be -2..4" }
        require(shadowTone == null || shadowTone in -2.0..4.0) { "shadowTone must be -2..4" }
        range("color", color, -4..4)
        range("sharpness", sharpness, -4..4)
        range("highIsoNoiseReduction", highIsoNoiseReduction, -4..4)
        range("clarity", clarity, -5..5)
        require((whiteBalance == WhiteBalance.TEMPERATURE) == (whiteBalanceTemperature != null)) {
            "whiteBalanceTemperature must be set if and only if white balance is TEMPERATURE"
        }
    }
}

fun FilmSimulation.isBlackAndWhite() = name.startsWith("MONOCHROME") || name.startsWith("ACROS") || this == FilmSimulation.SEPIA

fun formatDynamicRange(v: Int) = if (v == 0) "DR Auto" else "DR$v"

fun RecipeSettings.asCompleteRecipe() = copy(
    monochromeWarmCool = if (filmSimulation.isBlackAndWhite()) monochromeWarmCool ?: 0 else null,
    monochromeMagentaGreen = if (filmSimulation.isBlackAndWhite()) monochromeMagentaGreen ?: 0 else null,
    grainStrength = grainStrength ?: EffectStrength.OFF,
    grainSize = if ((grainStrength ?: EffectStrength.OFF) == EffectStrength.OFF) null else grainSize ?: GrainSize.SMALL,
    colorChrome = colorChrome ?: EffectStrength.OFF,
    colorChromeBlue = colorChromeBlue ?: EffectStrength.OFF,
    whiteBalance = whiteBalance ?: WhiteBalance.AUTO,
    whiteBalanceTemperature = if ((whiteBalance ?: WhiteBalance.AUTO) == WhiteBalance.TEMPERATURE) whiteBalanceTemperature else null,
    whiteBalanceRed = whiteBalanceRed ?: 0,
    whiteBalanceBlue = whiteBalanceBlue ?: 0,
    dynamicRange = dynamicRange ?: 100,
    highlightTone = highlightTone ?: 0.0,
    shadowTone = shadowTone ?: 0.0,
    color = if (filmSimulation.isBlackAndWhite()) null else color ?: 0,
    sharpness = sharpness ?: 0,
    highIsoNoiseReduction = highIsoNoiseReduction ?: 0,
    clarity = clarity ?: 0,
)

data class RecipeRevision(val id: String, val recipeId: String, val number: Int, val settings: RecipeSettings, val createdAt: Long)
data class Recipe(val id: String, val name: String, val archived: Boolean, val createdAt: Long, val updatedAt: Long, val current: RecipeRevision, val description: String = "")

data class ExtractedSettings(val settings: RecipeSettings, val make: String?, val existingRecipeTags: List<String> = emptyList())
enum class MatchStatus { MATCH, LOW_CONFIDENCE, AMBIGUOUS, NO_MATCH }
data class MatchCandidate(val recipe: Recipe, val revision: RecipeRevision, val confidence: Double, val differences: List<String>, val modifiedSummary: String? = null)
data class MatchResult(val status: MatchStatus, val candidates: List<MatchCandidate>)
