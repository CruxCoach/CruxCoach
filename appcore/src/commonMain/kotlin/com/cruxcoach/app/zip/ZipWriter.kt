package com.cruxcoach.app.zip

/**
 * Minimal ZIP writer with DEFLATED entries, as Android's `ZipOutputStream`
 * writes them. Unlike `ZipOutputStream` it knows each entry's sizes up front,
 * so CRC-32 and sizes go into the local header and no data descriptor follows
 * — `ZipInputStream`, `ZipFile` and every common unzip tool read both forms.
 * No ZIP64: the archives are capped far below 4 GiB. Names are ASCII, so the
 * UTF-8 flag is not needed. Duplicate names are the caller's responsibility.
 */
internal class ZipWriter(private val level: Int = DEFAULT_LEVEL) {
    private class Entry(val name: ByteArray, val crc: Int, val compressedSize: Int, val size: Int, val offset: Int)

    private val out = ArrayList<ByteArray>()
    private var length = 0
    private val entries = ArrayList<Entry>()

    fun add(name: String, data: ByteArray) {
        require(name.all { it.code in 0x20..0x7e } && name.isNotEmpty())
        val nameBytes = name.encodeToByteArray()
        val crc = Crc32.of(data)
        val compressed = RawDeflate.deflateRaw(data, level)
        val header = ByteArray(30 + nameBytes.size)
        writeInt(header, 0, LOCAL_HEADER)
        writeShort(header, 4, VERSION_DEFLATE)
        writeShort(header, 6, 0) // flags: none (no data descriptor, normal compression)
        writeShort(header, 8, METHOD_DEFLATED)
        writeShort(header, 10, 0) // mod time 00:00:00
        writeShort(header, 12, DOS_DATE_1980_01_01)
        writeInt(header, 14, crc)
        writeInt(header, 18, compressed.size)
        writeInt(header, 22, data.size)
        writeShort(header, 26, nameBytes.size)
        writeShort(header, 28, 0)
        nameBytes.copyInto(header, 30)
        entries += Entry(nameBytes, crc, compressed.size, data.size, length)
        append(header)
        append(compressed)
    }

    fun finish(): ByteArray {
        val directoryOffset = length
        for (entry in entries) {
            val record = ByteArray(46 + entry.name.size)
            writeInt(record, 0, CENTRAL_HEADER)
            writeShort(record, 4, VERSION_DEFLATE) // made by: MS-DOS, spec 2.0
            writeShort(record, 6, VERSION_DEFLATE)
            writeShort(record, 8, 0)
            writeShort(record, 10, METHOD_DEFLATED)
            writeShort(record, 12, 0)
            writeShort(record, 14, DOS_DATE_1980_01_01)
            writeInt(record, 16, entry.crc)
            writeInt(record, 20, entry.compressedSize)
            writeInt(record, 24, entry.size)
            writeShort(record, 28, entry.name.size)
            // extra, comment, disk, internal attributes, external attributes: zero.
            writeInt(record, 42, entry.offset)
            entry.name.copyInto(record, 46)
            append(record)
        }
        val directorySize = length - directoryOffset
        val end = ByteArray(22)
        writeInt(end, 0, END_OF_DIRECTORY)
        writeShort(end, 8, entries.size)
        writeShort(end, 10, entries.size)
        writeInt(end, 12, directorySize)
        writeInt(end, 16, directoryOffset)
        append(end)
        return joinBlocks(out)
    }

    private fun append(bytes: ByteArray) {
        require(length.toLong() + bytes.size < Int.MAX_VALUE)
        out += bytes
        length += bytes.size
    }

    private fun writeShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    companion object {
        /** `Deflater.DEFAULT_COMPRESSION`, what Android's `ZipOutputStream` uses. */
        const val DEFAULT_LEVEL = 6
        private const val VERSION_DEFLATE = 20

        /** 1980-01-01, the DOS epoch: the archive carries no wall-clock time. */
        private const val DOS_DATE_1980_01_01 = (0 shl 9) or (1 shl 5) or 1
    }
}

internal const val LOCAL_HEADER = 0x04034b50
internal const val CENTRAL_HEADER = 0x02014b50
internal const val END_OF_DIRECTORY = 0x06054b50
internal const val METHOD_STORED = 0
internal const val METHOD_DEFLATED = 8
