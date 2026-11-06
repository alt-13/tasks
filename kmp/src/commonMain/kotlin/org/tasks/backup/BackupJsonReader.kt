package org.tasks.backup

import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.IOException

/**
 * Streaming reader for backup files, covering the parts of `android.util.JsonReader` the importer
 * uses. Each value is copied out as raw JSON and decoded with kotlinx.serialization, so only one
 * task (or one preference map) is held in memory at a time.
 *
 * Commas and colons between tokens are skipped rather than validated; the raw values themselves are
 * validated when they are decoded.
 */
class BackupJsonReader(private val source: BufferedSource) : AutoCloseable {

    fun beginObject() = consume('{')

    fun endObject() = consume('}')

    fun beginArray() = consume('[')

    fun endArray() = consume(']')

    fun hasNext(): Boolean = peek().let { it != '}'.code.toByte() && it != ']'.code.toByte() }

    fun nextName(): String = lenientJson.decodeFromString(jsonString())

    fun nextInt(): Int = lenientJson.decodeFromString(jsonString())

    fun nextLong(): Long = lenientJson.decodeFromString(jsonString())

    fun skipValue() = readValue(null)

    /** The next value (object, array or primitive) as raw JSON text. */
    fun jsonString(): String = Buffer().also { readValue(it) }.readUtf8()

    inline fun <reified T : Any> forEach(callback: (T) -> Unit) {
        beginArray()
        while (hasNext()) callback(lenientJson.decodeFromString(jsonString()))
        endArray()
    }

    override fun close() = source.close()

    private fun peek(): Byte {
        while (true) {
            source.require(1)
            val b = source.buffer[0]
            if (b.toInt().toChar() in SEPARATORS) source.skip(1) else return b
        }
    }

    private fun consume(c: Char) {
        val b = peek()
        if (b != c.code.toByte()) throw IOException("Expected '$c' but was '${b.toInt().toChar()}'")
        source.skip(1)
    }

    private fun readValue(sink: Buffer?) {
        if (!hasNext()) throw IOException("Expected a value but was '${peek().toInt().toChar()}'")
        var depth = 0
        do {
            source.require(1)
            when (source.buffer[0].toInt().toChar()) {
                '"' -> readString(sink)
                '{', '[' -> { depth++; copy(sink, 1) }
                '}', ']' -> { depth--; copy(sink, 1) }
                else -> if (depth > 0) copy(sink, 1) else readLiteral(sink)
            }
        } while (depth > 0)
    }

    private fun readString(sink: Buffer?) {
        copy(sink, 1)
        while (true) {
            val index = source.indexOfElement(QUOTE_OR_BACKSLASH)
            if (index == -1L) throw IOException("Unterminated string")
            copy(sink, index)
            if (source.buffer[0] == '"'.code.toByte()) {
                copy(sink, 1)
                return
            }
            copy(sink, 2) // backslash and the escaped character
        }
    }

    private fun readLiteral(sink: Buffer?) {
        while (source.request(1) && source.buffer[0].toInt().toChar().let { it !in SEPARATORS && it != '}' && it != ']' }) {
            copy(sink, 1)
        }
    }

    private fun copy(sink: Buffer?, byteCount: Long) {
        if (sink == null) source.skip(byteCount) else source.readFully(sink, byteCount)
    }

    companion object {
        @PublishedApi
        internal val lenientJson = Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

        private const val SEPARATORS = " \t\r\n,:"
        private val QUOTE_OR_BACKSLASH = "\"\\".encodeUtf8()
    }
}
