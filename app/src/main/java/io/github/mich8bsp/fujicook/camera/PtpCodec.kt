package io.github.mich8bsp.fujicook.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PtpContainer(val type:Int,val code:Int,val transactionId:Int,val body:ByteArray=byteArrayOf()) {
    fun encode():ByteArray=ByteBuffer.allocate(12+body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(12+body.size).putShort(type.toShort()).putShort(code.toShort()).putInt(transactionId).put(body).array()
    companion object { const val COMMAND=1;const val DATA=2;const val RESPONSE=3
        fun command(code:Int,transaction:Int,params:IntArray= intArrayOf())=PtpContainer(COMMAND,code,transaction,ByteBuffer.allocate(params.size*4).order(ByteOrder.LITTLE_ENDIAN).also{b->params.forEach{b.putInt(it)}}.array())
        fun data(code:Int,transaction:Int,payload:ByteArray)=PtpContainer(DATA,code,transaction,payload)
        fun decode(bytes:ByteArray):PtpContainer { require(bytes.size>=12);val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val length=b.int;require(length in 12..bytes.size);return PtpContainer(b.short.toInt()and 0xffff,b.short.toInt()and 0xffff,b.int,bytes.copyOfRange(12,length)) }
    }
}

object Ptp { const val OPEN_SESSION=0x1002;const val CLOSE_SESSION=0x1003;const val GET_OBJECT_HANDLES=0x1007;const val GET_OBJECT=0x1009;const val DELETE_OBJECT=0x100b;const val GET_PROP=0x1015;const val SET_PROP=0x1016;const val SEND_OBJECT_INFO=0x900c;const val SEND_OBJECT=0x900d;const val OK=0x2001;const val PROFILE=0xd185;const val START_CONVERSION=0xd183 }
