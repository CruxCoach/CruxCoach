package com.cruxcoach.android.data

import com.cruxcoach.data.CruxCoachBackup.Category
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Readable XLSX export. This intentionally has no import counterpart. */
object CruxCoachExcelWorkbook {
    const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val FILE_NAME = "cruxcoach_readable_export.xlsx"

    private const val MAX_EXCEL_ROWS = 1_048_576
    private const val MAX_EXCEL_COLUMNS = 16_384

    fun fromJson(jsonString: String, categories: Set<Category>): ByteArray {
        val tables = CruxCoachCsvArchive.tablesFromJson(jsonString, categories)
        require(tables.isNotEmpty()) { "No categories selected for Excel export" }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            write(zip, "[Content_Types].xml", contentTypes(tables.size))
            write(zip, "_rels/.rels", packageRelationships)
            write(zip, "xl/workbook.xml", workbook(tables))
            write(zip, "xl/_rels/workbook.xml.rels", workbookRelationships(tables.size))
            write(zip, "xl/styles.xml", styles)
            tables.forEachIndexed { index, table ->
                write(zip, "xl/worksheets/sheet${index + 1}.xml", worksheet(table.rows))
            }
        }
        return output.toByteArray()
    }

    private fun worksheet(array: JsonArray): String {
        val objects = array.map { it as? JsonObject ?: error("Excel row must be an object") }
        val headers = objects.flatMap { it.keys }.distinct().sortedWith(
            compareBy<String> { headerPriority.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                .thenBy { it },
        ).ifEmpty { listOf("No data") }
        require(headers.size <= MAX_EXCEL_COLUMNS) { "Too many Excel columns" }
        require(objects.size + 1 <= MAX_EXCEL_ROWS) { "Too many Excel rows" }

        val lastColumn = columnName(headers.size)
        val lastRow = objects.size + 1
        val widths = headers.map { header ->
            val contentWidth = objects.asSequence()
                .mapNotNull { it[header] }
                .map { displayValue(it).length }
                .maxOrNull() ?: 0
            (maxOf(header.length, contentWidth).coerceIn(10, 48) + 2).toDouble()
        }
        return buildString {
            append(xmlDeclaration)
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            append("<dimension ref=\"A1:$lastColumn$lastRow\"/>")
            append("<sheetViews><sheetView workbookViewId=\"0\">")
            append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            append("</sheetView></sheetViews>")
            append("<cols>")
            widths.forEachIndexed { index, width ->
                val column = index + 1
                append("<col min=\"$column\" max=\"$column\" width=\"$width\" customWidth=\"1\"/>")
            }
            append("</cols><sheetData>")
            append("<row r=\"1\">")
            headers.forEachIndexed { index, header ->
                append(inlineStringCell(columnName(index + 1) + "1", header, style = 1))
            }
            append("</row>")
            objects.forEachIndexed { rowIndex, row ->
                val excelRow = rowIndex + 2
                append("<row r=\"$excelRow\">")
                headers.forEachIndexed { columnIndex, header ->
                    row[header]?.takeUnless { it is JsonNull }?.let { value ->
                        append(cell(columnName(columnIndex + 1) + excelRow, value))
                    }
                }
                append("</row>")
            }
            append("</sheetData>")
            if (objects.isNotEmpty()) append("<autoFilter ref=\"A1:$lastColumn$lastRow\"/>")
            append("</worksheet>")
        }
    }

    private fun cell(reference: String, value: JsonElement): String = when (value) {
        JsonNull -> ""
        is JsonPrimitive -> when {
            value.isString -> inlineStringCell(reference, value.content)
            value.booleanOrNull != null ->
                "<c r=\"$reference\" t=\"b\"><v>${if (value.booleanOrNull == true) 1 else 0}</v></c>"
            value.content.toDoubleOrNull()?.isFinite() == true ->
                "<c r=\"$reference\"><v>${xmlText(value.content)}</v></c>"
            else -> inlineStringCell(reference, value.content)
        }
        else -> inlineStringCell(reference, value.toString())
    }

    private fun inlineStringCell(reference: String, value: String, style: Int? = null): String {
        val styleAttribute = style?.let { " s=\"$it\"" }.orEmpty()
        return "<c r=\"$reference\" t=\"inlineStr\"$styleAttribute>" +
            "<is><t xml:space=\"preserve\">${xmlText(value)}</t></is></c>"
    }

    private fun displayValue(value: JsonElement): String = when (value) {
        JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append(xmlDeclaration)
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        repeat(sheetCount) { index ->
            append("<Override PartName=\"/xl/worksheets/sheet${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        append("</Types>")
    }

    private fun workbook(tables: List<CruxCoachCsvArchive.ExportTable>): String = buildString {
        append(xmlDeclaration)
        append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
        append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        append("<sheets>")
        tables.forEachIndexed { index, table ->
            append("<sheet name=\"${xmlAttribute(table.sheetName)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRelationships(sheetCount: Int): String = buildString {
        append(xmlDeclaration)
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        repeat(sheetCount) { index ->
            append("<Relationship Id=\"rId${index + 1}\" ")
            append("Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" ")
            append("Target=\"worksheets/sheet${index + 1}.xml\"/>")
        }
        append("<Relationship Id=\"rId${sheetCount + 1}\" ")
        append("Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        append("</Relationships>")
    }

    private fun columnName(oneBasedIndex: Int): String {
        require(oneBasedIndex > 0)
        var index = oneBasedIndex
        return buildString {
            while (index > 0) {
                index--
                append(('A'.code + index % 26).toChar())
                index /= 26
            }
        }.reversed()
    }

    private fun xmlText(value: String): String = value.asSequence()
        .filter { it == '\t' || it == '\n' || it == '\r' || it.code >= 0x20 }
        .joinToString("") { char ->
            when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                else -> char.toString()
            }
        }

    private fun xmlAttribute(value: String): String = xmlText(value)
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun write(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private const val xmlDeclaration = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private val headerPriority = listOf(
        "entryType",
        "rowType",
        "climbUuid",
        "uuid",
        "name",
        "comment",
        "note",
        "updatedAt",
    )

    private val packageRelationships = xmlDeclaration +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" " +
        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
        "Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private val styles = xmlDeclaration +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<fonts count=\"2\"><font/><font><b/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
        "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border/></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "</cellXfs></styleSheet>"
}
