package io.github.mich8bsp.fujicook

import io.github.mich8bsp.fujicook.camera.*
import io.github.mich8bsp.fujicook.data.*
import io.github.mich8bsp.fujicook.metadata.*
import io.github.mich8bsp.fujicook.model.*
import io.github.mich8bsp.fujicook.ui.suggestedFileName
import java.io.*
import java.nio.*
import java.nio.charset.StandardCharsets
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
        assertEquals(11.0 / 13, result.candidates.single().confidence, 0.0)
        assertEquals(2, result.candidates.single().differences.size)
        assertEquals("DR400, Grain strong large", result.candidates.single().modifiedSummary)
    }

    @Test
    fun tagEmbedsModifiedSummaryWithoutLeakingIntoReadTags() {
        val scan = byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 8, 1, 1, 0, 0, 63, 0, 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
        val source = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 4, 1, 2) + scan
        val parsed = JpegSegments.read(ByteArrayInputStream(source))
        val tagged = RecipeMetadata.tag(parsed, "Natura 1600", "DR100, Clarity 0, Grain off")
        val out = ByteArrayOutputStream()
        JpegSegments.write(tagged, out)
        val reread = JpegSegments.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf("recipe:Natura 1600"), RecipeMetadata.readTags(reread))
        val iptcPayload = reread.segments.first { it.marker == 0xed }.payload
        assertTrue(String(iptcPayload, StandardCharsets.UTF_8).contains("recipe-mods:DR100, Clarity 0, Grain off"))
    }

    @Test
    fun suggestedFileNameUsesOriginalNameAndSnakeCasedRecipe() {
        assertEquals("DSCF1234_natura_1600.jpg", suggestedFileName("DSCF1234.JPG", "Natura 1600"))
        assertEquals("photo_my_recipe.jpg", suggestedFileName(null, "My Recipe!"))
        assertEquals("archive.tar_provia.jpg", suggestedFileName("archive.tar.gz", "Provia"))
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

    @Test
    fun matcherFlagsAmbiguousOnTie() {
        val now = 1L
        fun candidate(name: String, s: RecipeSettings): Pair<Recipe, RecipeRevision> {
            val rev = RecipeRevision(name, "$name-id", 1, s, now)
            return Recipe("$name-id", name, false, now, now, rev) to rev
        }
        val settings = RecipeSettings(FilmSimulation.PROVIA, color = 1, sharpness = 1)
        val result = RecipeMatcher.match(settings, listOf(candidate("a", settings), candidate("b", settings)))
        assertEquals(MatchStatus.AMBIGUOUS, result.status)
    }

    @Test
    fun matcherReturnsLowConfidenceOnManySoftMismatches() {
        val now = 1L
        fun candidate(name: String, s: RecipeSettings): Pair<Recipe, RecipeRevision> {
            val rev = RecipeRevision(name, "$name-id", 1, s, now)
            return Recipe("$name-id", name, false, now, now, rev) to rev
        }
        val photo = RecipeSettings(
            FilmSimulation.PROVIA, colorChrome = EffectStrength.OFF, colorChromeBlue = EffectStrength.OFF,
            whiteBalance = WhiteBalance.AUTO, highlightTone = 0.0, shadowTone = 0.0, color = 0, sharpness = 0,
            highIsoNoiseReduction = 0, grainStrength = EffectStrength.STRONG, grainSize = GrainSize.LARGE,
            dynamicRange = 400, clarity = 5,
        )
        val recipe = photo.copy(grainStrength = EffectStrength.WEAK, grainSize = GrainSize.SMALL, dynamicRange = 0, clarity = -5)
        val result = RecipeMatcher.match(photo, listOf(candidate("soft", recipe)))
        assertEquals(MatchStatus.LOW_CONFIDENCE, result.status)
        assertEquals(9.0 / 13, result.candidates.single().confidence, 0.0)
    }

    @Test
    fun jsonRoundTripsSettings() {
        val settings = RecipeSettings(
            FilmSimulation.CLASSIC_CHROME, tags = setOf(RecipeTag.SUNNY, RecipeTag.PORTRAIT),
            grainStrength = EffectStrength.STRONG, grainSize = GrainSize.LARGE,
            whiteBalance = WhiteBalance.TEMPERATURE, whiteBalanceTemperature = 5500,
            dynamicRange = 200, highlightTone = 1.0, shadowTone = -0.5, color = 2, sharpness = 1,
            highIsoNoiseReduction = -1, clarity = 2,
        ).asCompleteRecipe()
        assertEquals(settings, RecipeJson.parseSettings(RecipeJson.settings(settings)))
    }

    @Test
    fun jsonRejectsUnknownKey() {
        val json = RecipeJson.settings(RecipeSettings(FilmSimulation.PROVIA).asCompleteRecipe())
        json.put("bogus", 1)
        assertThrows(IllegalArgumentException::class.java) { RecipeJson.parseSettings(json) }
    }

    @Test
    fun profileReadsExistingValuesWhenCameraArrayIsFullSize() {
        val camera = ByteArray(FujiProfile.SIZE)
        val b = ByteBuffer.wrap(camera).order(ByteOrder.LITTLE_ENDIAN)
        camera[2] = 9
        "FF179502".forEachIndexed { i, c -> b.putShort(3 + i * 2, c.code.toShort()) }
        IntArray(29) { it * 100 }.forEachIndexed { i, v -> b.putInt(0x201 + i * 4, v) }
        val p = FujiProfile.build(camera, RecipeSettings(FilmSimulation.PROVIA, sharpness = 2))
        val out = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(600, out.getInt(0x201 + 6 * 4)) // untouched index passes through from the camera
        assertEquals(20, out.getInt(0x201 + 18 * 4)) // sharpness maps to index 18, scaled by 10
    }

    @Test
    fun ptpDataContainerRoundTrip() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val c = PtpContainer.data(Ptp.SET_PROP, 3, payload)
        val d = PtpContainer.decode(c.encode())
        assertEquals(PtpContainer.DATA, d.type)
        assertEquals(Ptp.SET_PROP, d.code)
        assertArrayEquals(payload, d.body)
    }

    @Test
    fun ptpDecodeRejectsTruncatedContainer() {
        assertThrows(IllegalArgumentException::class.java) { PtpContainer.decode(ByteArray(8)) }
    }
}
