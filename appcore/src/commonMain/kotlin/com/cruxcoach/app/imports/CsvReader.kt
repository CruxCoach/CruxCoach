package com.cruxcoach.app.imports

/**
 * The small RFC-4180 reader both Android CSV paths use, with the delimiter as
 * a parameter (MoonBoard exports use `,`, the CruxCoach archive uses `;`) and
 * with every bound checked *while* scanning.
 *
 * Android builds the whole row list first and only then checks how big it got.
 * That is fine for a file a user exported from their own account and fatal for
 * the 50 MB of junk this port has to survive, so the limits below abort the
 * scan at the first breach instead. Nothing is ever truncated: a file that
 * exceeds a bound is rejected whole, with the code saying which bound it was.
 */
internal class CsvLimits(
    val maxRows: Int,
    val maxColumns: Int = 512,
    val maxFieldChars: Int = 8_192,
)

internal sealed class CsvParse {
    class Ok(val rows: List<List<String>>) : CsvParse()
    /** [code] is an `ImportCodes` whole-file code; [row] is 1-based or 0. */
    class Failed(val code: String, val row: Int) : CsvParse()
}

internal object CsvReader {
    /** Byte-order mark: MoonBoard's export writes one, Excel adds one on save. */
    const val BOM = "﻿"

    fun parse(input: String, delimiter: Char, limits: CsvLimits): CsvParse {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun finishField(): String? {
            if (field.length > limits.maxFieldChars) return ImportCodes.FIELD_TOO_LONG
            if (row.size >= limits.maxColumns) return ImportCodes.TOO_MANY_ROWS
            row.add(field.toString())
            field.setLength(0)
            return null
        }

        while (index < input.length) {
            val c = input[index]
            when {
                quoted && c == '"' && index + 1 < input.length && input[index + 1] == '"' -> {
                    field.append('"'); index++
                }
                c == '"' -> quoted = !quoted
                !quoted && c == delimiter -> finishField()?.let { return CsvParse.Failed(it, rows.size + 1) }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && index + 1 < input.length && input[index + 1] == '\n') index++
                    finishField()?.let { return CsvParse.Failed(it, rows.size + 1) }
                    rows.add(row)
                    if (rows.size > limits.maxRows) return CsvParse.Failed(ImportCodes.TOO_MANY_ROWS, rows.size)
                    row = ArrayList()
                }
                else -> {
                    field.append(c)
                    // Checked here too: a single unterminated quote otherwise makes
                    // the rest of the file one field and the bound never fires.
                    if (field.length > limits.maxFieldChars) {
                        return CsvParse.Failed(ImportCodes.FIELD_TOO_LONG, rows.size + 1)
                    }
                }
            }
            index++
        }
        if (quoted) return CsvParse.Failed(ImportCodes.UNTERMINATED_QUOTE, rows.size + 1)
        if (field.isNotEmpty() || row.isNotEmpty()) {
            finishField()?.let { return CsvParse.Failed(it, rows.size + 1) }
            rows.add(row)
            if (rows.size > limits.maxRows) return CsvParse.Failed(ImportCodes.TOO_MANY_ROWS, rows.size)
        }
        return CsvParse.Ok(rows)
    }

    /** RFC-4180 writer: quotes only what needs it, as the Android archive does. */
    fun write(rows: List<List<String>>, delimiter: Char): String =
        rows.joinToString("\n", postfix = "\n") { row ->
            row.joinToString(delimiter.toString()) { value ->
                if (value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) {
                    "\"" + value.replace("\"", "\"\"") + "\""
                } else {
                    value
                }
            }
        }
}
