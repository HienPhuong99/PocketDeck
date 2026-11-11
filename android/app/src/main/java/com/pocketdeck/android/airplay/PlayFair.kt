package com.pocketdeck.android.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pure-Kotlin port of the open-source "playfair" FairPlay SAP decryptor.
 *
 * Port of omg_hax.c / sap_hash.c / modified_md5.c / playfair.c (GPL-3.0, see
 * licenses/playfair-LICENSE.md). Recovers the 16-byte AES session key that an
 * AirPlay sender transmits FairPlay-encrypted (the 72-byte "ekey") during SETUP.
 */
class FairPlay {
    private var keyMsg: ByteArray? = null

    /** First /fp-setup (16 bytes) -> 142-byte canned reply for the requested mode. */
    fun setup(req: ByteArray): ByteArray {
        if (req.size != 16 || (req[4].toInt() and 0xFF) != 0x03) {
            throw IllegalArgumentException("Unsupported FairPlay version")
        }
        val mode = req[14].toInt() and 0xFF
        if (mode > 3) {
            throw IllegalArgumentException("Unsupported FairPlay mode $mode")
        }
        return FairPlayTables.REPLY_MESSAGE.copyOfRange(mode * 142, (mode + 1) * 142)
    }

    /** Second /fp-setup (164 bytes) -> 32-byte reply; stores keyMsg for SETUP ekey decryption. */
    fun handshake(req: ByteArray): ByteArray {
        if (req.size != 164 || (req[4].toInt() and 0xFF) != 0x03) {
            throw IllegalArgumentException("Bad FairPlay handshake")
        }
        keyMsg = req.copyOf()
        return FairPlayTables.FP_HEADER + req.copyOfRange(144, 164)
    }

    /** Decrypts the 72-byte ekey from RTSP SETUP into the 16-byte raw AES key. */
    fun decrypt(ekey: ByteArray): ByteArray {
        val msg = keyMsg ?: throw IllegalStateException("FairPlay handshake not completed")
        return PlayFairCrypto.playfairDecrypt(msg, ekey)
    }
}

object PlayFairCrypto {
    private const val M32 = 0xFFFFFFFFL

    private val MD5_SHIFT = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    private val MD5_K = LongArray(64) { i ->
        ((1L shl 32).toDouble() * abs(sin((i + 1).toDouble()))).toLong() and M32
    }

    private fun ByteArray.toU8(): IntArray = IntArray(size) { this[it].toInt() and 0xFF }
    private fun IntArray.toBytes(): ByteArray = ByteArray(size) { (this[it] and 0xFF).toByte() }

    private fun xor16(a: IntArray, b: IntArray): IntArray =
        IntArray(16) { (a[it] xor b[it]) and 0xFF }

    private fun zXor(block: IntArray): IntArray = xor16(block, FairPlayTables.Z_KEY)
    private fun xXor(block: IntArray): IntArray = xor16(block, FairPlayTables.X_KEY)

    private fun words16(b: IntArray): LongArray = LongArray(4) { k ->
        val o = 4 * k
        ((b[o] and 0xFF).toLong()) or
            (((b[o + 1] and 0xFF).toLong()) shl 8) or
            (((b[o + 2] and 0xFF).toLong()) shl 16) or
            (((b[o + 3] and 0xFF).toLong()) shl 24)
    }

    private fun putWord(b: IntArray, k: Int, w: Long) {
        val v = w and M32
        val o = 4 * k
        b[o] = (v and 0xFFL).toInt()
        b[o + 1] = ((v ushr 8) and 0xFFL).toInt()
        b[o + 2] = ((v ushr 16) and 0xFFL).toInt()
        b[o + 3] = ((v ushr 24) and 0xFFL).toInt()
    }

    private fun wordBytesLE(w: Long): IntArray {
        val v = w and M32
        return intArrayOf(
            (v and 0xFFL).toInt(),
            ((v ushr 8) and 0xFFL).toInt(),
            ((v ushr 16) and 0xFFL).toInt(),
            ((v ushr 24) and 0xFFL).toInt(),
        )
    }

    private fun rol8Byte(x: Int, n: Int): Int {
        val xv = x and 0xFF
        return (((xv shl n) and 0xFF) or (xv ushr (8 - n))) and 0xFF
    }

    private fun rol32(x: Long, n: Int): Long {
        val xv = x and M32
        return ((xv shl n) and M32) or (xv ushr (32 - n))
    }

