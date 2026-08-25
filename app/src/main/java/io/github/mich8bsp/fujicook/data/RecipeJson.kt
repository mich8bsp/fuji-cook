package io.github.mich8bsp.fujicook.data

import io.github.mich8bsp.fujicook.model.*
import org.json.JSONArray
import org.json.JSONObject

object RecipeJson {
    private val keys = setOf(
        "filmSimulation", "tags", "monochromeWarmCool", "monochromeMagentaGreen", "grainStrength", "grainSize",
        "colorChrome", "colorChromeBlue", "whiteBalance", "whiteBalanceTemperature",
        "whiteBalanceRed", "whiteBalanceBlue", "dynamicRange", "highlightTone", "shadowTone",
        "color", "sharpness", "highIsoNoiseReduction", "clarity",
    )

    fun settings(s: RecipeSettings) = JSONObject().apply {
        put("filmSimulation", s.filmSimulation.name)
        put("tags", JSONArray(s.tags.map { it.name }))
        putOpt("monochromeWarmCool", s.monochromeWarmCool)
        putOpt("monochromeMagentaGreen", s.monochromeMagentaGreen)
        putOpt("grainStrength", s.grainStrength?.name)
        putOpt("grainSize", s.grainSize?.name)
        putOpt("colorChrome", s.colorChrome?.name)
        putOpt("colorChromeBlue", s.colorChromeBlue?.name)
        putOpt("whiteBalance", s.whiteBalance?.name)
        putOpt("whiteBalanceTemperature", s.whiteBalanceTemperature)
        putOpt("whiteBalanceRed", s.whiteBalanceRed)
        putOpt("whiteBalanceBlue", s.whiteBalanceBlue)
        putOpt("dynamicRange", s.dynamicRange)
        putOpt("highlightTone", s.highlightTone)
        putOpt("shadowTone", s.shadowTone)
        putOpt("color", s.color)
        putOpt("sharpness", s.sharpness)
        putOpt("highIsoNoiseReduction", s.highIsoNoiseReduction)
        putOpt("clarity", s.clarity)
    }

    fun parseSettings(o: JSONObject): RecipeSettings {
        require(o.keys().asSequence().all { it in keys }) { "Unknown recipe setting" }
        fun int(k: String): Int? = if (o.has(k)) o.getInt(k) else null
        fun dbl(k: String): Double? = if (o.has(k)) o.getDouble(k) else null
        fun <T : Enum<T>> en(k: String, values: Array<T>): T? =
            if (o.has(k)) values.firstOrNull { it.name == o.getString(k) } ?: error("Invalid $k") else null

        val tags = if (o.has("tags")) {
            val arr = o.getJSONArray("tags")
            (0 until arr.length()).map { i -> RecipeTag.entries.firstOrNull { it.name == arr.getString(i) } ?: error("Invalid tag") }.toSet()
        } else emptySet()

        return RecipeSettings(
            filmSimulation = FilmSimulation.valueOf(o.getString("filmSimulation")),
            tags = tags,
            monochromeWarmCool = int("monochromeWarmCool"),
            monochromeMagentaGreen = int("monochromeMagentaGreen"),
            grainStrength = en("grainStrength", EffectStrength.entries.toTypedArray()),
            grainSize = en("grainSize", GrainSize.entries.toTypedArray()),
            colorChrome = en("colorChrome", EffectStrength.entries.toTypedArray()),
            colorChromeBlue = en("colorChromeBlue", EffectStrength.entries.toTypedArray()),
            whiteBalance = en("whiteBalance", WhiteBalance.entries.toTypedArray()),
            whiteBalanceTemperature = int("whiteBalanceTemperature"),
            whiteBalanceRed = int("whiteBalanceRed"),
            whiteBalanceBlue = int("whiteBalanceBlue"),
            dynamicRange = int("dynamicRange"),
            highlightTone = dbl("highlightTone"),
            shadowTone = dbl("shadowTone"),
            color = int("color"),
            sharpness = int("sharpness"),
            highIsoNoiseReduction = int("highIsoNoiseReduction"),
            clarity = int("clarity"),
        ).asCompleteRecipe().also { it.validate() }
    }

    fun recipe(r: Recipe) = JSONObject().apply {
        put("id", r.id)
        put("name", r.name)
        put("archived", r.archived)
        put("createdAt", r.createdAt)
        put("updatedAt", r.updatedAt)
        put("settings", settings(r.current.settings))
    }

    fun exportAll(recipes: List<Recipe>): String {
        val array = JSONArray()
        recipes.forEach { array.put(recipe(it)) }
        return array.toString(2)
    }
}
