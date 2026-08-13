package io.github.mich8bsp.fujicook

import io.github.mich8bsp.fujicook.camera.*
import io.github.mich8bsp.fujicook.data.*
import io.github.mich8bsp.fujicook.metadata.*
import io.github.mich8bsp.fujicook.model.*
import java.io.*
import java.nio.*
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test
    fun ptpRoundTrip() {
        val c = PtpContainer.command(Ptp.OPEN_SESSION, 7, intArrayOf(1))
        val d = PtpContainer.decode(c.encode())
        assertEquals(1, d.type)
        assertEquals(7, d.transactionId)
        assertEquals(16, c.encode().size)
    }

    @Test
    fun profileKeepsProcessor() {
        val camera = ByteArray(64)
        camera[2] = 9
        "FF179502".forEachIndexed { i, c -> ByteBuffer.wrap(camera).order(ByteOrder.LITTLE_ENDIAN).putShort(3 + i * 2, c.code.toShort()) }
        val p = FujiProfile.build(camera, RecipeSettings(FilmSimulation.CLASSIC_CHROME, color = 2, clarity = -1))
        assertEquals(632, p.size)
        assertEquals("FF179502", FujiProfile.processorId(p))
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(11, b.getInt(0x201 + 7 * 4))
        assertEquals(20, b.getInt(0x201 + 17 * 4))
    }

    @Test
    fun matcherHardFiltersFilm() {
        val now = 1L
        fun rec(name: String, film: FilmSimulation, color: Int): Pair<Recipe, RecipeRevision> {
            val rev = RecipeRevision(name, "$name-id", 1, RecipeSettings(film, color = color, sharpness = 1), now)
            return Recipe("$name-id", name, false, now, now, rev) to rev
        }
        val result = RecipeMatcher.match(
            RecipeSettings(FilmSimulation.CLASSIC_CHROME, color = 2, sharpness = 1),
            listOf(rec("winner", FilmSimulation.CLASSIC_CHROME, 2), rec("wrong", FilmSimulation.PROVIA, 2)),
        )
        assertEquals(MatchStatus.MATCH, result.status)
        assertEquals("winner", result.candidates.first().recipe.name)
    }

    @Test
    fun strictJsonRoundTrip() {
        val text = RecipeJson.envelope(listOf(ExportRecipe("Test", RecipeSettings(FilmSimulation.REALA_ACE, clarity = 2))))
        assertEquals(FilmSimulation.REALA_ACE, RecipeJson.parseEnvelope(text).recipes.single().settings.filmSimulation)
        assertThrows(IllegalArgumentException::class.java) { RecipeJson.parseEnvelope(text.replace("\"recipes\"", "\"unexpected\":1,\"recipes\"")) }
    }

    @Test
    fun metadataPreservesScan() {
        val scan = byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 8, 1, 1, 0, 0, 63, 0, 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
        val source = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 4, 1, 2) + scan
        val parsed = JpegSegments.read(ByteArrayInputStream(source))
        assertArrayEquals(scan, parsed.scanAndTail)
        val out = ByteArrayOutputStream()
        JpegSegments.write(RecipeMetadata.tag(parsed, "Fuji"), out)
        assertArrayEquals(scan, JpegSegments.read(ByteArrayInputStream(out.toByteArray())).scanAndTail)
    }

    @Test
    fun matcherRejectsIdentityMismatch() {
        val now = 1L
        fun candidate(name: String, s: RecipeSettings): Pair<Recipe, RecipeRevision> {
            val rev = RecipeRevision(name, "$name-id", 1, s, now)
            return Recipe("$name-id", name, false, now, now, rev) to rev
        }
        val photo = RecipeSettings(
            FilmSimulation.CLASSIC_CHROME, colorChrome = EffectStrength.STRONG, whiteBalance = WhiteBalance.DAYLIGHT,
            highlightTone = 1.0, color = 2, sharpness = 1, highIsoNoiseReduction = -1,
        )
        val wrong = photo.copy(sharpness = 2)
        val result = RecipeMatcher.match(photo, listOf(candidate("wrong", wrong)))
        assertEquals(MatchStatus.NO_MATCH, result.status)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun matcherPenalizesButKeepsSoftMismatch() {
        val now = 1L
        fun candidate(name: String, s: RecipeSettings): Pair<Recipe, RecipeRevision> {
            val rev = RecipeRevision(name, "$name-id", 1, s, now)
            return Recipe("$name-id", name, false, now, now, rev) to rev
        }
        val photo = RecipeSettings(
            FilmSimulation.CLASSIC_CHROME, colorChrome = EffectStrength.STRONG, colorChromeBlue = EffectStrength.OFF,
            whiteBalance = WhiteBalance.DAYLIGHT, highlightTone = 1.0, shadowTone = 0.0, color = 2, sharpness = 1,
            highIsoNoiseReduction = -1, grainStrength = EffectStrength.STRONG, grainSize = GrainSize.LARGE,
            dynamicRange = 400, clarity = 2,
        )
        val recipe = photo.copy(grainStrength = EffectStrength.WEAK, dynamicRange = 200)
        val result = RecipeMatcher.match(photo, listOf(candidate("soft", recipe)))
        assertEquals(1, result.candidates.size)
        assertEquals(0.5, result.candidates.single().confidence, 0.0)
        assertEquals(2, result.candidates.single().differences.size)
    }

    @Test
    fun matcherDistinguishesWhiteBalanceTemperature() {
        val now = 1L
        fun candidate(name: String, temperature: Int): Pair<Recipe, RecipeRevision> {
            val settings = RecipeSettings(FilmSimulation.PROVIA, whiteBalance = WhiteBalance.TEMPERATURE, whiteBalanceTemperature = temperature)
            val rev = RecipeRevision(name, "$name-id", 1, settings, now)
            return Recipe("$name-id", name, false, now, now, rev) to rev
        }
        val photo = RecipeSettings(FilmSimulation.PROVIA, whiteBalance = WhiteBalance.TEMPERATURE, whiteBalanceTemperature = 5000)
        val result = RecipeMatcher.match(photo, listOf(candidate("cool", 6500), candidate("warm", 5000)))
        assertEquals(MatchStatus.MATCH, result.status)
        assertEquals("warm", result.candidates.single().recipe.name)
    }

    @Test
    fun validateRequiresTemperatureIffWhiteBalanceIsTemperature() {
        assertThrows(IllegalArgumentException::class.java) {
            RecipeSettings(FilmSimulation.PROVIA, whiteBalance = WhiteBalance.TEMPERATURE, whiteBalanceTemperature = null).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecipeSettings(FilmSimulation.PROVIA, whiteBalance = WhiteBalance.DAYLIGHT, whiteBalanceTemperature = 5000).validate()
        }
        RecipeSettings(FilmSimulation.PROVIA, whiteBalance = WhiteBalance.TEMPERATURE, whiteBalanceTemperature = 5000).validate()
    }

    @Test
    fun decodesMonochromeFilmFromSaturationWhenFilmModeMissing() {
        assertEquals(FilmSimulation.MONOCHROME, FujifilmMakerNote.decodeFilm(null, 0x300))
        assertEquals(FilmSimulation.SEPIA, FujifilmMakerNote.decodeFilm(null, 0x310))
        assertEquals(FilmSimulation.ACROS, FujifilmMakerNote.decodeFilm(null, 0x500))
        assertEquals(FilmSimulation.ACROS_YE, FujifilmMakerNote.decodeFilm(null, 0x502))
        assertEquals(FilmSimulation.ACROS_R, FujifilmMakerNote.decodeFilm(null, 0x501))
        assertEquals(FilmSimulation.ACROS_G, FujifilmMakerNote.decodeFilm(null, 0x503))
    }

    @Test
    fun decodesFujiRecipeNumericTables() {
        assertEquals(4, FujifilmMakerNote.decodeColor(0x0e0))
        assertEquals(-1, FujifilmMakerNote.decodeColor(0x180))
        assertEquals(-3, FujifilmMakerNote.decodeSharpness(0x01))
        assertEquals(1, FujifilmMakerNote.decodeSharpness(0x84))
        assertEquals(-4, FujifilmMakerNote.decodeNoiseReduction(0x2e0))
        assertEquals(3, FujifilmMakerNote.decodeNoiseReduction(0x1c0))
    }
}
