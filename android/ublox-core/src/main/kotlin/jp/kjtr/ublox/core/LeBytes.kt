package jp.kjtr.ublox.core

import java.io.ByteArrayOutputStream

/** リトルエンディアンの読み出し（Python の struct.unpack_from("<...") の代わり）。 */
class LeReader(private val b: ByteArray) {
    fun u1(off: Int): Int = b[off].toInt() and 0xFF
    fun i1(off: Int): Int = b[off].toInt()
    fun u2(off: Int): Int = u1(off) or (u1(off + 1) shl 8)
    fun i2(off: Int): Int = u2(off).toShort().toInt()
    fun i4(off: Int): Int = u1(off) or (u1(off + 1) shl 8) or (u1(off + 2) shl 16) or (u1(off + 3) shl 24)
    fun u4(off: Int): Long = i4(off).toLong() and 0xFFFFFFFFL
    fun i8(off: Int): Long = (u4(off)) or (u4(off + 4) shl 32)
    fun f4(off: Int): Float = Float.fromBits(i4(off))
    fun f8(off: Int): Double = Double.fromBits(i8(off))
}

/**
 * リトルエンディアンの書き込み。UBX のペイロードを組み立てるのに使う
 * （内蔵 GNSS から NAV-PVT などを合成するときも使う）。
 */
class LeWriter {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun u1(v: Int) = apply { out.write(v and 0xFF) }
    fun u2(v: Int) = apply { u1(v); u1(v shr 8) }
    fun u4(v: Long) = apply { int(v, 4) }
    fun i4(v: Int) = apply { int(v.toLong(), 4) }
    fun f4(v: Float) = apply { i4(v.toRawBits()) }
    fun f8(v: Double) = apply { int(v.toRawBits(), 8) }
    fun bytes(v: ByteArray) = apply { out.write(v) }
    fun zeros(n: Int) = apply { repeat(n) { out.write(0) } }

    /** 整数 v の下位 size バイトを書く（負の値は 2 の補数）。 */
    fun int(v: Long, size: Int) = apply {
        for (i in 0 until size) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