    private fun tableIndex(i: Int): Int = ((31 * i) % 0x28) shl 8
    private fun messageTableIndex(i: Int): Int = ((97 * i) % 144) shl 8
    private fun permuteTable2(i: Int): Int = ((71 * i) % 144) shl 8

    private fun permuteBlock1(b: IntArray) {
        val s3 = FairPlayTables.TABLE_S3
        b[0] = s3[b[0]]
        b[4] = s3[0x400 + b[4]]
        b[8] = s3[0x800 + b[8]]
        b[12] = s3[0xC00 + b[12]]
        var tmp = b[13]
        b[13] = s3[0x100 + b[9]]
        b[9] = s3[0xD00 + b[5]]
        b[5] = s3[0x900 + b[1]]
        b[1] = s3[0x500 + tmp]
        tmp = b[2]
        b[2] = s3[0xA00 + b[10]]
        b[10] = s3[0x200 + tmp]
        tmp = b[6]
        b[6] = s3[0xE00 + b[14]]
        b[14] = s3[0x600 + tmp]
        tmp = b[3]
        b[3] = s3[0xF00 + b[7]]
        b[7] = s3[0x300 + b[11]]
        b[11] = s3[0x700 + b[15]]
        b[15] = s3[0xB00 + tmp]
    }

    private fun permuteBlock2(b: IntArray, rnd: Int) {
        val s4 = FairPlayTables.TABLE_S4
        fun p(k: Int, v: Int): Int = s4[permuteTable2(rnd * 16 + k) + v]

        b[0] = p(0, b[0])
        b[4] = p(4, b[4])
        b[8] = p(8, b[8])
        b[12] = p(12, b[12])
        var tmp = b[13]
        b[13] = p(13, b[9])
        b[9] = p(9, b[5])
        b[5] = p(5, b[1])
        b[1] = p(1, tmp)
        tmp = b[2]
        b[2] = p(2, b[10])
        b[10] = p(10, tmp)
        tmp = b[6]
        b[6] = p(6, b[14])
        b[14] = p(14, tmp)
        tmp = b[3]
        b[3] = p(3, b[7])
        b[7] = p(7, b[11])
        b[11] = p(11, b[15])
        b[15] = p(15, tmp)
    }

    private fun generateKeySchedule(keyMaterial: IntArray): Array<LongArray> {
        val buf = xor16(keyMaterial, FairPlayTables.T_KEY)
        val s1 = FairPlayTables.TABLE_S1
        val sched = Array(11) { LongArray(4) }
        var ti = 0
        for (rnd in 0 until 11) {
            val row = sched[rnd]
            row[0] = words16(buf)[0]
            val t1 = tableIndex(ti)
            val t2 = tableIndex(ti + 1)
            val t3 = tableIndex(ti + 2)
            val t4 = tableIndex(ti + 3)
            ti += 4
            buf[0] = (buf[0] xor s1[t1 + buf[0x0D]] xor FairPlayTables.INDEX_MANGLE[rnd]) and 0xFF
            buf[1] = (buf[1] xor s1[t2 + buf[0x0E]]) and 0xFF
            buf[2] = (buf[2] xor s1[t3 + buf[0x0F]]) and 0xFF
            buf[3] = (buf[3] xor s1[t4 + buf[0x0C]]) and 0xFF
            val w = words16(buf)
            row[1] = w[1]
            w[1] = (w[1] xor w[0]) and M32
            row[2] = w[2]
            w[2] = (w[2] xor w[1]) and M32
            row[3] = w[3]
            w[3] = (w[3] xor w[2]) and M32
            for (k in 0 until 4) {
                putWord(buf, k, w[k])
            }
        }
        return sched
    }

    private fun IntArray.u32(idx: Int): Long = this[idx].toLong() and M32

