package com.cruxcoach.app.zip

/** CRC-32 (ISO-HDLC, the ZIP polynomial 0xEDB88320). */
internal object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(data: ByteArray): Int = of(data, 0, data.size)

    fun of(data: ByteArray, from: Int, to: Int): Int {
        var crc = -1
        for (i in from until to) crc = table[(crc xor data[i].toInt()) and 0xff] xor (crc ushr 8)
        return crc.inv()
    }
}
