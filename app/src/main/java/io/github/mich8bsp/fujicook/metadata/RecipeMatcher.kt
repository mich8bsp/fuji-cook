package io.github.mich8bsp.fujicook.metadata

import io.github.mich8bsp.fujicook.model.*

object RecipeMatcher {
    fun match(photo: RecipeSettings, recipes: List<Pair<Recipe, RecipeRevision>>): MatchResult {
        val scored = recipes.mapNotNull { (recipe, revision) ->
            val settings = revision.settings
            if (!identityMatches(photo, settings)) return@mapNotNull null
            val differences = softDifferences(photo, settings)
            val compared = (strictValues(photo, settings) + softValues(photo, settings)).count { (photoValue, recipeValue) -> photoValue != null && recipeValue != null }
            val confidence = if (compared == 0) 1.0 else (compared - differences.size).toDouble() / compared
            MatchCandidate(recipe, revision, confidence, differences, modifiedSummary(photo, settings))
        }.sortedByDescending { it.confidence }

        if (scored.isEmpty()) return MatchResult(MatchStatus.NO_MATCH, emptyList())
        val best = scored.first()
        val tied = scored.drop(1).firstOrNull()?.confidence == best.confidence
        val status = when {
            tied -> MatchStatus.AMBIGUOUS
            best.confidence < .75 -> MatchStatus.LOW_CONFIDENCE
            else -> MatchStatus.MATCH
        }
        return MatchResult(status, scored)
    }

    private fun identityMatches(photo: RecipeSettings, recipe: RecipeSettings): Boolean =
        strictValues(photo, recipe).all { (photoValue, recipeValue) ->
            photoValue == null || recipeValue == null || photoValue == recipeValue
        }

    private fun strictValues(photo: RecipeSettings, recipe: RecipeSettings): List<Pair<Any?, Any?>> = listOf(
        photo.filmSimulation to recipe.filmSimulation,
        photo.colorChrome to recipe.colorChrome,
        photo.colorChromeBlue to recipe.colorChromeBlue,
        photo.whiteBalance to recipe.whiteBalance,
        photo.whiteBalanceTemperature to recipe.whiteBalanceTemperature,
        photo.whiteBalanceRed to recipe.whiteBalanceRed,
        photo.whiteBalanceBlue to recipe.whiteBalanceBlue,
        photo.highlightTone to recipe.highlightTone,
        photo.shadowTone to recipe.shadowTone,
        photo.color to recipe.color,
        photo.sharpness to recipe.sharpness,
        photo.highIsoNoiseReduction to recipe.highIsoNoiseReduction,
    )

    private fun softValues(photo: RecipeSettings, recipe: RecipeSettings): List<Pair<Any?, Any?>> = listOf(
        photo.grainStrength to recipe.grainStrength,
        photo.grainSize to recipe.grainSize,
        photo.dynamicRange to recipe.dynamicRange,
        photo.clarity to recipe.clarity,
    )

    private fun softDifferences(photo: RecipeSettings, recipe: RecipeSettings): List<String> {
        val values = listOf(
            Triple("grainStrength", photo.grainStrength, recipe.grainStrength),
            Triple("grainSize", photo.grainSize, recipe.grainSize),
            Triple("dynamicRange", photo.dynamicRange, recipe.dynamicRange),
            Triple("clarity", photo.clarity, recipe.clarity),
        )
        return values.mapNotNull { (name, photoValue, recipeValue) ->
            if (photoValue != null && recipeValue != null && photoValue != recipeValue) "$name: photo $photoValue, recipe $recipeValue" else null
        }
    }

    private fun modifiedSummary(photo: RecipeSettings, recipe: RecipeSettings): String? {
        val parts = mutableListOf<String>()
        if (photo.dynamicRange != null && recipe.dynamicRange != null && photo.dynamicRange != recipe.dynamicRange) {
            parts += formatDynamicRange(photo.dynamicRange)
        }
        if (photo.clarity != null && recipe.clarity != null && photo.clarity != recipe.clarity) {
            parts += "Clarity ${photo.clarity}"
        }
        val grainChanged = (photo.grainStrength != null && recipe.grainStrength != null && photo.grainStrength != recipe.grainStrength) ||
            (photo.grainSize != null && recipe.grainSize != null && photo.grainSize != recipe.grainSize)
        if (grainChanged && photo.grainStrength != null) {
            parts += if (photo.grainStrength == EffectStrength.OFF) "Grain off"
            else "Grain " + photo.grainStrength.name.lowercase() + (photo.grainSize?.let { " " + it.name.lowercase() } ?: "")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}
