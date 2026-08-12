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
    fun open(){
        var r=command(Ptp.OPEN_SESSION,intArrayOf(1),operation="Open session")
        if(r.first.code==0x201e){runCatching{command(Ptp.CLOSE_SESSION,operation="Close stale session")};r=command(Ptp.OPEN_SESSION,intArrayOf(1),operation="Reopen session")}
        checkOk(r)
    }
    private fun transfer(bytes:ByteArray){var at=0;while(at<bytes.size){val n=minOf(512*1024,bytes.size-at);val sent=connection.bulkTransfer(output,bytes,at,n,30000);require(sent>0){"USB write failed on endpoint 0x"+output.address.toString(16)+" after "+at+"/"+bytes.size+" bytes"};at+=sent}}
    private fun receive(operation:String,timeoutMs:Int):PtpContainer {
        val first=ByteArray(512*1024);val got=connection.bulkTransfer(input,first,first.size,timeoutMs)
        require(got>=12){operation+": USB read failed on endpoint 0x"+input.address.toString(16)+" after "+timeoutMs/1000+"s (result "+got+")"}
        val size=ByteBuffer.wrap(first,0,4).order(ByteOrder.LITTLE_ENDIAN).int;require(size in 12..100*1024*1024){operation+": invalid PTP container size "+size}
        val all=ByteArray(size);first.copyInto(all,0,0,minOf(got,size));var at=minOf(got,size)
        while(at<size){val n=connection.bulkTransfer(input,all,at,size-at,timeoutMs);require(n>0){operation+": USB read failed after "+at+"/"+size+" bytes"};at+=n}
        return PtpContainer.decode(all)
    }
    private fun command(code:Int,params:IntArray=intArrayOf(),payload:ByteArray?=null,operation:String="PTP 0x"+code.toString(16)):Pair<PtpContainer,ByteArray>{
        val tx=++transaction;transfer(PtpContainer.command(code,tx,params).encode());if(payload!=null)transfer(PtpContainer.data(code,tx,payload).encode())
        val timeout=if(payload!=null)60000 else 15000;var r=receive(operation,timeout);var data=byteArrayOf();if(r.type==PtpContainer.DATA){data=r.body;r=receive(operation,timeout)}
        require(r.type==PtpContainer.RESPONSE&&r.transactionId==tx){operation+": unexpected PTP response"};return r to data
    }
    private fun checkOk(r:Pair<PtpContainer,ByteArray>) { require(r.first.code==Ptp.OK){"Camera returned PTP 0x${r.first.code.toString(16)}"} }
    fun sendRaf(bytes:ByteArray){val info=objectInfo(bytes.size);checkOk(command(Ptp.SEND_OBJECT_INFO,intArrayOf(0,0,0),info,"Send RAF info"));checkOk(command(Ptp.SEND_OBJECT,payload=bytes,operation="Upload RAF"))}
    fun getProfile():ByteArray=command(Ptp.GET_PROP,intArrayOf(Ptp.PROFILE),operation="Read RAW profile").also(::checkOk).second
    fun setProfile(profile:ByteArray)=checkOk(command(Ptp.SET_PROP,intArrayOf(Ptp.PROFILE),profile,"Apply recipe profile"))
    suspend fun convert(timeoutSeconds:Int=30):ByteArray {val old=handles().toSet();checkOk(command(Ptp.SET_PROP,intArrayOf(Ptp.START_CONVERSION),byteArrayOf(1,0)));repeat(timeoutSeconds){handles().firstOrNull{it !in old}?.let{h->val result=command(Ptp.GET_OBJECT,intArrayOf(h));checkOk(result);runCatching{checkOk(command(Ptp.DELETE_OBJECT,intArrayOf(h)))};return result.second};delay(1000)};error("Camera conversion timed out")}
    private fun handles():List<Int>{val r=command(Ptp.GET_OBJECT_HANDLES,intArrayOf(-1,0,0));checkOk(r);if(r.second.size<4)return emptyList();val b=ByteBuffer.wrap(r.second).order(ByteOrder.LITTLE_ENDIAN);return List(b.int.coerceAtMost((r.second.size-4)/4)){b.int}}
    private fun objectInfo(size:Int):ByteArray {val b=ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0);b.putShort(0xf802.toShort());b.putShort(0);b.putInt(size);b.position(52);val name="FUP_FILE.dat";b.put((name.length+1).toByte());name.forEach{b.putShort(it.code.toShort())};b.putShort(0);b.put(0);b.put(0);b.put(0);return b.array().copyOf(b.position())}
    override fun close(){runCatching{command(Ptp.CLOSE_SESSION)};connection.releaseInterface(intf);connection.close()}
}
