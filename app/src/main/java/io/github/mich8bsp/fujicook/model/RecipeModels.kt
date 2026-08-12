package io.github.mich8bsp.fujicook.model

enum class FilmSimulation { PROVIA, VELVIA, ASTIA, PRO_NEG_HI, PRO_NEG_STD, MONOCHROME, MONOCHROME_YE, MONOCHROME_R, MONOCHROME_G, SEPIA, CLASSIC_CHROME, ACROS, ACROS_YE, ACROS_R, ACROS_G, ETERNA, CLASSIC_NEGATIVE, ETERNA_BLEACH_BYPASS, NOSTALGIC_NEGATIVE, REALA_ACE }
enum class EffectStrength { OFF, WEAK, STRONG }
enum class GrainSize { SMALL, LARGE }
enum class WhiteBalance { AUTO, AUTO_WHITE_PRIORITY, AUTO_AMBIENCE_PRIORITY, DAYLIGHT, SHADE, INCANDESCENT, FLUORESCENT_1, FLUORESCENT_2, FLUORESCENT_3, UNDERWATER, TEMPERATURE, CUSTOM_1, CUSTOM_2, CUSTOM_3 }
enum class ColorSpace { SRGB, ADOBE_RGB }

data class RecipeSettings(
    val filmSimulation: FilmSimulation,
    val monochromeWarmCool: Int? = null,
    val monochromeMagentaGreen: Int? = null,
    val grainStrength: EffectStrength? = null,
    val grainSize: GrainSize? = null,
    val colorChrome: EffectStrength? = null,
    val colorChromeBlue: EffectStrength? = null,
    val smoothSkin: EffectStrength? = null,
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
    val colorSpace: ColorSpace? = null,
) {
    fun validate() {
        fun range(name: String, value: Int?, valid: IntRange) { require(value == null || value in valid) { "$name must be in ${valid.first}..${valid.last}" } }
        range("monochromeWarmCool", monochromeWarmCool, -18..18)
        range("monochromeMagentaGreen", monochromeMagentaGreen, -18..18)
        range("whiteBalanceTemperature", whiteBalanceTemperature, 2500..10000)
        range("whiteBalanceRed", whiteBalanceRed, -9..9); range("whiteBalanceBlue", whiteBalanceBlue, -9..9)
        require(dynamicRange == null || dynamicRange in setOf(100, 200, 400)) { "dynamicRange must be 100, 200, or 400" }
        require(highlightTone == null || highlightTone in -2.0..4.0) { "highlightTone must be -2..4" }
        require(shadowTone == null || shadowTone in -2.0..4.0) { "shadowTone must be -2..4" }
        range("color", color, -4..4); range("sharpness", sharpness, -4..4)
        range("highIsoNoiseReduction", highIsoNoiseReduction, -4..4); range("clarity", clarity, -5..5)
        require(whiteBalance == WhiteBalance.TEMPERATURE || whiteBalanceTemperature == null) { "Temperature requires TEMPERATURE white balance" }
    }
}

fun FilmSimulation.isBlackAndWhite() = name.startsWith("MONOCHROME") || name.startsWith("ACROS") || this == FilmSimulation.SEPIA

fun RecipeSettings.asCompleteRecipe() = copy(
    monochromeWarmCool = null,
    monochromeMagentaGreen = null,
    grainStrength = grainStrength ?: EffectStrength.OFF,
    grainSize = if ((grainStrength ?: EffectStrength.OFF) == EffectStrength.OFF) null else grainSize ?: GrainSize.SMALL,
    colorChrome = colorChrome ?: EffectStrength.OFF,
    colorChromeBlue = colorChromeBlue ?: EffectStrength.OFF,
    smoothSkin = null,
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
    colorSpace = null,
)

data class RecipeRevision(val id: String, val recipeId: String, val number: Int, val settings: RecipeSettings, val createdAt: Long)
data class Recipe(val id: String, val name: String, val archived: Boolean, val createdAt: Long, val updatedAt: Long, val current: RecipeRevision)

data class ExtractedSettings(val settings: RecipeSettings, val make: String?, val existingRecipeTags: List<String> = emptyList())
enum class MatchStatus { MATCH, LOW_CONFIDENCE, AMBIGUOUS, NO_MATCH }
data class MatchCandidate(val recipe: Recipe, val revision: RecipeRevision, val score: Double, val confidence: Double, val differences: List<String>)
data class MatchResult(val status: MatchStatus, val candidates: List<MatchCandidate>)
