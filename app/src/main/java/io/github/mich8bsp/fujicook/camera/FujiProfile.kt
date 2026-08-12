package io.github.mich8bsp.fujicook.camera

import io.github.mich8bsp.fujicook.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FujiProfile {
    const val SIZE=632;private const val OFFSET=0x201;private const val COUNT=29
    private val nr=mapOf(4 to 0x5000,3 to 0x6000,2 to 0,1 to 0x1000,0 to 0x2000,-1 to 0x3000,-2 to 0x4000,-3 to 0x7000,-4 to 0x8000)
    fun processorId(camera:ByteArray):String { require(camera.size>4);val n=camera[2].toInt()and 0xff;return buildString{for(i in 0 until n){val p=3+i*2;if(p+1>=camera.size)break;val c=(camera[p].toInt()and 255)or((camera[p+1].toInt()and 255)shl 8);if(c==0)break;append(c.toChar())}}.also{require(it.isNotBlank()){ "Camera profile has no processor identifier" }} }
    fun build(camera:ByteArray,s:RecipeSettings):ByteArray {
        s.validate();val processor=processorId(camera);val out=ByteArray(SIZE);val b=ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);b.putShort(0,COUNT.toShort());out[2]=(processor.length+1).toByte();processor.forEachIndexed{i,c->b.putShort(3+i*2,c.code.toShort())}
        val values=if(camera.size>=OFFSET+COUNT*4) IntArray(COUNT){ByteBuffer.wrap(camera).order(ByteOrder.LITTLE_ENDIAN).getInt(OFFSET+it*4)} else intArrayOf(2,7,7,2,0,1,0,1,1,1,0,0,0,0,0,0,0,0,0,0,0,1,0,1,1,0,0,0,0)
        fun set(i:Int,v:Int?){if(v!=null)values[i]=v};set(7,film(s.filmSimulation));set(8,s.grainStrength?.let{effect(it)+(if(s.grainSize==GrainSize.LARGE&&it!=EffectStrength.OFF)2 else 0)});set(9,s.colorChrome?.let(::effect));set(11,s.whiteBalance?.let(::wb));set(12,s.whiteBalanceRed);set(13,s.whiteBalanceBlue);set(14,s.whiteBalanceTemperature);set(5,s.dynamicRange?.let{mapOf(100 to 1,200 to 2,400 to 3).getValue(it)});set(15,s.highlightTone?.times(10)?.toInt());set(16,s.shadowTone?.times(10)?.toInt());set(17,s.color?.times(10));set(18,s.sharpness?.times(10));set(19,s.highIsoNoiseReduction?.let{nr.getValue(it)});set(22,s.monochromeWarmCool?.times(10));set(23,s.smoothSkin?.let(::effect));set(24,s.colorChromeBlue?.let(::effect));set(25,s.monochromeMagentaGreen?.times(10));set(26,s.clarity?.times(10));set(21,s.colorSpace?.let{if(it==ColorSpace.SRGB)1 else 2})
        values.forEachIndexed{i,v->b.putInt(OFFSET+i*4,v)};return out
    }
    private fun effect(v:EffectStrength)=v.ordinal+1
    private fun wb(v:WhiteBalance)=when(v){WhiteBalance.AUTO,WhiteBalance.AUTO_WHITE_PRIORITY,WhiteBalance.AUTO_AMBIENCE_PRIORITY->2;WhiteBalance.DAYLIGHT->4;WhiteBalance.INCANDESCENT->6;WhiteBalance.UNDERWATER->8;WhiteBalance.FLUORESCENT_1->0x8001;WhiteBalance.FLUORESCENT_2->0x8002;WhiteBalance.FLUORESCENT_3->0x8003;WhiteBalance.SHADE->0x8006;WhiteBalance.TEMPERATURE->0x8007;WhiteBalance.CUSTOM_1->0x8008;WhiteBalance.CUSTOM_2->0x8009;WhiteBalance.CUSTOM_3->0x800a}
    private fun film(v:FilmSimulation)=v.ordinal+1
}
