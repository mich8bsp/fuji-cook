package io.github.mich8bsp.fujicook.metadata

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object RecipeMetadata {
    private val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray()

    fun readTags(jpeg: ParsedJpeg): List<String> {
        val xmp = jpeg.segments.filter { it.marker == 0xe1 && it.payload.startsWith(xmpHeader) }
            .flatMap { s -> Regex("recipe:[^<\"]+").findAll(String(s.payload, StandardCharsets.UTF_8)).map { it.value.trim() }.toList() }
        val iptc = jpeg.segments.filter { it.marker == 0xed }.flatMap { segment -> readIptcKeywords(segment.payload) }
        return (xmp + iptc).distinct()
    }

    fun tag(jpeg: ParsedJpeg, recipeName: String, modifiedSummary: String? = null): ParsedJpeg {
        val tags = listOfNotNull("recipe:$recipeName", modifiedSummary?.let { "recipe-mods:$it" })
        val segments = jpeg.segments.filterNot { (it.marker == 0xe1 && it.payload.startsWith(xmpHeader)) || it.marker == 0xed }.toMutableList()
        val subjects = (readTags(jpeg).filterNot { it.startsWith("recipe:") } + tags).distinct()
        val escaped = subjects.joinToString("") { "<rdf:li>${xml(it)}</rdf:li>" }
        val xmp = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:subject><rdf:Bag>$escaped</rdf:Bag></dc:subject></rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>""".encodeToByteArray()
        var insert = (segments.indexOfLast { it.marker in 0xe0..0xef } + 1).coerceAtLeast(0)
        segments.add(insert, JpegSegment(0xe1, xmpHeader + xmp))
        insert++
        segments.add(insert, JpegSegment(0xed, iptcSegment(tags)))
        return jpeg.copy(segments = segments)
    }

    private fun readIptcKeywords(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        var p = 0
        while (p + 5 <= bytes.size) {
            if (bytes[p] == 0x1c.toByte() && bytes[p + 1] == 2.toByte() && bytes[p + 2] == 25.toByte()) {
                val n = ((bytes[p + 3].toInt() and 255) shl 8) or (bytes[p + 4].toInt() and 255)
                if (p + 5 + n <= bytes.size) out += String(bytes, p + 5, n, StandardCharsets.UTF_8)
                p += 5 + n
            } else {
                p++
            }
        }
        return out.filter { it.startsWith("recipe:") }
    }

    private fun iptcSegment(keywords: List<String>): ByteArray {
        val dataset = ByteArrayOutputStream().apply {
            keywords.forEach { keyword ->
                val value = keyword.toByteArray(StandardCharsets.UTF_8)
                require(value.size <= 32767)
                write(0x1c); write(2); write(25)
                write(value.size shr 8); write(value.size)
                write(value)
            }
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("Photoshop 3.0\u0000".toByteArray(StandardCharsets.US_ASCII))
        out.write("8BIM".toByteArray(StandardCharsets.US_ASCII))
        out.write(byteArrayOf(0x04, 0x04, 0, 0))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(dataset.size).array())
        out.write(dataset)
        if (dataset.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    private fun xml(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