    private fun cycle(block: IntArray, sched: Array<LongArray>) {
        val s5 = FairPlayTables.TABLE_S5
        val s6 = FairPlayTables.TABLE_S6
        val s7 = FairPlayTables.TABLE_S7
        val s8 = FairPlayTables.TABLE_S8
        var w = words16(block)
        for (k in 0 until 4) {
            putWord(block, k, w[k] xor sched[10][k])
        }
        permuteBlock1(block)
        for (rnd in 0 until 9) {
            val k0 = wordBytesLE(sched[9 - rnd][0])
            val k1 = wordBytesLE(sched[9 - rnd][1])
            val k2 = wordBytesLE(sched[9 - rnd][2])
            val k3 = wordBytesLE(sched[9 - rnd][3])

            val ab0 = s5.u32(block[3] xor k0[3]) xor
                s6.u32(block[2] xor k0[2]) xor
                s8.u32(block[0] xor k0[0]) xor
                s7.u32(block[1] xor k0[1])
            putWord(block, 0, ab0)

            val ab1 = s6.u32(block[6] xor k1[2]) xor
                s5.u32(block[7] xor k1[3]) xor
                s8.u32(block[4] xor k1[0]) xor
                s7.u32(block[5] xor k1[1])
            putWord(block, 1, ab1)

            val ab2 = s5.u32(block[11] xor k2[3]) xor
                s6.u32(block[10] xor k2[2]) xor
                s7.u32(block[9] xor k2[1]) xor
                s8.u32(block[8] xor k2[0])
            putWord(block, 2, ab2)

            val ab3 = s5.u32(block[15] xor k3[3]) xor
                s6.u32(block[14] xor k3[2]) xor
                s7.u32(block[13] xor k3[1]) xor
                s8.u32(block[12] xor k3[0])
            putWord(block, 3, ab3)

            permuteBlock2(block, 8 - rnd)
        }
        w = words16(block)
        for (k in 0 until 4) {
            putWord(block, k, w[k] xor sched[0][k])
        }
    }

    private fun decryptMessage(messageIn: IntArray): IntArray {
        val mode = messageIn[12]
        if (mode !in 0..3) {
            throw IllegalArgumentException("Unsupported FairPlay mode $mode")
        }
        val s2 = FairPlayTables.TABLE_S2
        val s9 = FairPlayTables.TABLE_S9
        val s10 = FairPlayTables.TABLE_S10
        val mkey = FairPlayTables.MESSAGE_KEY.copyOfRange(mode * 144, (mode + 1) * 144)
        val miv = FairPlayTables.MESSAGE_IV.copyOfRange(mode * 16, (mode + 1) * 16)
        val out = IntArray(128)

        for (i in 0 until 8) {
            val buf = if (mode == 3) {
                val start = 0x80 - 0x10 * i
                messageIn.copyOfRange(start, start + 16)
            } else {
                val start = 0x10 * (i + 1)
                messageIn.copyOfRange(start, start + 16)
            }

            for (j in 0 until 9) {
                val base = 0x80 - 0x10 * j
                fun m(k: Int, v: Int): Int = (s2[messageTableIndex(base + k) + v] xor mkey[base + k]) and 0xFF

                buf[0x0] = m(0x0, buf[0x0])
                buf[0x4] = m(0x4, buf[0x4])
                buf[0x8] = m(0x8, buf[0x8])
                buf[0xC] = m(0xC, buf[0xC])
                var tmp = buf[0x0D]
                buf[0xD] = m(0xD, buf[0x9])
                buf[0x9] = m(0x9, buf[0x5])
                buf[0x5] = m(0x5, buf[0x1])
                buf[0x1] = m(0x1, tmp)
                tmp = buf[0x02]
                buf[0x2] = m(0x2, buf[0xA])
                buf[0xA] = m(0xA, tmp)
                tmp = buf[0x06]
                buf[0x6] = m(0x6, buf[0xE])
                buf[0xE] = m(0xE, tmp)
                tmp = buf[0x3]
                buf[0x3] = m(0x3, buf[0x7])
                buf[0x7] = m(0x7, buf[0xB])
                buf[0xB] = m(0xB, buf[0xF])
                buf[0xF] = m(0xF, tmp)

                for (k in 0 until 4) {
                    val o = 4 * k
                    val word = s9.u32(buf[o]) xor
                        s9.u32(0x100 + buf[o + 1]) xor
                        s9.u32(0x200 + buf[o + 2]) xor
                        s9.u32(0x300 + buf[o + 3])
                    putWord(buf, k, word)
                }
            }

            buf[0x0] = s10[(0x0 shl 8) + buf[0x0]]
            buf[0x4] = s10[(0x4 shl 8) + buf[0x4]]
            buf[0x8] = s10[(0x8 shl 8) + buf[0x8]]
            buf[0xC] = s10[(0xC shl 8) + buf[0xC]]
            var tmp = buf[0x0D]
            buf[0xD] = s10[(0xD shl 8) + buf[0x9]]
            buf[0x9] = s10[(0x9 shl 8) + buf[0x5]]
            buf[0x5] = s10[(0x5 shl 8) + buf[0x1]]
            buf[0x1] = s10[(0x1 shl 8) + tmp]
            tmp = buf[0x02]
            buf[0x2] = s10[(0x2 shl 8) + buf[0xA]]
            buf[0xA] = s10[(0xA shl 8) + tmp]
            tmp = buf[0x06]
            buf[0x6] = s10[(0x6 shl 8) + buf[0xE]]
            buf[0xE] = s10[(0xE shl 8) + tmp]
            tmp = buf[0x3]
            buf[0x3] = s10[(0x3 shl 8) + buf[0x7]]
            buf[0x7] = s10[(0x7 shl 8) + buf[0xB]]
            buf[0xB] = s10[(0xB shl 8) + buf[0xF]]
            buf[0xF] = s10[(0xF shl 8) + tmp]

            if (mode in 0..2) {
                val src = if (i > 0) messageIn.copyOfRange(0x10 * i, 0x10 * i + 16) else miv
                val x = xor16(buf, src)
                System.arraycopy(x, 0, out, 0x10 * i, 16)
            } else {
                val pos = 0x70 - 0x10 * i
                val src = if (i < 7) messageIn.copyOfRange(pos, pos + 16) else miv
                val x = xor16(buf, src)
                System.arraycopy(x, 0, out, pos, 16)
            }
        }
        return out
    }

