package io.github.usernamealreadytakensht.trackstuff.data.omdborg

import java.io.PushbackReader
import java.io.Reader

/**
 * Reader for the omdb.org CSV exports (MySQL `SELECT … INTO OUTFILE` format):
 *  - quoted fields, separated by commas;
 *  - a quote inside a field is escaped as `\"`, a backslash as `\`;
 *  - NULL values written as `\N` without quotes;
 *  - line breaks allowed inside a quoted field.
 *
 * A `null` value in the returned list corresponds to `\N`.
 */
class MysqlCsvReader(source: Reader) {
    private val reader = PushbackReader(source, 1)

    /** Reads one record, or `null` at end of stream. */
    fun readRecord(): List<String?>? {
        val fields = mutableListOf<String?>()
        val sb = StringBuilder()
        var c = reader.read()
        if (c == -1) return null
        // Skip empty lines.
        while (c == '\n'.code || c == '\r'.code) { c = reader.read(); if (c == -1) return null }

        while (true) {
            if (c == '"'.code) {
                // Champ entre guillemets
                sb.setLength(0)
                while (true) {
                    c = reader.read()
                    when (c) {
                        -1 -> break
                        '\\'.code -> {
                            val n = reader.read()
                            if (n == -1) break
                            sb.append(n.toChar())
                        }
                        '"'.code -> break
                        else -> sb.append(c.toChar())
                    }
                }
                fields += sb.toString()
                c = reader.read()
            } else {
                // Bare field: `\N` (NULL) or raw value up to the comma / end of line
                sb.setLength(0)
                while (c != -1 && c != ','.code && c != '\n'.code && c != '\r'.code) {
                    sb.append(c.toChar()); c = reader.read()
                }
                val raw = sb.toString()
                fields += if (raw == "\\N") null else raw
            }
            when (c) {
                ','.code -> { c = reader.read(); continue }
                '\r'.code -> { c = reader.read(); if (c != '\n'.code && c != -1) reader.unread(c); return fields }
                '\n'.code, -1 -> return fields
                else -> {
                    // Unexpected character after a field (malformed file): skip to the end of the line.
                    while (c != -1 && c != '\n'.code) c = reader.read()
                    return fields
                }
            }
        }
    }

    /** Iterates over all records, skipping the header. */
    inline fun forEachRecord(skipHeader: Boolean = true, block: (List<String?>) -> Unit) {
        if (skipHeader) readRecord()
        while (true) {
            val r = readRecord() ?: break
            block(r)
        }
    }
}
