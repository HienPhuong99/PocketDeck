package com.pocketdeck.android.airplay

import kotlin.math.abs

/**
 * 32-bit C integer & uchar[] emulation + translated FairPlay SAP garble() function.
 * Ported from open-source playfair hand_garble.c (GPL-3.0, see licenses/playfair-LICENSE.md).
 */
internal class CInt(raw: Long, val u: Boolean) {
    val v: Long

    init {
        val masked = raw and 0xFFFFFFFFL
        v = if (!u && (masked and 0x80000000L) != 0L) masked - 0x100000000L else masked
    }

    fun add(o: CInt): CInt = CInt(v + o.v, u || o.u)
    fun sub(o: CInt): CInt = CInt(v - o.v, u || o.u)
    fun mul(o: CInt): CInt = CInt(v * o.v, u || o.u)

    fun div(o: CInt): CInt {
        val ru = u || o.u
        if (ru) {
            return CInt((v and 0xFFFFFFFFL) / (o.v and 0xFFFFFFFFL), true)
        }
        var q = abs(v) / abs(o.v)
        if ((v < 0L) != (o.v < 0L)) q = -q
        return CInt(q, false)
    }

    fun rem(o: CInt): CInt {
        val ru = u || o.u
        if (ru) {
            return CInt((v and 0xFFFFFFFFL) % (o.v and 0xFFFFFFFFL), true)
        }
        val q = div(o).v
        return CInt(v - o.v * q, false)
    }

    fun bitAnd(o: CInt): CInt = CInt(v and o.v, u || o.u)
    fun bitOr(o: CInt): CInt = CInt(v or o.v, u || o.u)
    fun bitXor(o: CInt): CInt = CInt(v xor o.v, u || o.u)

    fun shl(o: CInt): CInt {
        val n = o.v.toInt()
        require(n in 0..31) { "shift out of range: $n" }
        return CInt(v shl n, u)
    }

    fun shr(o: CInt): CInt {
        val n = o.v.toInt()
        require(n in 0..31) { "shift out of range: $n" }
        return if (u) {
            CInt((v and 0xFFFFFFFFL) ushr n, true)
        } else {
            CInt(v shr n, false)
        }
    }

    fun inv(): CInt = CInt(v.inv(), u)
    fun neg(): CInt = CInt(-v, u)
}

internal fun cL(n: Long): CInt = CInt(n, n > 0x7FFFFFFFL)
internal fun cU(x: CInt): CInt = CInt(x.v, true)

internal class UCharBuf(val b: IntArray, trackWrites: Boolean = false) {
    private val written: MutableSet<Int>? = if (trackWrites) HashSet() else null

    private fun checkIdx(i: Int): Int {
        if (i !in b.indices) throw IndexOutOfBoundsException("index $i out of range ${b.size}")
        return i
    }

    fun get(i: Long): CInt = getIdx(i.toInt())
    fun get(i: CInt): CInt = getIdx(i.v.toInt())

    private fun getIdx(rawIdx: Int): CInt {
        val idx = checkIdx(rawIdx)
        if (written != null && idx !in written) {
            throw IllegalStateException("read of uninitialised element $idx")
        }
        return CInt((b[idx] and 0xFF).toLong(), false)
    }

    fun set(i: Long, v: CInt) = setIdx(i.toInt(), v)
    fun set(i: CInt, v: CInt) = setIdx(i.v.toInt(), v)

    private fun setIdx(rawIdx: Int, value: CInt) {
        val idx = checkIdx(rawIdx)
        b[idx] = value.v.toInt() and 0xFF
        written?.add(idx)
    }
}

private fun u8(x: CInt): Long = x.v and 0xFFL
private fun countOf(x: CInt): Int = x.v.toInt()

internal fun rol8(x: CInt, n: CInt): CInt {
    val xv = u8(x)
    val nv = countOf(n)
    return CInt((((xv shl nv) and 0xFFL) or (xv ushr (8 - nv))) and 0xFFL, false)
}

internal fun rol8x(x: CInt, n: CInt): CInt {
    val xv = u8(x)
    val nv = countOf(n)
    return CInt((xv shl nv) or (xv ushr (8 - nv)), true)
}

internal fun weirdRor8(x: CInt, n: CInt): CInt {
    val xv = u8(x)
    val nv = countOf(n)
    if (nv == 0) return CInt(0L, true)
    return CInt(((xv ushr nv) and 0xFFL) or (xv shl (8 - nv)), true)
}

internal fun weirdRol8(x: CInt, n: CInt): CInt {
    val xv = u8(x)
    val nv = countOf(n)
    if (nv == 0) return CInt(0L, true)
    return CInt(((xv shl nv) and 0xFFL) or (xv ushr (8 - nv)), true)
}

internal fun weirdRol32(x: CInt, n: CInt): CInt {
    val xv = u8(x)
    val nv = countOf(n)
    if (nv == 0) return CInt(0L, true)
    return CInt((xv shl nv) xor (xv ushr (8 - nv)), true)
}