    private fun modifiedMd5(blockIn: IntArray, keyIn: IntArray): IntArray {
        val blk = blockIn.copyOfRange(0, 64)
        val keyWords = words16(keyIn)
        var a = keyWords[0]
        var b = keyWords[1]
        var c = keyWords[2]
        var d = keyWords[3]

        fun swap(i: Int, j: Int) {
            for (k in 0 until 4) {
                val t = blk[4 * i + k]
                blk[4 * i + k] = blk[4 * j + k]
                blk[4 * j + k] = t
            }
        }

        for (i in 0 until 64) {
            val j = when {
                i < 16 -> i
                i < 32 -> (5 * i + 1) % 16
                i < 48 -> (3 * i + 5) % 16
                else -> (7 * i) % 16
            }
            val inp = (
                ((blk[4 * j].toLong() and 0xFFL) shl 24) or
                    ((blk[4 * j + 1].toLong() and 0xFFL) shl 16) or
                    ((blk[4 * j + 2].toLong() and 0xFFL) shl 8) or
                    (blk[4 * j + 3].toLong() and 0xFFL)
                ) and M32
            var z = (a + inp + MD5_K[i]) and M32
            val f = when {
                i < 16 -> (b and c) or (b.inv() and M32 and d)
                i < 32 -> (b and d) or (c and (d.inv() and M32))
                i < 48 -> (b xor c xor d) and M32
                else -> (c xor ((b or (d.inv() and M32)) and M32)) and M32
            }
            z = rol32((z + f) and M32, MD5_SHIFT[i])
            z = (z + b) and M32
            val nextA = d
            val nextB = z
            val nextC = b
            val nextD = c
            a = nextA
            b = nextB
            c = nextC
            d = nextD

            if (i == 31) {
                swap((a and 15L).toInt(), (b and 15L).toInt())
                swap((c and 15L).toInt(), (d and 15L).toInt())
                swap(((a ushr 4) and 15L).toInt(), ((b ushr 4) and 15L).toInt())
                swap(((a ushr 8) and 15L).toInt(), ((b ushr 8) and 15L).toInt())
                swap(((a ushr 12) and 15L).toInt(), ((b ushr 12) and 15L).toInt())
            }
        }

        val out = IntArray(16)
        putWord(out, 0, (keyWords[0] + a) and M32)
        putWord(out, 1, (keyWords[1] + b) and M32)
        putWord(out, 2, (keyWords[2] + c) and M32)
        putWord(out, 3, (keyWords[3] + d) and M32)
        return out
    }

