package io.github.mich8bsp.fujicook.data

import io.github.mich8bsp.fujicook.model.*
import org.json.JSONArray
import org.json.JSONObject

object RecipeJson {
    private val keys = setOf(
        "filmSimulation", "monochromeWarmCool", "monochromeMagentaGreen", "grainStrength", "grainSize",
        "colorChrome", "colorChromeBlue", "smoothSkin", "whiteBalance", "whiteBalanceTemperature",
        "whiteBalanceRed", "whiteBalanceBlue", "dynamicRange", "highlightTone", "shadowTone",
        "color", "sharpness", "highIsoNoiseReduction", "clarity", "colorSpace",
    )

    fun settings(s: RecipeSettings) = JSONObject().apply {
        put("filmSimulation", s.filmSimulation.name)
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

        return RecipeSettings(
            filmSimulation = FilmSimulation.valueOf(o.getString("filmSimulation")),
            monochromeWarmCool = null,
            monochromeMagentaGreen = null,
            grainStrength = en("grainStrength", EffectStrength.entries.toTypedArray()),
            grainSize = en("grainSize", GrainSize.entries.toTypedArray()),
            colorChrome = en("colorChrome", EffectStrength.entries.toTypedArray()),
            colorChromeBlue = en("colorChromeBlue", EffectStrength.entries.toTypedArray()),
            smoothSkin = null,
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
            colorSpace = null,
        ).asCompleteRecipe().also { it.validate() }
    }

    fun envelope(items: List<ExportRecipe>) = JSONObject()
        .put("schemaVersion", 1)
        .put("cameraModel", "Fujifilm X-T5")
        .put("recipes", JSONArray().also { a -> items.forEach { a.put(JSONObject().put("name", it.name).put("settings", settings(it.settings))) } })
        .toString(2)

    fun parseEnvelope(text: String): RecipeEnvelope {
        val o = JSONObject(text)
        require(o.keys().asSequence().toSet() == setOf("schemaVersion", "cameraModel", "recipes"))
        require(o.getInt("schemaVersion") == 1)
        require(o.getString("cameraModel") == "Fujifilm X-T5")
        val a = o.getJSONArray("recipes")
        return RecipeEnvelope(
            recipes = List(a.length()) { i ->
                val r = a.getJSONObject(i)
                require(r.keys().asSequence().toSet() == setOf("name", "settings"))
                ExportRecipe(r.getString("name").also { require(it.isNotBlank()) }, parseSettings(r.getJSONObject("settings")))
            },
        )
    }
}
