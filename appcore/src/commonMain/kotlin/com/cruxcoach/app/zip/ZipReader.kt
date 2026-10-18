package com.cruxcoach.app.zip

/** One file listed in a ZIP archive's central directory. */
internal class ZipEntryInfo(
    val name: String,
    val method: Int,
    val crc: Int,
    val compressedSize: Int,
    /** Declared uncompressed size; [ZipReader.read] refuses data that inflates to anything else. */
    val size: Int,
    internal val dataOffset: Int,
) {
    val isDirectory: Boolean get() = name.endsWith("/")
}

/**
 * Reads a ZIP archive held in memory, for the CSV ZIP import that Android
 * does with `ZipInputStream`. Supports what ZIP writers produce for small
 * archives — STORED and DEFLATED entries, with or without a data descriptor
 * — and fails closed on everything else: encryption, ZIP64, multi-disk
 * archives, other methods, inconsistent local and central headers,
 * overlapping entries, bytes outside the declared ranges, and data whose
 * size or CRC-32 differs from the central directory.
 *
 * Entries come from the central directory, the record every writer finishes
 * with (Android's `ZipOutputStream` too). Inflation is bounded by each entry's
 * declared size, so the caller limits memory by checking [ZipEntryInfo.size]
 * before [read]. Every refusal is an [IllegalArgumentException], as with
 * Android's `require` checks.
 */
