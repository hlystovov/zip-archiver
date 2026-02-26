package com.example.zip

import okio.Buffer
import okio.Deflater
import okio.Inflater
import okio.deflate
import okio.inflate

/**
 * Raw DEFLATE сжатие для ZIP (без zlib заголовка)
 * Использует okio Deflater с nowrap=true
 */
internal fun compress(data: ByteArray): ByteArray {
    if (data.isEmpty()) return byteArrayOf()

    val source = Buffer().apply { write(data) }
    val sink = Buffer()

    // nowrap=true для raw DEFLATE (без zlib заголовка)
    val deflater = Deflater(-1, true)
    val deflaterSink = sink.deflate(deflater)

    deflaterSink.write(source, source.size)
    deflaterSink.flush()
    deflaterSink.close()

    return sink.readByteArray()
}

/**
 * Raw DEFLATE сжатие чанка данных
 */
internal fun compress(buffer: ByteArray, length: Int): ByteArray {
    return compress(buffer.copyOf(length))
}

/**
 * Raw DEFLATE распаковка для ZIP (без zlib заголовка)
 */
internal fun decompress(compressed: ByteArray, uncompressedSize: Int): ByteArray {
    if (compressed.isEmpty()) return byteArrayOf()

    val source = Buffer().apply { write(compressed) }
    // nowrap=true для raw DEFLATE (без zlib заголовка)
    val inflaterSource = source.inflate(Inflater(true))
    val sink = Buffer()

    var totalRead = 0L
    while (totalRead < uncompressedSize) {
        val toRead = minOf(8192L, uncompressedSize - totalRead)
        val bytesRead = inflaterSource.read(sink, toRead)
        if (bytesRead == -1L || bytesRead == 0L) break
        totalRead += bytesRead
    }

    inflaterSource.close()
    return sink.readByteArray()
}
