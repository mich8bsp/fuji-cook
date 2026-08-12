package io.github.mich8bsp.fujicook.metadata

import io.github.mich8bsp.fujicook.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal TIFF reader intentionally limited to safe scalar Fujifilm recipe tags. */
object FujifilmMakerNote {
    private const val EXIF_IFD = 0x8769; private const val MAKER_NOTE = 0x927c; private const val MAKE = 0x010f
    fun extract(jpeg: ParsedJpeg): ExtractedSettings {
        val exif = jpeg.segments.firstOrNull { it.marker == 0xe1 && it.payload.startsWith("Exif\u0000\u0000".encodeToByteArray()) }?.payload ?: error("JPEG has no EXIF")
        val tiff = exif.copyOfRange(6, exif.size); val root = Tiff(tiff); val make = root.ascii(root.entry(root.firstIfd, MAKE))
        require(make?.contains("FUJIFILM", true) == true) { "Not a Fujifilm JPEG" }
        val exifOffset = root.number(root.entry(root.firstIfd, EXIF_IFD))?.toInt() ?: error("No EXIF IFD")
        val maker = root.bytes(root.entry(exifOffset, MAKER_NOTE)) ?: error("No Fujifilm MakerNote")
        require(maker.startsWith("FUJIFILM".encodeToByteArray()) && maker.size > 12) { "Invalid Fujifilm MakerNote" }
        val m = Tiff(maker, ByteOrder.LITTLE_ENDIAN, 0); val ifd = m.u32(8).toInt(); fun num(tag:Int)=m.number(m.entry(ifd,tag))?.toInt()
        val saturationRaw=num(0x1003); val film = decodeFilm(num(0x1401), saturationRaw)
        val settings = RecipeSettings(
            filmSimulation=film,
            monochromeWarmCool=num(0x1049)?.signed8(), monochromeMagentaGreen=num(0x104b)?.signed8(),
            grainStrength=effect(num(0x1047)), grainSize=when(num(0x104c)){16->GrainSize.SMALL;32->GrainSize.LARGE;else->null},
            colorChrome=effect(num(0x1048)), colorChromeBlue=effect(num(0x104e)), smoothSkin=effect(num(0x1053)),
            whiteBalance=decodeWhiteBalance(num(0x1002)), whiteBalanceTemperature=num(0x1005),
            dynamicRange=when(num(0x1402)){0x100->100;0x200->200;0x201->400;else->null},
            highlightTone=decodeTone(num(0x1041)), shadowTone=decodeTone(num(0x1040)),
            color=if (film.name.startsWith("ACROS") || film.name.startsWith("MONOCHROME") || film==FilmSimulation.SEPIA) null else decodeTone(saturationRaw)?.toInt(),
            sharpness=decodeTone(num(0x1001))?.toInt(), highIsoNoiseReduction=decodeTone(num(0x100e))?.toInt(), clarity=num(0x100f)?.let { -it / 1000 },
        )
        return ExtractedSettings(settings, make, RecipeMetadata.readTags(jpeg))
    }
    private fun ByteArray.startsWith(prefix:ByteArray)=size>=prefix.size && prefix.indices.all{this[it]==prefix[it]}
    private fun Int.signed8()=(this and 0xff).let{if(it>127) it-256 else it}
    private fun effect(v:Int?)=when(v){0->EffectStrength.OFF;32->EffectStrength.WEAK;64->EffectStrength.STRONG;else->null}
    private fun decodeTone(v:Int?):Double? = v?.let { signed32(it) / -16.0 }
    private fun signed32(v:Int)=v
    private fun decodeWhiteBalance(v:Int?)=when(v){0->WhiteBalance.AUTO;256->WhiteBalance.DAYLIGHT;512->WhiteBalance.SHADE;768->WhiteBalance.FLUORESCENT_1;769->WhiteBalance.FLUORESCENT_2;770->WhiteBalance.FLUORESCENT_3;1024->WhiteBalance.INCANDESCENT;3840->WhiteBalance.CUSTOM_1;3841->WhiteBalance.CUSTOM_2;3842->WhiteBalance.CUSTOM_3;4080->WhiteBalance.TEMPERATURE;else->null}
    private fun decodeFilm(v:Int?,sat:Int?):FilmSimulation {
        // Fujifilm encodes monochrome modes in Saturation rather than FilmMode.
        val mono=when(sat){0x100->FilmSimulation.MONOCHROME;0x110->FilmSimulation.MONOCHROME_YE;0x120->FilmSimulation.MONOCHROME_R;0x130->FilmSimulation.MONOCHROME_G;0x200->FilmSimulation.SEPIA;0x300->FilmSimulation.ACROS;0x310->FilmSimulation.ACROS_YE;0x320->FilmSimulation.ACROS_R;0x330->FilmSimulation.ACROS_G;else->null}; if(mono!=null)return mono
        return when(v){0x000->FilmSimulation.PROVIA;0x200,0x400->FilmSimulation.VELVIA;0x120->FilmSimulation.ASTIA;0x500->FilmSimulation.PRO_NEG_STD;0x501->FilmSimulation.PRO_NEG_HI;0x600->FilmSimulation.CLASSIC_CHROME;0x700->FilmSimulation.ETERNA;0x800->FilmSimulation.CLASSIC_NEGATIVE;0x900->FilmSimulation.ETERNA_BLEACH_BYPASS;0xa00->FilmSimulation.NOSTALGIC_NEGATIVE;0xb00->FilmSimulation.REALA_ACE;else->error("Unsupported film simulation code: $v")}
    }

    private data class Entry(val type:Int,val count:Int,val value:Int,val at:Int)
    private class Tiff(val b:ByteArray, forced:ByteOrder?=null, private val base:Int=0) {
        val order=forced ?: when(String(b,0,2)){"II"->ByteOrder.LITTLE_ENDIAN;"MM"->ByteOrder.BIG_ENDIAN;else->error("Invalid TIFF")}; val firstIfd:Int get()=u32(4).toInt()
        fun u16(p:Int)=ByteBuffer.wrap(b,p,2).order(order).short.toInt() and 0xffff
        fun u32(p:Int)=ByteBuffer.wrap(b,p,4).order(order).int.toLong() and 0xffffffffL
        fun entry(ifd:Int,tag:Int):Entry? { if(ifd<0||ifd+2>b.size)return null; val n=u16(ifd); for(i in 0 until n){val p=ifd+2+i*12;if(p+12>b.size)break;if(u16(p)==tag)return Entry(u16(p+2),u32(p+4).toInt(),u32(p+8).toInt(),p+8)};return null }
        fun number(e:Entry?):Long? { e?:return null; val size=when(e.type){1,2,7->1;3->2;4,9->4;else->return null}; val p=if(e.count*size<=4)e.at else e.value+base; return when(e.type){1,7->b.getOrNull(p)?.toLong()?.and(255);3->if(p+2<=b.size)u16(p).toLong() else null;4->if(p+4<=b.size)u32(p) else null;9->if(p+4<=b.size)ByteBuffer.wrap(b,p,4).order(order).int.toLong() else null;else->null} }
        fun bytes(e:Entry?):ByteArray? { e?:return null; val unit=when(e.type){1,2,7->1;3->2;4,9->4;5,10->8;else->return null};val len=e.count*unit;val p=if(len<=4)e.at else e.value+base;return if(p>=0&&p+len<=b.size)b.copyOfRange(p,p+len)else null }
        fun ascii(e:Entry?)=bytes(e)?.toString(Charsets.US_ASCII)?.trimEnd('\u0000')
    }
}
