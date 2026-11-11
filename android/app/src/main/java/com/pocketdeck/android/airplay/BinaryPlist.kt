package com.pocketdeck.android.airplay

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin Apple Binary Plist (`bplist00`) reader & writer for AirPlay RTSP messages
 * (`/info`, `SETUP`, `TEARDOWN`).
 */
object BinaryPlist {
    private val MAGIC = "bplist00".toByteArray(StandardCharsets.US_ASCII)

    fun parse(data: ByteArray): Any? {
        require(data.size >= 40) { "Binary plist too short (${data.size} bytes)" }
        for (i in MAGIC.indices) {
            require(data[i] == MAGIC[i]) { "Invalid bplist00 header" }
        }
        val trailer = ByteBuffer.wrap(data, data.size - 32, 32).order(ByteOrder.BIG_ENDIAN)
        trailer.position(data.size - 32 + 6)
        val offsetIntSize = trailer.get().toInt() and 0xFF
        val objectRefSize = trailer.get().toInt() and 0xFF
        val numObjects = trailer.long.toInt()
        val topObject = trailer.long.toInt()
        val offsetTableOffset = trailer.long.toInt()

        require(offsetIntSize in 1..8 && objectRefSize in 1..8 && numObjects > 0) {
            "Malformed bplist trailer"
        }

        val offsets = IntArray(numObjects) { idx ->
            readUnsignedBE(data, offsetTableOffset + idx * offsetIntSize, offsetIntSize).toInt()
        }

        fun readObject(objIdx: Int, depth: Int): Any? {
            require(depth < 64 && objIdx in 0 until numObjects) { "Invalid bplist object graph" }
            var pos = offsets[objIdx]
            val marker = data[pos].toInt() and 0xFF
            pos += 1
            val kind = (marker ushr 4) and 0x0F
            val info = marker and 0x0F

            fun readCount(): Int {
                if (info != 0x0F) return info
                val intMarker = data[pos].toInt() and 0xFF
                pos += 1
                require((intMarker ushr 4) == 0x01) { "Expected integer length marker" }
                val byteCount = 1 shl (intMarker and 0x0F)
                val v = readUnsignedBE(data, pos, byteCount).toInt()
                pos += byteCount
                return v
            }

            return when (kind) {
                0x0 -> when (info) {
                    0x0 -> null
                    0x8 -> false
                    0x9 -> true
                    else -> null
                }
                0x1 -> {
                    val byteCount = 1 shl info
                    val result: Any = when (byteCount) {
                        1 -> (data[pos].toInt() and 0xFF).toLong()
                        2 -> ByteBuffer.wrap(data, pos, 2).order(ByteOrder.BIG_ENDIAN).short.toLong() and 0xFFFFL
                        4 -> ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        8 -> ByteBuffer.wrap(data, pos, 8).order(ByteOrder.BIG_ENDIAN).long
                        16 -> ByteBuffer.wrap(data, pos + 8, 8).order(ByteOrder.BIG_ENDIAN).long
                        else -> throw IllegalArgumentException("Unsupported int width $byteCount")
                    }
                    result
                }
                0x2 -> {
                    val byteCount = 1 shl info
                    when (byteCount) {
                        4 -> ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).float.toDouble()
                        8 -> ByteBuffer.wrap(data, pos, 8).order(ByteOrder.BIG_ENDIAN).double
                        else -> throw IllegalArgumentException("Unsupported real width $byteCount")
                    }
                }
                0x3 -> {
                    ByteBuffer.wrap(data, pos, 8).order(ByteOrder.BIG_ENDIAN).double
                }
                0x4 -> {
                    val len = readCount()
                    data.copyOfRange(pos, pos + len)
                }
                0x5 -> {
                    val len = readCount()
                    String(data, pos, len, StandardCharsets.US_ASCII)
                }
                0x6 -> {
                    val charLen = readCount()
                    String(data, pos, charLen * 2, StandardCharsets.UTF_16BE)
                }
                0x8 -> {
                    val byteCount = info + 1
                    readUnsignedBE(data, pos, byteCount)
                }
                0xA -> {
                    val count = readCount()
                    List(count) { i ->
                        val ref = readUnsignedBE(data, pos + i * objectRefSize, objectRefSize).toInt()
                        readObject(ref, depth + 1)
                    }
                }
                0xD -> {
                    val count = readCount()
                    val keysStart = pos
                    val valsStart = pos + count * objectRefSize
                    val map = LinkedHashMap<String, Any?>(count)
                    for (i in 0 until count) {
                        val kRef = readUnsignedBE(data, keysStart + i * objectRefSize, objectRefSize).toInt()
                        val vRef = readUnsignedBE(data, valsStart + i * objectRefSize, objectRefSize).toInt()
                        val key = readObject(kRef, depth + 1)?.toString() ?: ""
                        map[key] = readObject(vRef, depth + 1)
                    }
                    map
                }
                else -> throw IllegalArgumentException("Unsupported bplist marker 0x${marker.toString(16)}")
            }
        }

