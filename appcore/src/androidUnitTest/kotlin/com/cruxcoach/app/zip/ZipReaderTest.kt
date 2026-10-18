package com.cruxcoach.app.zip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [ZipReader] and [ZipWriter] against `java.util.zip`, the library Android's archive code uses. */
class ZipReaderTest {
    private val text = buildString {
        repeat(500) { append("$it;climb-${it % 9};Felt easy;2026-08-27T10:01:00Z\n") }
    }.encodeToByteArray()

    /** Exactly what Android's `CruxCoachCsvArchive.writeEntry` does: deflated, with data descriptors. */
    private fun javaZip(vararg files: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, data) in files) {
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun readAll(archive: ByteArray, maxEntries: Int = 10): Map<String, ByteArray> {
        val zip = ZipReader.open(archive, maxEntries)
        return zip.entries.associate { it.name to zip.read(it) }
    }

    private fun streamAll(archive: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    @Test
    fun `reads what java's ZipOutputStream writes deflated and stored`() {
        val files = arrayOf("manifest.json" to """{"app":"CruxCoach"}""".encodeToByteArray(), "a.csv" to text, "empty.csv" to ByteArray(0))
        for (stored in listOf(false, true)) {
            val archive = javaZip(*files, stored = stored)
            val zip = ZipReader.open(archive, 10)
            assertEquals(listOf("manifest.json", "a.csv", "empty.csv"), zip.entries.map { it.name })
            assertTrue(zip.entries.all { it.method == if (stored) METHOD_STORED else METHOD_DEFLATED })
            val read = readAll(archive)
            for ((name, data) in files) assertContentEquals(data, read.getValue(name), name)
        }
    }

    @Test
    fun `java reads what ZipWriter writes and checks its crcs`() {
        val writer = ZipWriter()
        writer.add("manifest.json", """{"app":"CruxCoach"}""".encodeToByteArray())
        writer.add("a.csv", text)
        writer.add("empty.csv", ByteArray(0))
        val archive = writer.finish()
        assertTrue(archive.size < text.size, "entries must be deflated")

        // ZipInputStream verifies size and CRC of every deflated entry while reading it.
        val streamed = streamAll(archive)
        assertContentEquals(text, streamed.getValue("a.csv"))
        assertEquals(setOf("manifest.json", "a.csv", "empty.csv"), streamed.keys)
        val file = File.createTempFile("zipwriter", ".zip")
        try {
            file.writeBytes(archive)
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                assertEquals(listOf("manifest.json", "a.csv", "empty.csv"), entries.map { it.name })
                for (entry in entries) {
                    assertEquals(ZipEntry.DEFLATED, entry.method)
                    val bytes = zip.getInputStream(entry).readBytes()
                    assertEquals(entry.crc, CRC32().apply { update(bytes) }.value)
                    assertEquals(entry.size, bytes.size.toLong())
                }
            }
        } finally {
            file.delete()
        }
        assertContentEquals(text, readAll(archive).getValue("a.csv"))
    }

    @Test
    fun `deflate output is interchangeable with java's Inflater and Deflater`() {
        val ours = RawDeflate.deflateRaw(text, 6)
        val inflater = Inflater(true)
        inflater.setInput(ours)
        val out = ByteArray(text.size)
        assertEquals(text.size, inflater.inflate(out))
        assertTrue(inflater.finished())
        inflater.end()
        assertContentEquals(text, out)

        val deflater = Deflater(9, true)
        deflater.setInput(text)
        deflater.finish()
        val buffer = ByteArray(text.size + 64)
        val length = deflater.deflate(buffer)
        assertTrue(deflater.finished())
        deflater.end()
        assertContentEquals(text, RawDeflate.inflateRaw(buffer.copyOf(length), text.size))
    }

    @Test
    fun `crc32 matches java`() {
        assertEquals(0xCBF43926.toInt(), Crc32.of("123456789".encodeToByteArray()))
        assertEquals(CRC32().apply { update(text) }.value.toInt(), Crc32.of(text))
        assertEquals(0, Crc32.of(ByteArray(0)))
    }

    @Test
    fun `a wrong crc or size is refused`() {
        val archive = javaZip("a.csv" to text, stored = true)
        val zip = ZipReader.open(archive, 10)
        val entry = zip.entries.single()
        // Flip one data byte: CRC no longer matches.
        val corrupted = archive.copyOf().also { it[entry.dataOffset + 10] = (it[entry.dataOffset + 10] + 1).toByte() }
        val reopened = ZipReader.open(corrupted, 10)
        assertFailsWith<IllegalArgumentException> { reopened.read(reopened.entries.single()) }

        // A deflated entry whose declared size is smaller than its data: inflation stops at the limit.
        val deflated = javaZip("a.csv" to text)
        val directory = centralRecord(deflated)
        val shrunk = deflated.copyOf().also { writeInt(it, directory + 24, text.size - 1) }
        val shrunkZip = ZipReader.open(shrunk, 10)
        assertFailsWith<IllegalArgumentException> { shrunkZip.read(shrunkZip.entries.single()) }

        // Declared larger than the data really is.
        val grown = deflated.copyOf().also { writeInt(it, directory + 24, text.size + 1) }
        val grownZip = ZipReader.open(grown, 10)
        assertFailsWith<IllegalArgumentException> { grownZip.read(grownZip.entries.single()) }
    }

    @Test
    fun `malformed or unsupported archives are refused`() {
        val good = javaZip("a.csv" to text)
        assertFailsWith<IllegalArgumentException> { ZipReader.open(ByteArray(0), 10) }
        assertFailsWith<IllegalArgumentException> { ZipReader.open("PK\u0003\u0004 not a zip".encodeToByteArray(), 10) }
        // Truncated: no end record any more.
        assertFailsWith<IllegalArgumentException> { ZipReader.open(good.copyOf(good.size - 5), 10) }
        // Bytes appended after the end record (not declared as a comment).
        assertFailsWith<IllegalArgumentException> { ZipReader.open(good + byteArrayOf(0), 10) }
        // Bytes prepended: every offset is off.
        assertFailsWith<IllegalArgumentException> { ZipReader.open(byteArrayOf(0) + good, 10) }
        // More entries than allowed, before anything is inflated.
        val six = javaZip(*Array(6) { "f$it.csv" to text })
        assertFailsWith<IllegalArgumentException> { ZipReader.open(six, 5) }
        assertEquals(6, ZipReader.open(six, 6).entries.size)

        val directory = centralRecord(good)
        // Encrypted flag.
        assertFailsWith<IllegalArgumentException> {
            ZipReader.open(good.copyOf().also { writeShort(it, directory + 8, readShort(it, directory + 8) or 1) }, 10)
        }
        // Unsupported method (12 = bzip2) in both headers.
        assertFailsWith<IllegalArgumentException> {
            ZipReader.open(good.copyOf().also { writeShort(it, directory + 10, 12); writeShort(it, 8, 12) }, 10)
        }
        // Local and central headers disagree on the method.
        assertFailsWith<IllegalArgumentException> {
            ZipReader.open(good.copyOf().also { writeShort(it, 8, 0) }, 10)
        }
        // ZIP64 marker sizes.
        assertFailsWith<IllegalArgumentException> {
            ZipReader.open(good.copyOf().also { writeInt(it, directory + 24, -1) }, 10)
        }
        // Local header name differs from the directory's.
        assertFailsWith<IllegalArgumentException> {
            ZipReader.open(good.copyOf().also { it[30] = 'b'.code.toByte() }, 10)
        }
        // Compressed size reaching into the central directory.
        assertFailsWith<IllegalArgumentException> {
            ZipReader.open(good.copyOf().also { writeInt(it, directory + 20, readInt(it, directory + 20) + 40) }, 10)
        }
    }

    @Test
    fun `entries sharing their data are refused`() {
        // Two central records pointing at the same local entry: the overlap trick of zip bombs.
        val single = javaZip("a.csv" to text, stored = true)
        val directory = centralRecord(single)
        val end = single.size - 22
        val record = single.copyOfRange(directory, end)
        val doubled = single.copyOfRange(0, end) + record + single.copyOfRange(end, single.size)
        writeShort(doubled, doubled.size - 22 + 8, 2)
        writeShort(doubled, doubled.size - 22 + 10, 2)
        writeInt(doubled, doubled.size - 22 + 12, record.size * 2)
        assertFailsWith<IllegalArgumentException> { ZipReader.open(doubled, 10) }
    }

    @Test
    fun `an archive comment is allowed`() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setComment("made by a test")
            zip.putNextEntry(ZipEntry("a.csv"))
            zip.write(text)
            zip.closeEntry()
        }
        assertContentEquals(text, readAll(output.toByteArray()).getValue("a.csv"))
    }

    private fun centralRecord(archive: ByteArray): Int = readInt(archive, archive.size - 22 + 16)

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        readShort(bytes, offset) or (readShort(bytes, offset + 2) shl 16)

    private fun writeShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        writeShort(bytes, offset, value and 0xffff)
        writeShort(bytes, offset + 2, value ushr 16)
    }
}
