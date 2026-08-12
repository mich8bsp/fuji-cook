package io.github.mich8bsp.fujicook.camera

import android.hardware.usb.*
import kotlinx.coroutines.delay
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AndroidFujiCamera(private val manager:UsbManager,private val device:UsbDevice):Closeable {
    companion object { const val VENDOR=0x04cb;const val XT5=0x02fc }
    init { require(device.vendorId==VENDOR&&device.productId==XT5){"Only Fujifilm X-T5 is supported"} }
    private val intf=(0 until device.interfaceCount).map(device::getInterface).firstOrNull{it.interfaceClass==UsbConstants.USB_CLASS_STILL_IMAGE}?:device.getInterface(0)
    private val input=(0 until intf.endpointCount).map(intf::getEndpoint).first{it.direction==UsbConstants.USB_DIR_IN&&it.type==UsbConstants.USB_ENDPOINT_XFER_BULK}
    private val output=(0 until intf.endpointCount).map(intf::getEndpoint).first{it.direction==UsbConstants.USB_DIR_OUT&&it.type==UsbConstants.USB_ENDPOINT_XFER_BULK}
    private val connection=requireNotNull(manager.openDevice(device)){"USB permission was not granted"}.also{require(it.claimInterface(intf,true)){"Could not claim camera interface"}}
    private var transaction=0
    fun open(){val r=command(Ptp.OPEN_SESSION,intArrayOf(1));checkOk(r)}
    private fun transfer(bytes:ByteArray){var at=0;while(at<bytes.size){val n=minOf(512*1024,bytes.size-at);val sent=connection.bulkTransfer(output,bytes,at,n,5000);require(sent>0){"USB write failed"};at+=sent}}
    private fun receive():PtpContainer {val first=ByteArray(512*1024);val got=connection.bulkTransfer(input,first,first.size,5000);require(got>=12){"USB read failed"};val size=ByteBuffer.wrap(first,0,4).order(ByteOrder.LITTLE_ENDIAN).int;val all=ByteArray(size);first.copyInto(all,0,0,minOf(got,size));var at=got;while(at<size){val n=connection.bulkTransfer(input,all,at,size-at,5000);require(n>0){"USB read failed"};at+=n};return PtpContainer.decode(all)}
    private fun command(code:Int,params:IntArray= intArrayOf(),payload:ByteArray?=null):Pair<PtpContainer,ByteArray>{val tx=++transaction;transfer(PtpContainer.command(code,tx,params).encode());if(payload!=null)transfer(PtpContainer.data(code,tx,payload).encode());var r=receive();var data=byteArrayOf();if(r.type==PtpContainer.DATA){data=r.body;r=receive()};require(r.type==PtpContainer.RESPONSE&&r.transactionId==tx);return r to data}
    private fun checkOk(r:Pair<PtpContainer,ByteArray>) { require(r.first.code==Ptp.OK){"Camera returned PTP 0x${r.first.code.toString(16)}"} }
    fun sendRaf(bytes:ByteArray){val info=objectInfo(bytes.size);checkOk(command(Ptp.SEND_OBJECT_INFO,intArrayOf(0,0,0),info));checkOk(command(Ptp.SEND_OBJECT,payload=bytes))}
    fun getProfile():ByteArray=command(Ptp.GET_PROP,intArrayOf(Ptp.PROFILE)).also(::checkOk).second
    fun setProfile(profile:ByteArray)=checkOk(command(Ptp.SET_PROP,intArrayOf(Ptp.PROFILE),profile))
    suspend fun convert(timeoutSeconds:Int=30):ByteArray {val old=handles().toSet();checkOk(command(Ptp.SET_PROP,intArrayOf(Ptp.START_CONVERSION),byteArrayOf(1,0)));repeat(timeoutSeconds){handles().firstOrNull{it !in old}?.let{h->val result=command(Ptp.GET_OBJECT,intArrayOf(h));checkOk(result);runCatching{checkOk(command(Ptp.DELETE_OBJECT,intArrayOf(h)))};return result.second};delay(1000)};error("Camera conversion timed out")}
    private fun handles():List<Int>{val r=command(Ptp.GET_OBJECT_HANDLES,intArrayOf(-1,0,0));checkOk(r);if(r.second.size<4)return emptyList();val b=ByteBuffer.wrap(r.second).order(ByteOrder.LITTLE_ENDIAN);return List(b.int.coerceAtMost((r.second.size-4)/4)){b.int}}
    private fun objectInfo(size:Int):ByteArray {val b=ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0);b.putShort(0xf802.toShort());b.putShort(0);b.putInt(size);b.position(52);val name="FUP_FILE.dat";b.put((name.length+1).toByte());name.forEach{b.putShort(it.code.toShort())};b.putShort(0);b.put(0);b.put(0);b.put(0);return b.array().copyOf(b.position())}
    override fun close(){runCatching{command(Ptp.CLOSE_SESSION)};connection.releaseInterface(intf);connection.close()}
}