        return readObject(topObject, 0)
    }

    @Suppress("UNCHECKED_CAST")
    fun parseDict(data: ByteArray): Map<String, Any?> {
        if (data.isEmpty()) return emptyMap()
        return (parse(data) as? Map<String, Any?>) ?: emptyMap()
    }

    fun encode(root: Any?): ByteArray {
        val objects = ArrayList<Any?>()

        fun flatten(node: Any?): Int {
            return when (node) {
                is Map<*, *> -> {
                    val idx = objects.size
                    objects.add(null)
                    val entries = ArrayList<Pair<Int, Int>>(node.size)
                    for ((k, v) in node) {
                        val ki = flatten(k?.toString() ?: "")
                        val vi = flatten(v)
                        entries.add(ki to vi)
                    }
                    objects[idx] = MappedDict(entries)
                    idx
                }
                is Iterable<*> -> {
                    val list = node.toList()
                    val idx = objects.size
                    objects.add(null)
                    val refs = IntArray(list.size) { flatten(list[it]) }
                    objects[idx] = MappedArray(refs)
                    idx
                }
                else -> {
                    val idx = objects.size
                    objects.add(node)
                    idx
                }
            }
        }

        val topIndex = flatten(root)
        val numObjects = objects.size
        val objectRefSize = minBytesForUnsigned(numObjects.toLong())

        val out = ByteArrayOutputStream(512)
        out.write(MAGIC)
        val offsets = IntArray(numObjects)

        fun writeCountHeader(kindNibble: Int, count: Int) {
            if (count < 15) {
                out.write((kindNibble shl 4) or count)
            } else {
                out.write((kindNibble shl 4) or 0x0F)
                writeIntObject(out, count.toLong())
            }
        }

        for (i in 0 until numObjects) {
            offsets[i] = out.size()
            when (val obj = objects[i]) {
                null -> out.write(0x00)
                is Boolean -> out.write(if (obj) 0x09 else 0x08)
                is Byte -> writeIntObject(out, obj.toLong())
                is Short -> writeIntObject(out, obj.toLong())
                is Int -> writeIntObject(out, obj.toLong())
                is Long -> writeIntObject(out, obj)
                is Float -> {
                    out.write(0x22)
                    val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(obj)
                    out.write(bb.array())
                }
                is Double -> {
                    out.write(0x23)
                    val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(obj)
                    out.write(bb.array())
                }
                is ByteArray -> {
                    writeCountHeader(0x4, obj.size)
                    out.write(obj)
                }
                is String -> {
                    val isAscii = obj.all { it.code in 0..127 }
                    if (isAscii) {
                        val bytes = obj.toByteArray(StandardCharsets.US_ASCII)
                        writeCountHeader(0x5, bytes.size)
                        out.write(bytes)
                    } else {
                        val bytes = obj.toByteArray(StandardCharsets.UTF_16BE)
                        writeCountHeader(0x6, obj.length)
                        out.write(bytes)
                    }
                }
                is MappedArray -> {
                    writeCountHeader(0xA, obj.refs.size)
                    for (ref in obj.refs) {
                        writeUnsignedBE(out, ref.toLong(), objectRefSize)
                    }
                }
                is MappedDict -> {
                    writeCountHeader(0xD, obj.entries.size)
                    for ((kRef, _) in obj.entries) {
                        writeUnsignedBE(out, kRef.toLong(), objectRefSize)
                    }
                    for ((_, vRef) in obj.entries) {
                        writeUnsignedBE(out, vRef.toLong(), objectRefSize)
                    }
                }
                else -> {
                    val bytes = obj.toString().toByteArray(StandardCharsets.UTF_8)
                    writeCountHeader(0x5, bytes.size)
                    out.write(bytes)
                }
            }
        }

        val offsetTableOffset = out.size()
        val maxOffset = offsets.maxOrNull() ?: 0
        val offsetIntSize = minBytesForUnsigned(maxOffset.toLong())
        for (off in offsets) {
            writeUnsignedBE(out, off.toLong(), offsetIntSize)
        }

        val trailer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        trailer.position(6)
        trailer.put(offsetIntSize.toByte())
        trailer.put(objectRefSize.toByte())
        trailer.putLong(numObjects.toLong())
        trailer.putLong(topIndex.toLong())
        trailer.putLong(offsetTableOffset.toLong())
        out.write(trailer.array())
        return out.toByteArray()
    }

    private class MappedArray(val refs: IntArray)
    private class MappedDict(val entries: List<Pair<Int, Int>>)

    private fun writeIntObject(out: ByteArrayOutputStream, v: Long) {
        when {
            v in 0..0xFF -> {
                out.write(0x10)
                out.write(v.toInt() and 0xFF)
            }
            v in 0..0xFFFF -> {
                out.write(0x11)
                writeUnsignedBE(out, v, 2)
            }
            v in 0..0xFFFFFFFFL -> {
                out.write(0x12)
                writeUnsignedBE(out, v, 4)
            }
            else -> {
                out.write(0x13)
                val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v)
                out.write(bb.array())
            }
        }
    }

    private fun minBytesForUnsigned(v: Long): Int = when {
        v <= 0xFFL -> 1
        v <= 0xFFFFL -> 2
        v <= 0xFFFFFFFFL -> 4
        else -> 8
    }

    private fun writeUnsignedBE(out: ByteArrayOutputStream, v: Long, width: Int) {
        for (shift in (width - 1) downTo 0) {
            out.write(((v ushr (shift * 8)) and 0xFFL).toInt())
        }
    }

    private fun readUnsignedBE(data: ByteArray, offset: Int, width: Int): Long {
        var acc = 0L
        for (i in 0 until width) {
            acc = (acc shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return acc
    }
}