internal class ZipReader private constructor(
    private val archive: ByteArray,
    val entries: List<ZipEntryInfo>,
) {
    /** The entry's bytes, inflated and checked against its declared size and CRC-32. */
    fun read(entry: ZipEntryInfo): ByteArray {
        require(entry in entries) { "Entry of another ZIP archive" }
        val end = entry.dataOffset + entry.compressedSize
        val data = when (entry.method) {
            METHOD_STORED -> archive.copyOfRange(entry.dataOffset, end)
            else -> requireNotNull(
                RawDeflate.inflateRaw(archive.copyOfRange(entry.dataOffset, end), entry.size),
            ) { "Corrupt or oversized ZIP entry" }
        }
        require(data.size == entry.size) { "ZIP entry size mismatch" }
        require(Crc32.of(data) == entry.crc) { "ZIP entry CRC mismatch" }
        return data
    }

    companion object {
        private const val END_RECORD_BYTES = 22
        private const val MAX_COMMENT_BYTES = 0xffff
        private const val FLAG_ENCRYPTED = 0x0001
        private const val FLAG_DATA_DESCRIPTOR = 0x0008
        private const val FLAG_STRONG_ENCRYPTION = 0x0040
        private const val FLAG_MASKED_HEADERS = 0x2000

        /** Parses and cross-checks the directory; refuses more than [maxEntries] files up front. */
        fun open(archive: ByteArray, maxEntries: Int): ZipReader {
            val end = findEndRecord(archive)
            require(u16(archive, end + 4) == 0 && u16(archive, end + 6) == 0) { "Multi-disk ZIP archive" }
            val count = u16(archive, end + 8)
            require(u16(archive, end + 10) == count) { "Inconsistent ZIP entry count" }
            require(count <= maxEntries) { "Too many files in ZIP archive" }
            val directorySize = u32(archive, end + 12)
            val directoryOffset = u32(archive, end + 16)
            // The directory sits right before the end record; anything between would be ZIP64 records.
            require(directoryOffset + directorySize == end.toLong()) { "Invalid ZIP central directory" }
            val directoryStart = directoryOffset.toInt()

            val entries = ArrayList<ZipEntryInfo>(count)
            val spans = ArrayList<LongRange>(count)
            var position = directoryStart
            repeat(count) {
                require(position + 46 <= end && s32(archive, position) == CENTRAL_HEADER) {
                    "Invalid ZIP central directory record"
                }
                val flags = u16(archive, position + 8)
                val method = u16(archive, position + 10)
                val crc = s32(archive, position + 16)
                val compressedSize = u32(archive, position + 20)
                val size = u32(archive, position + 24)
                val nameLength = u16(archive, position + 28)
                val extraLength = u16(archive, position + 30)
                val commentLength = u16(archive, position + 32)
                val disk = u16(archive, position + 34)
                val headerOffset = u32(archive, position + 42)
                val next = position + 46 + nameLength + extraLength + commentLength
                require(next <= end) { "Invalid ZIP central directory record" }
                requireSupported(flags, method)
                require(disk == 0) { "Multi-disk ZIP archive" }
                // 0xFFFFFFFF marks ZIP64 sizes or offsets.
                require(compressedSize < Int.MAX_VALUE && size < Int.MAX_VALUE && headerOffset < directoryOffset) {
                    "Unsupported ZIP64 entry"
                }
                require(method != METHOD_STORED || compressedSize == size) { "Invalid stored ZIP entry" }
                val nameBytes = archive.copyOfRange(position + 46, position + 46 + nameLength)

                val local = headerOffset.toInt()
                require(local + 30L <= directoryOffset && s32(archive, local) == LOCAL_HEADER) {
                    "Invalid ZIP local header"
                }
                val localFlags = u16(archive, local + 6)
                requireSupported(localFlags, u16(archive, local + 8))
                require(u16(archive, local + 8) == method) { "ZIP headers disagree on the method" }
                val localNameLength = u16(archive, local + 26)
                val localExtraLength = u16(archive, local + 28)
                val dataOffset = local + 30L + localNameLength + localExtraLength
                require(
                    localNameLength == nameLength &&
                        dataOffset <= directoryOffset &&
                        archive.copyOfRange(local + 30, local + 30 + nameLength).contentEquals(nameBytes),
                ) { "ZIP headers disagree on the name" }
                if (localFlags and FLAG_DATA_DESCRIPTOR == 0) {
                    require(
                        s32(archive, local + 14) == crc &&
                            u32(archive, local + 18) == compressedSize &&
                            u32(archive, local + 22) == size,
                    ) { "ZIP headers disagree on the sizes" }
                }
                val dataEnd = dataOffset + compressedSize
                require(dataEnd <= directoryOffset) { "ZIP entry runs past its data" }
                spans += headerOffset until dataEnd
                entries += ZipEntryInfo(
                    name = nameBytes.decodeToString(),
                    method = method,
                    crc = crc,
                    compressedSize = compressedSize.toInt(),
                    size = size.toInt(),
                    dataOffset = dataOffset.toInt(),
                )
                position = next
            }
            require(position == end) { "Invalid ZIP central directory" }
            // Overlapping entries are the classic way to multiply one compressed block.
            spans.sortedBy { it.first }.zipWithNext().forEach { (a, b) ->
                require(a.last < b.first) { "Overlapping ZIP entries" }
            }
            return ZipReader(archive, entries)
        }

        private fun requireSupported(flags: Int, method: Int) {
            require(flags and (FLAG_ENCRYPTED or FLAG_STRONG_ENCRYPTION or FLAG_MASKED_HEADERS) == 0) {
                "Encrypted ZIP entry"
            }
            require(method == METHOD_STORED || method == METHOD_DEFLATED) { "Unsupported ZIP compression" }
        }

        /** The end record is the last 22 bytes, unless an archive comment follows it. */
        private fun findEndRecord(archive: ByteArray): Int {
            val last = archive.size - END_RECORD_BYTES
            require(last >= 0) { "Not a ZIP archive" }
            val first = maxOf(0, last - MAX_COMMENT_BYTES)
            for (position in last downTo first) {
                if (s32(archive, position) == END_OF_DIRECTORY &&
                    position + END_RECORD_BYTES + u16(archive, position + 20) == archive.size
                ) {
                    return position
                }
            }
            throw IllegalArgumentException("Not a ZIP archive")
        }

        private fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun s32(bytes: ByteArray, offset: Int): Int =
            u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)

        private fun u32(bytes: ByteArray, offset: Int): Long = s32(bytes, offset).toLong() and 0xffffffffL
    }
}