internal fun fairplayGarble(
    buffer0: UCharBuf,
    buffer1: UCharBuf,
    buffer2: UCharBuf,
    buffer3: UCharBuf,
    buffer4: UCharBuf,
) {
    buffer2.set(12L, cL(20L).add(buffer1.get(cL(64L)).bitAnd(cL(92L)).bitOr(buffer1.get(cL(99L)).div(cL(3L)).bitAnd(cL(35L))).bitAnd(buffer4.get(rol8x(buffer4.get(buffer1.get(cL(206L)).rem(cL(21L))), cL(4L)).rem(cL(21L))))))
    buffer1.set(4L, buffer1.get(cL(99L)).div(cL(5L)).mul(buffer1.get(cL(99L)).div(cL(5L))).mul(cL(2L)))
    buffer2.set(34L, cL(184L))
    buffer1.set(153L, buffer1.get(153L).bitXor(buffer2.get(buffer1.get(cL(203L)).rem(cL(35L))).mul(buffer2.get(buffer1.get(cL(203L)).rem(cL(35L)))).mul(buffer1.get(cL(190L)))))
    buffer0.set(3L, buffer0.get(3L).sub(buffer4.get(buffer1.get(cL(205L)).rem(cL(21L))).shr(cL(1L)).bitAnd(cL(80L)).bitOr(cL(943168L))))
    buffer0.set(16L, cL(147L))
    buffer0.set(13L, cL(98L))
    buffer1.set(33L, buffer1.get(33L).sub(buffer4.get(buffer1.get(cL(36L)).rem(cL(21L))).bitAnd(cL(246L))))
    var tmp2: CInt = cU(buffer2.get(buffer1.get(cL(67L)).rem(cL(35L))))
    buffer2.set(12L, cL(7L))
    var tmp: CInt = cU(buffer0.get(buffer1.get(cL(181L)).rem(cL(20L))))
    buffer1.set(2L, buffer1.get(2L).sub(cL(3136L)))
    buffer0.set(19L, buffer4.get(buffer1.get(cL(58L)).rem(cL(21L))))
    buffer3.set(0L, cL(92L).sub(buffer2.get(buffer1.get(cL(32L)).rem(cL(35L)))))
    buffer3.set(4L, buffer2.get(buffer1.get(cL(15L)).rem(cL(35L))).add(cL(158L)))
    buffer1.set(34L, buffer1.get(34L).add(buffer4.get(buffer2.get(buffer1.get(cL(15L)).rem(cL(35L))).add(cL(158L)).bitAnd(cL(255L)).rem(cL(21L))).div(cL(5L))))
    buffer0.set(19L, buffer0.get(19L).add(cL(4294967014L).sub(buffer0.get(buffer3.get(cL(4L)).rem(cL(20L))).shr(cL(1L)).bitAnd(cL(102L)))))
    buffer1.set(15L, cL(3L).mul(buffer1.get(cL(72L)).shr(buffer4.get(buffer1.get(cL(190L)).rem(cL(21L))).bitAnd(cL(7L))).bitXor(buffer1.get(cL(72L)).shl(cL(7L).sub(buffer4.get(buffer1.get(cL(190L)).rem(cL(21L))).sub(cL(1L))).bitAnd(cL(7L)))).sub(cL(3L).mul(buffer4.get(buffer1.get(cL(126L)).rem(cL(21L)))))).bitXor(buffer1.get(cL(15L))))
    buffer0.set(15L, buffer0.get(15L).bitXor(buffer2.get(buffer1.get(cL(181L)).rem(cL(35L))).mul(buffer2.get(buffer1.get(cL(181L)).rem(cL(35L)))).mul(buffer2.get(buffer1.get(cL(181L)).rem(cL(35L))))))
    buffer2.set(4L, buffer2.get(4L).bitXor(buffer1.get(cL(202L)).div(cL(3L))))
    var A: CInt = cU(cL(92L).sub(buffer0.get(buffer3.get(cL(0L)).rem(cL(20L)))))
    var E: CInt = cU(A.bitAnd(cL(198L)).bitOr(buffer1.get(cL(105L)).inv().bitAnd(cL(198L))).bitOr(A.bitAnd(buffer1.get(cL(105L)).inv())))
    buffer2.set(1L, buffer2.get(1L).add(E.mul(E).mul(E)))
    buffer0.set(19L, buffer0.get(19L).bitXor(cL(224L).bitOr(buffer4.get(buffer1.get(cL(92L)).rem(cL(21L))).bitAnd(cL(27L))).mul(buffer2.get(buffer1.get(cL(41L)).rem(cL(35L)))).div(cL(3L))))
    buffer1.set(140L, buffer1.get(140L).add(weirdRor8(cL(92L), buffer1.get(cL(5L)).bitAnd(cL(7L)))))
    buffer2.set(12L, buffer2.get(12L).add(buffer1.get(cL(4L)).inv().bitXor(buffer2.get(buffer1.get(cL(12L)).rem(cL(35L)))).bitOr(buffer1.get(cL(182L))).bitAnd(cL(192L)).bitOr(buffer1.get(cL(4L)).inv().bitXor(buffer2.get(buffer1.get(cL(12L)).rem(cL(35L)))).bitAnd(buffer1.get(cL(182L))))))
    buffer1.set(36L, buffer1.get(36L).add(cL(125L)))
    buffer1.set(124L, rol8x(cL(74L).bitAnd(buffer1.get(cL(138L))).bitOr(cL(74L).bitOr(buffer1.get(cL(138L))).bitAnd(buffer0.get(cL(15L)))).bitAnd(buffer0.get(buffer1.get(cL(43L)).rem(cL(20L)))).bitOr(cL(74L).bitAnd(buffer1.get(cL(138L))).bitOr(cL(74L).bitOr(buffer1.get(cL(138L))).bitAnd(buffer0.get(cL(15L)))).bitOr(buffer0.get(buffer1.get(cL(43L)).rem(cL(20L)))).bitAnd(cL(95L))), cL(4L)))
    buffer3.set(8L, buffer0.get(buffer3.get(cL(4L)).rem(cL(20L))).bitAnd(cL(95L)).bitAnd(buffer4.get(buffer1.get(cL(68L)).rem(cL(21L))).bitAnd(cL(46L)).shl(cL(1L))).bitOr(cL(16L)).bitXor(cL(92L)))
    A = cU(buffer1.get(cL(177L)).add(buffer4.get(buffer1.get(cL(79L)).rem(cL(21L)))))
    var D: CInt = cU(A.shr(cL(1L)).bitOr(cL(3L).mul(buffer1.get(cL(148L))).div(cL(5L))).bitAnd(buffer2.get(cL(1L))).bitOr(A.shr(cL(1L)).bitAnd(cL(3L).mul(buffer1.get(cL(148L))).div(cL(5L)))))
    buffer3.set(12L, cL(34L).neg().sub(D))
    A = cU(cL(8L).sub(buffer2.get(cL(22L)).bitAnd(cL(7L))))
    var B: CInt = cU(buffer1.get(cL(33L)).shr(A.bitAnd(cL(7L))))
    var C: CInt = cU(buffer1.get(cL(33L)).shl(buffer2.get(cL(22L)).bitAnd(cL(7L))))
    buffer2.set(16L, buffer2.get(16L).add(buffer2.get(buffer3.get(cL(0L)).rem(cL(35L))).bitAnd(cL(159L)).bitOr(buffer0.get(buffer3.get(cL(4L)).rem(cL(20L)))).bitOr(cL(8L)).sub(B.bitXor(C).bitOr(cL(128L)))))
    buffer0.set(14L, buffer0.get(14L).bitXor(buffer2.get(buffer3.get(cL(12L)).rem(cL(35L)))))
    A = cU(weirdRol8(buffer4.get(buffer0.get(buffer1.get(cL(201L)).rem(cL(20L))).rem(cL(21L))), buffer2.get(buffer1.get(cL(112L)).rem(cL(35L))).shl(cL(1L)).bitAnd(cL(7L))))
    D = cU(buffer0.get(buffer1.get(cL(208L)).rem(cL(20L))).bitAnd(cL(131L)).bitOr(buffer0.get(buffer1.get(cL(164L)).rem(cL(20L))).bitAnd(cL(124L))))
    buffer1.set(19L, buffer1.get(19L).add(A.bitAnd(D.div(cL(5L))).bitOr(A.bitOr(D.div(cL(5L))).bitAnd(cL(37L)))))
    buffer2.set(8L, weirdRor8(cL(140L), buffer4.get(buffer1.get(cL(45L)).rem(cL(21L))).add(cL(92L)).mul(buffer4.get(buffer1.get(cL(45L)).rem(cL(21L))).add(cL(92L))).bitAnd(cL(7L))))
    buffer1.set(190L, cL(56L))
    buffer2.set(8L, buffer2.get(8L).bitXor(buffer3.get(cL(0L))))
    buffer1.set(53L, buffer0.get(buffer1.get(cL(83L)).rem(cL(20L))).bitOr(cL(204L)).div(cL(5L)).inv())
    buffer0.set(13L, buffer0.get(13L).add(buffer0.get(buffer1.get(cL(41L)).rem(cL(20L)))))
    buffer0.set(10L, buffer2.get(buffer3.get(cL(0L)).rem(cL(35L))).bitAnd(buffer1.get(cL(2L))).bitOr(buffer2.get(buffer3.get(cL(0L)).rem(cL(35L))).bitOr(buffer1.get(cL(2L))).bitAnd(buffer3.get(cL(12L)))).div(cL(15L)))
    A = cU(cL(56L).bitOr(buffer4.get(buffer1.get(cL(2L)).rem(cL(21L))).bitAnd(cL(68L))).bitOr(buffer2.get(buffer3.get(cL(8L)).rem(cL(35L)))).bitAnd(cL(42L)).bitOr(buffer4.get(buffer1.get(cL(2L)).rem(cL(21L))).bitAnd(cL(68L)).bitOr(cL(56L)).bitAnd(buffer2.get(buffer3.get(cL(8L)).rem(cL(35L))))))
    buffer3.set(16L, A.mul(A).add(cL(110L)))
    buffer3.set(20L, cL(202L).sub(buffer3.get(cL(16L))))
    buffer3.set(24L, buffer1.get(cL(151L)))
    buffer2.set(13L, buffer2.get(13L).bitXor(buffer4.get(buffer3.get(cL(0L)).rem(cL(21L)))))
    B = cU(buffer2.get(buffer1.get(cL(179L)).rem(cL(35L))).sub(cL(38L)).bitAnd(cL(177L)).bitOr(buffer3.get(cL(12L)).bitAnd(cL(177L))))
    C = cU(buffer2.get(buffer1.get(cL(179L)).rem(cL(35L))).sub(cL(38L)).bitAnd(buffer3.get(cL(12L))))
    buffer3.set(28L, cL(30L).add(B.bitOr(C).mul(B.bitOr(C))))
    buffer3.set(32L, buffer3.get(cL(28L)).add(cL(62L)))
    A = cU(buffer3.get(cL(20L)).add(buffer3.get(cL(0L)).bitAnd(cL(74L))).bitOr(buffer4.get(buffer3.get(cL(0L)).rem(cL(21L))).inv()).bitAnd(cL(121L)))
    B = cU(buffer3.get(cL(20L)).add(buffer3.get(cL(0L)).bitAnd(cL(74L))).bitAnd(buffer4.get(buffer3.get(cL(0L)).rem(cL(21L))).inv()))
    var tmp3: CInt = cU(A.bitOr(B))
    C = cU(A.bitOr(B).bitXor(cL(4294967206L)).bitOr(buffer3.get(cL(0L))).bitAnd(cL(4L)).bitOr(A.bitOr(B).bitXor(cL(4294967206L)).bitAnd(buffer3.get(cL(0L)))))
    buffer1.set(47L, buffer2.get(buffer1.get(cL(89L)).rem(cL(35L))).add(C).bitXor(buffer1.get(cL(47L))))
    buffer3.set(36L, rol8(tmp.bitAnd(cL(179L)).add(cL(68L)), cL(2L)).bitAnd(buffer0.get(cL(3L))).bitOr(tmp2.bitAnd(buffer0.get(cL(3L)).inv())).sub(cL(15L)))
    buffer1.set(123L, buffer1.get(123L).bitXor(cL(221L)))
    A = cU(buffer4.get(buffer3.get(cL(0L)).rem(cL(21L))).div(cL(3L)).sub(buffer2.get(buffer3.get(cL(4L)).rem(cL(35L)))))
    C = cU(buffer3.get(cL(0L)).bitAnd(cL(163L)).add(cL(92L)).bitAnd(cL(246L)).bitOr(buffer3.get(cL(0L)).bitAnd(cL(92L))))
    E = cU(C.bitOr(buffer3.get(cL(24L))).bitAnd(cL(54L)).bitOr(C.bitAnd(buffer3.get(cL(24L)))))
    buffer3.set(40L, A.sub(E))
    buffer3.set(44L, tmp3.bitXor(cL(81L)).bitXor(buffer3.get(cL(0L)).shr(cL(1L)).bitAnd(cL(101L)).add(cL(26L))))
    buffer3.set(48L, buffer2.get(buffer3.get(cL(4L)).rem(cL(35L))).bitAnd(cL(27L)))
    buffer3.set(52L, cL(27L))
    buffer3.set(56L, cL(199L))
    buffer3.set(64L, buffer3.get(cL(4L)).add(buffer3.get(cL(40L)).bitOr(buffer3.get(cL(24L))).bitAnd(cL(177L)).bitOr(buffer3.get(cL(40L)).bitAnd(buffer3.get(cL(24L)))).bitAnd(buffer4.get(buffer3.get(cL(0L)).rem(cL(20L))).bitAnd(cL(177L)).bitOr(cL(176L)).bitOr(buffer4.get(buffer3.get(cL(0L)).rem(cL(21L))).bitAnd(cL(3L).inv()))).bitOr(buffer3.get(cL(40L)).bitAnd(buffer3.get(cL(24L))).bitOr(buffer3.get(cL(40L)).bitOr(buffer3.get(cL(24L))).bitAnd(cL(177L))).bitAnd(cL(199L)).bitOr(buffer4.get(buffer3.get(cL(0L)).rem(cL(21L))).bitAnd(cL(1L)).add(cL(176L)).bitOr(buffer4.get(buffer3.get(cL(0L)).rem(cL(21L))).bitAnd(cL(3L).inv())).bitAnd(buffer3.get(cL(56L))))).bitAnd(buffer3.get(cL(52L)).inv()).bitOr(buffer3.get(cL(48L)))))
    buffer2.set(33L, buffer2.get(33L).bitXor(buffer1.get(cL(26L))))
    buffer1.set(106L, buffer1.get(106L).bitXor(buffer3.get(cL(20L)).bitXor(cL(133L))))
    buffer2.set(30L, buffer3.get(cL(64L)).div(cL(3L)).sub(cL(275L).bitOr(buffer3.get(cL(0L)).bitAnd(cL(247L)))).bitXor(buffer0.get(buffer1.get(cL(122L)).rem(cL(20L)))))
    buffer1.set(22L, buffer2.get(buffer1.get(cL(90L)).rem(cL(35L))).bitAnd(cL(95L)).bitOr(cL(68L)))
    A = cU(buffer4.get(buffer3.get(cL(36L)).rem(cL(21L))).bitAnd(cL(184L)).bitOr(buffer2.get(buffer3.get(cL(44L)).rem(cL(35L))).bitAnd(cL(184L).inv())))
    buffer2.set(18L, buffer2.get(18L).add(A.mul(A).mul(A).shr(cL(1L))))
    buffer2.set(5L, buffer2.get(5L).sub(buffer4.get(buffer1.get(cL(92L)).rem(cL(21L)))))
    A = cU(buffer1.get(cL(41L)).bitAnd(cL(24L).inv()).bitOr(buffer2.get(buffer1.get(cL(183L)).rem(cL(35L))).bitAnd(cL(24L))).bitAnd(buffer3.get(cL(16L)).add(cL(53L))).bitOr(buffer3.get(cL(20L)).bitAnd(buffer2.get(buffer3.get(cL(20L)).rem(cL(35L))))))
    B = cU(buffer1.get(cL(17L)).bitAnd(buffer3.get(cL(44L)).inv()).bitOr(buffer0.get(buffer1.get(cL(59L)).rem(cL(20L))).bitAnd(buffer3.get(cL(44L)))))
    buffer2.set(18L, buffer2.get(18L).bitXor(A.mul(B)))
    A = cU(weirdRor8(buffer1.get(cL(11L)), buffer2.get(buffer1.get(cL(28L)).rem(cL(35L))).bitAnd(cL(7L))).bitAnd(cL(7L)))
    B = cU(buffer0.get(buffer1.get(cL(93L)).rem(cL(20L))).bitAnd(buffer0.get(cL(14L)).inv()).bitOr(buffer0.get(cL(14L)).bitAnd(cL(150L))).bitAnd(cL(28L).inv()).bitOr(buffer1.get(cL(7L)).bitAnd(cL(28L))))
    buffer2.set(22L, B.bitOr(weirdRol8(buffer2.get(buffer3.get(cL(0L)).rem(cL(35L))), A)).bitAnd(buffer2.get(cL(33L))).bitOr(B.bitAnd(weirdRol8(buffer2.get(buffer3.get(cL(0L)).rem(cL(35L))), A))).add(cL(74L)).bitAnd(cL(255L)))
    A = cU(buffer4.get(buffer0.get(buffer1.get(cL(39L)).rem(cL(20L))).bitXor(cL(217L)).rem(cL(21L))))
    buffer0.set(15L, buffer0.get(15L).sub(buffer3.get(cL(20L)).bitOr(buffer3.get(cL(0L))).bitAnd(cL(214L)).bitOr(buffer3.get(cL(20L)).bitAnd(buffer3.get(cL(0L)))).bitAnd(A).bitOr(buffer3.get(cL(20L)).bitOr(buffer3.get(cL(0L))).bitAnd(cL(214L)).bitOr(buffer3.get(cL(20L)).bitAnd(buffer3.get(cL(0L)))).bitOr(A).bitAnd(buffer3.get(cL(32L))))))
    B = cU(buffer2.get(buffer1.get(cL(57L)).rem(cL(35L))).bitAnd(buffer0.get(buffer3.get(cL(64L)).rem(cL(20L)))).bitOr(buffer0.get(buffer3.get(cL(64L)).rem(cL(20L))).bitOr(buffer2.get(buffer1.get(cL(57L)).rem(cL(35L)))).bitAnd(cL(95L))).bitOr(buffer3.get(cL(64L)).bitAnd(cL(45L))).bitOr(cL(82L)).bitAnd(cL(32L)))
    C = cU(buffer2.get(buffer1.get(cL(57L)).rem(cL(35L))).bitAnd(buffer0.get(buffer3.get(cL(64L)).rem(cL(20L)))).bitOr(buffer2.get(buffer1.get(cL(57L)).rem(cL(35L))).bitOr(buffer0.get(buffer3.get(cL(64L)).rem(cL(20L)))).bitAnd(cL(95L))).bitAnd(buffer3.get(cL(64L)).bitAnd(cL(45L)).bitOr(cL(82L))))
    D = cU(buffer3.get(cL(0L)).div(cL(3L)).sub(buffer3.get(cL(64L)).bitOr(buffer1.get(cL(22L)))).bitXor(buffer3.get(cL(28L)).add(cL(62L))).bitXor(B.bitOr(C)))
    var T: CInt = cU(buffer0.get(D.bitAnd(cL(255L)).rem(cL(20L))))
    buffer3.set(68L, buffer0.get(buffer1.get(cL(99L)).rem(cL(20L))).mul(buffer0.get(buffer1.get(cL(99L)).rem(cL(20L)))).mul(buffer0.get(buffer1.get(cL(99L)).rem(cL(20L)))).mul(buffer0.get(buffer1.get(cL(99L)).rem(cL(20L)))).bitOr(buffer2.get(buffer3.get(cL(64L)).rem(cL(35L)))))
    var U: CInt = cU(buffer0.get(buffer1.get(cL(50L)).rem(cL(20L))))
    var W: CInt = cU(buffer2.get(buffer1.get(cL(138L)).rem(cL(35L))))
    var X: CInt = cU(buffer4.get(buffer1.get(cL(39L)).rem(cL(21L))))
    var Y: CInt = cU(buffer0.get(buffer1.get(cL(4L)).rem(cL(20L))))
    var Z: CInt = cU(buffer4.get(buffer1.get(cL(202L)).rem(cL(21L))))
    var V: CInt = cU(buffer0.get(buffer1.get(cL(151L)).rem(cL(20L))))
    var S: CInt = cU(buffer2.get(buffer1.get(cL(14L)).rem(cL(35L))))
    var R: CInt = cU(buffer0.get(buffer1.get(cL(145L)).rem(cL(20L))))
    A = cU(buffer2.get(buffer3.get(cL(68L)).rem(cL(35L))).bitAnd(buffer0.get(buffer1.get(cL(209L)).rem(cL(20L)))).bitOr(buffer2.get(buffer3.get(cL(68L)).rem(cL(35L))).bitOr(buffer0.get(buffer1.get(cL(209L)).rem(cL(20L)))).bitAnd(cL(24L))))
    B = cU(weirdRol8(buffer4.get(buffer1.get(cL(127L)).rem(cL(21L))), buffer2.get(buffer3.get(cL(68L)).rem(cL(35L))).bitAnd(cL(7L))))
    C = cU(A.bitAnd(buffer0.get(cL(10L))).bitOr(B.bitAnd(buffer0.get(cL(10L)).inv())))
    D = cU(cL(7L).bitXor(buffer4.get(buffer2.get(buffer3.get(cL(36L)).rem(cL(35L))).rem(cL(21L))).shl(cL(1L))))
    buffer3.set(72L, C.bitAnd(cL(71L)).bitOr(D.bitAnd(cL(71L).inv())))
    buffer2.set(2L, buffer2.get(2L).add(buffer0.get(buffer3.get(cL(20L)).rem(cL(20L))).shl(cL(1L)).bitAnd(cL(159L)).bitOr(buffer4.get(buffer1.get(cL(190L)).rem(cL(21L))).bitAnd(cL(159L).inv())).bitAnd(buffer4.get(buffer3.get(cL(64L)).rem(cL(21L))).bitAnd(cL(110L)).bitOr(buffer0.get(buffer1.get(cL(25L)).rem(cL(20L))).bitAnd(cL(110L).inv())).bitAnd(cL(150L).inv()).bitOr(buffer1.get(cL(25L)).bitAnd(cL(150L))))))
    buffer2.set(14L, buffer2.get(14L).sub(buffer2.get(buffer3.get(cL(20L)).rem(cL(35L))).bitAnd(buffer3.get(cL(72L)).bitXor(buffer2.get(buffer1.get(cL(100L)).rem(cL(35L))))).bitAnd(cL(34L).inv()).bitOr(buffer1.get(cL(97L)).bitAnd(cL(34L)))))
    buffer0.set(17L, cL(115L))
    buffer1.set(23L, buffer1.get(23L).bitXor(buffer4.get(buffer1.get(cL(17L)).rem(cL(21L))).bitOr(buffer0.get(buffer3.get(cL(20L)).rem(cL(20L)))).bitAnd(buffer3.get(cL(72L))).bitOr(buffer4.get(buffer1.get(cL(17L)).rem(cL(21L))).bitAnd(buffer0.get(buffer3.get(cL(20L)).rem(cL(20L))))).bitAnd(buffer1.get(cL(50L)).div(cL(3L))).bitOr(buffer4.get(buffer1.get(cL(17L)).rem(cL(21L))).bitOr(buffer0.get(buffer3.get(cL(20L)).rem(cL(20L)))).bitAnd(buffer3.get(cL(72L))).bitOr(buffer4.get(buffer1.get(cL(17L)).rem(cL(21L))).bitAnd(buffer0.get(buffer3.get(cL(20L)).rem(cL(20L))))).bitOr(buffer1.get(cL(50L)).div(cL(3L))).bitAnd(cL(246L))).shl(cL(1L))))
    buffer0.set(13L, buffer0.get(buffer3.get(cL(40L)).rem(cL(20L))).bitOr(buffer1.get(cL(10L))).bitAnd(cL(82L)).bitOr(buffer0.get(buffer3.get(cL(40L)).rem(cL(20L))).bitAnd(buffer1.get(cL(10L)))).bitAnd(cL(209L)).bitOr(buffer0.get(buffer1.get(cL(39L)).rem(cL(20L))).shl(cL(1L)).bitAnd(cL(46L))).shr(cL(1L)))
    buffer2.set(33L, buffer2.get(33L).sub(buffer1.get(cL(113L)).bitAnd(cL(9L))))
    buffer2.set(28L, buffer2.get(28L).sub(cL(2L).bitOr(buffer1.get(cL(110L)).bitAnd(cL(222L))).shr(cL(1L)).bitAnd(cL(223L).inv()).bitOr(buffer3.get(cL(20L)).bitAnd(cL(223L)))))
    var J: CInt = cU(weirdRol8(V.bitOr(Z), U.bitAnd(cL(7L))))
    A = cU(buffer2.get(cL(16L)).bitAnd(T).bitOr(W.bitAnd(buffer2.get(cL(16L)).inv())))
    B = cU(buffer1.get(cL(33L)).bitAnd(cL(17L)).bitOr(X.bitAnd(cL(17L).inv())))
    E = cU(Y.bitOr(A.add(B).div(cL(5L))).bitAnd(cL(147L)).bitOr(Y.bitAnd(A.add(B).div(cL(5L)))))
    var M: CInt = cU(buffer3.get(cL(40L)).bitAnd(buffer4.get(buffer3.get(cL(8L)).add(J).add(E).bitAnd(cL(255L)).rem(cL(21L)))).bitOr(buffer3.get(cL(40L)).bitOr(buffer4.get(buffer3.get(cL(8L)).add(J).add(E).bitAnd(cL(255L)).rem(cL(21L)))).bitAnd(buffer2.get(cL(23L)))))
    buffer0.set(15L, buffer4.get(buffer3.get(cL(20L)).rem(cL(21L))).sub(cL(48L)).bitAnd(buffer1.get(cL(184L)).inv()).bitOr(buffer4.get(buffer3.get(cL(20L)).rem(cL(21L))).sub(cL(48L)).bitAnd(cL(189L))).bitOr(cL(189L).bitAnd(buffer1.get(cL(184L)).inv())).bitAnd(M.mul(M).mul(M)))
    buffer2.set(22L, buffer2.get(22L).add(buffer1.get(cL(183L))))
    buffer3.set(76L, cL(3L).mul(buffer4.get(buffer1.get(cL(1L)).rem(cL(21L)))).bitXor(buffer3.get(cL(0L))))
    A = cU(buffer2.get(buffer3.get(cL(8L)).add(J.add(E)).bitAnd(cL(255L)).rem(cL(35L))))
    var F: CInt = cU(buffer4.get(buffer1.get(cL(178L)).rem(cL(21L))).bitAnd(A).bitOr(buffer4.get(buffer1.get(cL(178L)).rem(cL(21L))).bitOr(A).bitAnd(cL(209L))).mul(buffer0.get(buffer1.get(cL(13L)).rem(cL(20L)))).mul(buffer4.get(buffer1.get(cL(26L)).rem(cL(21L))).shr(cL(1L))))
    var G: CInt = cU(F.add(cL(1933574137L)).mul(cL(198L)).sub(F.add(cL(1933574137L)).mul(cL(396L)).add(cL(212L)).bitAnd(cL(212L))).add(cL(85L)))
    buffer3.set(80L, buffer3.get(cL(36L)).add(G.bitXor(cL(148L))).add(G.bitXor(cL(107L)).shl(cL(1L))).sub(cL(127L)))
    buffer3.set(84L, buffer2.get(buffer3.get(cL(64L)).rem(cL(35L))).bitAnd(cL(245L)).bitOr(buffer2.get(buffer3.get(cL(20L)).rem(cL(35L))).bitAnd(cL(10L))))
    A = cU(buffer0.get(buffer3.get(cL(68L)).rem(cL(20L))).bitOr(cL(81L)))
    buffer2.set(18L, buffer2.get(18L).sub(A.mul(A).mul(A).bitAnd(buffer0.get(cL(15L)).inv()).bitOr(buffer3.get(cL(80L)).div(cL(15L)).bitAnd(buffer0.get(cL(15L))))))
    buffer3.set(88L, buffer3.get(cL(8L)).add(J).add(E).sub(buffer0.get(buffer1.get(cL(160L)).rem(cL(20L)))).add(buffer4.get(buffer0.get(buffer3.get(cL(8L)).add(J).add(E).bitAnd(cL(255L)).rem(cL(20L))).rem(cL(21L))).div(cL(3L))))
    B = cU(R.bitXor(buffer3.get(cL(72L))).bitAnd(cL(198L).inv()).bitOr(S.mul(S).bitAnd(cL(198L))))
    F = cU(buffer4.get(buffer1.get(cL(69L)).rem(cL(21L))).bitAnd(buffer1.get(cL(172L))).bitOr(buffer4.get(buffer1.get(cL(69L)).rem(cL(21L))).bitOr(buffer1.get(cL(172L))).bitAnd(buffer3.get(cL(12L)).sub(B).add(cL(77L)))))
    buffer0.set(16L, cL(147L).sub(buffer3.get(cL(72L)).bitAnd(F.bitAnd(cL(251L)).bitOr(cL(1L))).bitOr(F.bitAnd(cL(250L)).bitOr(buffer3.get(cL(72L))).bitAnd(cL(198L)))))
    C = cU(buffer4.get(buffer1.get(cL(168L)).rem(cL(21L))).bitAnd(buffer0.get(buffer1.get(cL(29L)).rem(cL(20L)))).bitAnd(cL(7L)).bitOr(buffer4.get(buffer1.get(cL(168L)).rem(cL(21L))).bitOr(buffer0.get(buffer1.get(cL(29L)).rem(cL(20L)))).bitAnd(cL(6L))))
    F = cU(buffer4.get(buffer1.get(cL(155L)).rem(cL(21L))).bitAnd(buffer1.get(cL(105L))).bitOr(buffer4.get(buffer1.get(cL(155L)).rem(cL(21L))).bitOr(buffer1.get(cL(105L))).bitAnd(cL(141L))))
    buffer0.set(3L, buffer0.get(3L).sub(buffer4.get(weirdRol32(F, C).rem(cL(21L)))))
    buffer1.set(5L, weirdRor8(buffer0.get(cL(12L)), buffer0.get(buffer1.get(cL(61L)).rem(cL(20L))).div(cL(5L)).bitAnd(cL(7L))).bitXor(buffer2.get(buffer3.get(cL(84L)).rem(cL(35L))).inv().bitAnd(cL(4294967295L)).div(cL(5L))))
    buffer1.set(198L, buffer1.get(198L).add(buffer1.get(cL(3L))))
    A = cU(cL(162L).bitOr(buffer2.get(buffer3.get(cL(64L)).rem(cL(35L)))))
    buffer1.set(164L, buffer1.get(164L).add(A.mul(A).div(cL(5L))))
    G = cU(weirdRor8(cL(139L), buffer3.get(cL(80L)).bitAnd(cL(7L))))
    C = cU(buffer4.get(buffer3.get(cL(64L)).rem(cL(21L))).mul(buffer4.get(buffer3.get(cL(64L)).rem(cL(21L)))).mul(buffer4.get(buffer3.get(cL(64L)).rem(cL(21L)))).bitAnd(cL(95L)).bitOr(buffer0.get(buffer3.get(cL(40L)).rem(cL(20L))).bitAnd(cL(95L).inv())))
    buffer3.set(92L, G.bitAnd(cL(12L)).bitOr(buffer0.get(buffer3.get(cL(20L)).rem(cL(20L))).bitAnd(cL(12L))).bitOr(G.bitAnd(buffer0.get(buffer3.get(cL(20L)).rem(cL(20L))))).bitOr(C))
    buffer2.set(12L, buffer2.get(12L).add(buffer1.get(cL(103L)).bitAnd(cL(32L)).bitOr(buffer3.get(cL(92L)).bitAnd(buffer1.get(cL(103L)).bitOr(cL(60L)))).bitOr(cL(16L)).div(cL(3L))))
    buffer3.set(96L, buffer1.get(cL(143L)))
    buffer3.set(100L, cL(27L))
    buffer3.set(104L, buffer3.get(cL(40L)).bitAnd(buffer2.get(cL(8L)).inv()).bitOr(buffer1.get(cL(35L)).bitAnd(buffer2.get(cL(8L)))).bitAnd(buffer3.get(cL(64L))).bitXor(cL(119L)))
    buffer3.set(108L, cL(238L).bitAnd(buffer3.get(cL(40L)).bitAnd(buffer2.get(cL(8L)).inv()).bitOr(buffer1.get(cL(35L)).bitAnd(buffer2.get(cL(8L)))).bitAnd(buffer3.get(cL(64L))).shl(cL(1L))))
    buffer3.set(112L, buffer3.get(cL(64L)).inv().bitAnd(buffer3.get(cL(84L)).div(cL(3L))).bitXor(cL(49L)))
    buffer3.set(116L, cL(98L).bitAnd(buffer3.get(cL(64L)).inv().bitAnd(buffer3.get(cL(84L)).div(cL(3L))).shl(cL(1L))))
    A = cU(buffer1.get(cL(35L)).bitAnd(buffer2.get(cL(8L))).bitOr(buffer3.get(cL(40L)).bitAnd(buffer2.get(cL(8L)).inv())))
    B = cU(A.bitAnd(buffer3.get(cL(64L))).bitOr(buffer3.get(cL(84L)).div(cL(3L)).bitAnd(buffer3.get(cL(64L)).inv())))
    buffer1.set(143L, buffer3.get(cL(96L)).sub(B.bitAnd(cL(86L).add(buffer1.get(cL(172L)).bitAnd(cL(64L)).shr(cL(1L)))).bitOr(buffer1.get(cL(172L)).bitAnd(cL(65L)).shr(cL(1L)).bitXor(cL(86L)).bitOr(buffer3.get(cL(64L)).inv().bitAnd(buffer3.get(cL(84L)).div(cL(3L))).bitOr(buffer3.get(cL(40L)).bitAnd(buffer2.get(cL(8L)).inv()).bitOr(buffer1.get(cL(35L)).bitAnd(buffer2.get(cL(8L)))).bitAnd(buffer3.get(cL(64L))))).bitAnd(buffer3.get(cL(100L))))))
    buffer2.set(29L, cL(162L))
    A = cU(buffer4.get(buffer3.get(cL(88L)).rem(cL(21L))).bitAnd(cL(160L)).bitOr(buffer0.get(buffer1.get(cL(125L)).rem(cL(20L))).bitAnd(cL(95L))).shr(cL(1L)))
    B = cU(buffer2.get(buffer1.get(cL(149L)).rem(cL(35L))).bitXor(buffer1.get(cL(43L)).mul(buffer1.get(cL(43L)))))
    buffer0.set(15L, buffer0.get(15L).add(B.bitAnd(A).bitOr(A.bitOr(B).bitAnd(cL(115L)))))
    buffer3.set(120L, buffer3.get(cL(64L)).sub(buffer0.get(buffer3.get(cL(40L)).rem(cL(20L)))))
    buffer1.set(95L, buffer4.get(buffer3.get(cL(20L)).rem(cL(21L))))
    A = cU(weirdRor8(buffer2.get(buffer3.get(cL(80L)).rem(cL(35L))), buffer2.get(buffer1.get(cL(17L)).rem(cL(35L))).mul(buffer2.get(buffer1.get(cL(17L)).rem(cL(35L)))).mul(buffer2.get(buffer1.get(cL(17L)).rem(cL(35L)))).bitAnd(cL(7L))))
    buffer0.set(7L, buffer0.get(7L).sub(A.mul(A)))
    buffer2.set(8L, buffer2.get(cL(8L)).sub(buffer1.get(cL(184L))).add(buffer4.get(buffer1.get(cL(202L)).rem(cL(21L))).mul(buffer4.get(buffer1.get(cL(202L)).rem(cL(21L)))).mul(buffer4.get(buffer1.get(cL(202L)).rem(cL(21L))))))
    buffer0.set(16L, buffer2.get(buffer1.get(cL(102L)).rem(cL(35L))).shl(cL(1L)).bitAnd(cL(132L)))
    buffer3.set(124L, buffer4.get(buffer3.get(cL(40L)).rem(cL(21L))).shr(cL(1L)).bitXor(buffer3.get(cL(68L))))
    buffer0.set(7L, buffer0.get(7L).sub(buffer0.get(buffer1.get(cL(191L)).rem(cL(20L))).sub(buffer4.get(buffer1.get(cL(80L)).rem(cL(21L))).shl(cL(1L)).bitAnd(cL(177L).inv()).bitOr(buffer4.get(buffer4.get(buffer3.get(cL(88L)).rem(cL(21L))).rem(cL(21L))).bitAnd(cL(177L))))))
    buffer0.set(6L, buffer0.get(buffer1.get(cL(119L)).rem(cL(20L))))
    A = cU(buffer4.get(buffer1.get(cL(190L)).rem(cL(21L))).bitAnd(cL(209L).inv()).bitOr(buffer1.get(cL(118L)).bitAnd(cL(209L))))
    B = cU(buffer0.get(buffer3.get(cL(120L)).rem(cL(20L))).mul(buffer0.get(buffer3.get(cL(120L)).rem(cL(20L)))))
    buffer0.set(12L, buffer0.get(buffer3.get(cL(84L)).rem(cL(20L))).bitXor(buffer2.get(buffer1.get(cL(71L)).rem(cL(35L))).add(buffer2.get(buffer1.get(cL(15L)).rem(cL(35L))))).bitAnd(A.bitAnd(B).bitOr(A.bitOr(B).bitAnd(cL(27L)))))
    B = cU(buffer1.get(cL(32L)).bitAnd(buffer2.get(buffer3.get(cL(88L)).rem(cL(35L)))).bitOr(buffer1.get(cL(32L)).bitOr(buffer2.get(buffer3.get(cL(88L)).rem(cL(35L)))).bitAnd(cL(23L))))
    D = cU(buffer4.get(buffer1.get(cL(57L)).rem(cL(21L))).mul(cL(231L)).bitAnd(cL(169L)).bitOr(B.bitAnd(cL(86L))))
    F = cU(buffer0.get(buffer1.get(cL(82L)).rem(cL(20L))).bitAnd(cL(29L).inv()).bitOr(buffer4.get(buffer3.get(cL(124L)).rem(cL(21L))).bitAnd(cL(29L))).bitAnd(cL(190L)).bitOr(buffer4.get(D.div(cL(5L)).rem(cL(21L))).bitAnd(cL(190L).inv())))
    var H: CInt = cU(buffer0.get(buffer3.get(cL(40L)).rem(cL(20L))).mul(buffer0.get(buffer3.get(cL(40L)).rem(cL(20L)))).mul(buffer0.get(buffer3.get(cL(40L)).rem(cL(20L)))))
    var K: CInt = cU(H.bitAnd(buffer1.get(cL(82L))).bitOr(H.bitAnd(cL(92L))).bitOr(buffer1.get(cL(82L)).bitAnd(cL(92L))))
    buffer3.set(128L, F.bitAnd(K).bitOr(F.bitOr(K).bitAnd(cL(192L))).bitXor(D.div(cL(5L))))
    buffer2.set(25L, buffer2.get(25L).bitXor(buffer0.get(buffer3.get(cL(120L)).rem(cL(20L))).shl(cL(1L)).mul(buffer1.get(cL(5L))).sub(weirdRol8(buffer3.get(cL(76L)), buffer4.get(buffer3.get(cL(124L)).rem(cL(21L))).bitAnd(cL(7L))).bitAnd(buffer3.get(cL(20L)).add(cL(110L))))))
}
