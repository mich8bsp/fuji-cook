package io.github.mich8bsp.fujicook.metadata

import java.io.*

data class JpegSegment(val marker: Int, val payload: ByteArray)
data class ParsedJpeg(val segments: List<JpegSegment>, val scanAndTail: ByteArray)

object JpegSegments {
    fun read(input: InputStream): ParsedJpeg {
        val data = input.readBytes()
        require(data.size >= 4 && data[0] == 0xff.toByte() && data[1] == 0xd8.toByte()) { "Not a JPEG" }
        var p = 2
        val segments = mutableListOf<JpegSegment>()
        while (p + 1 < data.size) {
            require(data[p++] == 0xff.toByte()) { "Invalid JPEG marker" }
            while (p < data.size && data[p] == 0xff.toByte()) p++
            val markerStart = p - 1
            val marker = data[p++].toInt() and 0xff
            if (marker == 0xda) return ParsedJpeg(segments, data.copyOfRange(markerStart, data.size))
            if (marker == 0xd9) return ParsedJpeg(segments, data.copyOfRange(markerStart, data.size))
            if (marker in 0xd0..0xd7 || marker == 0x01) {
                segments += JpegSegment(marker, byteArrayOf())
                continue
            }
            require(p + 2 <= data.size)
            val length = ((data[p].toInt() and 0xff) shl 8) or (data[p + 1].toInt() and 0xff)
            require(length >= 2 && p + length <= data.size)
            segments += JpegSegment(marker, data.copyOfRange(p + 2, p + length))
            p += length
        }
        throw IllegalArgumentException("JPEG has no image scan")
    }

    fun write(jpeg: ParsedJpeg, output: OutputStream) {
        output.write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        jpeg.segments.forEach { s ->
            output.write(0xff)
            output.write(s.marker)
            if (s.marker !in 0xd0..0xd7 && s.marker != 0x01) {
                val n = s.payload.size + 2
                require(n <= 65535)
                output.write(n shr 8)
                output.write(n)
                output.write(s.payload)
            }
        }
        output.write(jpeg.scanAndTail)
    }
}
