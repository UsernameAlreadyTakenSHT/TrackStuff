package com.example.trackstuff

import com.example.trackstuff.data.local.normalizeTitle
import com.example.trackstuff.data.omdborg.MysqlCsvReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class MysqlCsvReaderTest {
    private fun records(csv: String): List<List<String?>> {
        val r = MysqlCsvReader(StringReader(csv))
        val out = mutableListOf<List<String?>>()
        r.forEachRecord(skipHeader = false) { out += it }
        return out
    }

    @Test
    fun `parses quoted fields, nulls and escaped quotes like omdb dumps`() {
        val csv = """"id","name","parent_id","date"
"27310","Kraven \"Redux\"",\N,"2006-01-01"
"463","Sin City 3: Hell and Back","188",\N
"""
        val rows = records(csv)
        assertEquals(3, rows.size)
        assertEquals(listOf("id", "name", "parent_id", "date"), rows[0])
        assertEquals("Kraven \"Redux\"", rows[1][1])
        assertNull(rows[1][2])
        assertEquals("2006-01-01", rows[1][3])
        assertEquals("188", rows[2][2])
        assertNull(rows[2][3])
    }

    @Test
    fun `keeps newlines inside quoted fields`() {
        val csv = "\"movie_id\",\"abstract\"\n\"25\",\"Line one.\nLine two, with comma.\"\n\"26\",\"Short\"\n"
        val rows = records(csv)
        assertEquals(3, rows.size)
        assertEquals("Line one.\nLine two, with comma.", rows[1][1])
        assertEquals("26", rows[2][0])
    }

    @Test
    fun `handles CRLF and backslash escapes`() {
        val csv = "\"1\",\"a\\\\b\"\r\n\"2\",\"c\"\r\n"
        val rows = records(csv)
        assertEquals(listOf("1", "a\\b"), rows[0])
        assertEquals(listOf("2", "c"), rows[1])
    }

    @Test
    fun `title normalization strips accents and punctuation`() {
        assertEquals("les choristes", normalizeTitle("Les Choristes"))
        assertEquals("sixieme sens film", normalizeTitle("Sixième Sens (film)"))
        assertEquals("cowboy bebop", normalizeTitle("  Cowboy   Bebop! "))
    }
}