    private fun sapHash(blockIn: IntArray): IntArray {
        val buffer0 = FairPlayTables.SAP_BUFFER0.toU8()
        val buffer1 = IntArray(210)
        val buffer2 = FairPlayTables.SAP_BUFFER2.toU8()
        val buffer3 = IntArray(132)
        val buffer4 = FairPlayTables.SAP_BUFFER4.toU8()

        for (i in 0 until 210) {
            val w = (i % 64) ushr 2
            buffer1[i] = blockIn[4 * w + 3 - (i % 4)]
        }
        for (i in 0 until 840) {
            val il = i.toLong()
            val x = buffer1[(((il - 155L) and M32) % 210L).toInt()]
            val y = buffer1[(((il - 57L) and M32) % 210L).toInt()]
            val z = buffer1[(((il - 13L) and M32) % 210L).toInt()]
            val w = buffer1[i % 210]
            buffer1[i % 210] = (rol8Byte(y, 5) + (rol8Byte(z, 3) xor w) - rol8Byte(x, 7)) and 0xFF
        }

        fairplayGarble(
            UCharBuf(buffer0, trackWrites = false),
            UCharBuf(buffer1),
            UCharBuf(buffer2),
            UCharBuf(buffer3, trackWrites = true),
            UCharBuf(buffer4),
        )

        val key = IntArray(16) { 0xE1 }
        for (i in 0 until 11) {
            if (i == 3) {
                key[i] = 0x3D
            } else {
                key[i] = (key[i] + buffer3[FairPlayTables.SAP_I0_INDEX[i] * 4]) and 0xFF
            }
        }
        for (i in 0 until 20) key[i % 16] = (key[i % 16] xor buffer0[i]) and 0xFF
        for (i in 0 until 35) key[i % 16] = (key[i % 16] xor buffer2[i]) and 0xFF
        for (i in 0 until 210) key[i % 16] = (key[i % 16] xor buffer1[i]) and 0xFF

        repeat(16) {
            for (i in 0 until 16) {
                val il = i.toLong()
                val x = key[(((il - 7L) and M32) % 16L).toInt()]
                val y = key[i % 16]
                val z = key[(((il - 37L) and M32) % 16L).toInt()]
                val w = key[(((il - 177L) and M32) % 16L).toInt()]
                key[i] = (rol8Byte(x, 1) xor y xor rol8Byte(z, 6) xor rol8Byte(w, 5)) and 0xFF
            }
        }
        return key
    }

    private fun generateSessionKey(oldSap: IntArray, messageIn: IntArray): IntArray {
        val dm = decryptMessage(messageIn)
        val newSap = IntArray(320)
        val ss1 = FairPlayTables.STATIC_SOURCE_1.toU8()
        val ss2 = FairPlayTables.STATIC_SOURCE_2.toU8()
        System.arraycopy(ss1, 0, newSap, 0x000, 0x011)
        System.arraycopy(dm, 0, newSap, 0x011, 0x080)
        System.arraycopy(oldSap, 0x080, newSap, 0x091, 0x080)
        System.arraycopy(ss2, 0, newSap, 0x111, 0x02F)

        var sessionKey = FairPlayTables.INITIAL_SESSION_KEY.toU8()
        for (rnd in 0 until 5) {
            val base = newSap.copyOfRange(rnd * 64, rnd * 64 + 64)
            val md5 = modifiedMd5(base, sessionKey)
            val sk = sapHash(base)
            val skw = words16(sk)
            val mdw = words16(md5)
            val next = IntArray(16)
            for (k in 0 until 4) {
                putWord(next, k, (skw[k] + mdw[k]) and M32)
            }
            sessionKey = next
        }

        val out = sessionKey.copyOf()
        for (i in 0 until 16 step 4) {
            val t0 = out[i]
            out[i] = out[i + 3]
            out[i + 3] = t0
            val t1 = out[i + 1]
            out[i + 1] = out[i + 2]
            out[i + 2] = t1
        }
        return IntArray(16) { (out[it] xor 121) and 0xFF }
    }

    /**
     * Recovers the 16-byte AES key from the 164-byte second fp-setup request (message3)
     * and the 72-byte FairPlay-encrypted ekey (cipherText).
     */
    fun playfairDecrypt(message3: ByteArray, cipherText: ByteArray): ByteArray {
        require(message3.size == 164 && cipherText.size == 72) {
            "Bad FairPlay input sizes: msg3=${message3.size}, cipher=${cipherText.size}"
        }
        val ct = cipherText.toU8()
        val chunk1 = ct.copyOfRange(16, 32)
        val chunk2 = ct.copyOfRange(56, 72)
        val sapKey = generateSessionKey(FairPlayTables.DEFAULT_SAP.toU8(), message3.toU8())
        val sched = generateKeySchedule(sapKey)
        val block = zXor(chunk2)
        cycle(block, sched)
        val key = xor16(block, chunk1)
        return zXor(xXor(key)).toBytes()
    }
}
