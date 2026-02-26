package com.example.zip

import okio.BufferedSink

/**
 * ZIP Local File Header
 * Структура для стриминговой записи файлов
 */
internal data class LocalFileHeader(
    val version: Short = 20, // 2.0
    val flags: Short = 0,
    val compression: Short = 0, // 0 = stored, 8 = deflate
    val modTime: Short = 0,
    val modDate: Short = 0,
    val crc32: Int = 0,
    val compressedSize: Int = 0,
    val uncompressedSize: Int = 0,
    val name: String,
    val extra: ByteArray = byteArrayOf()
) {
    fun writeTo(sink: BufferedSink) {
        sink.writeZipInt(0x04034b50) // Local file header signature
        sink.writeZipShort(version)
        sink.writeZipShort(flags)
        sink.writeZipShort(compression)
        sink.writeZipShort(modTime)
        sink.writeZipShort(modDate)
        sink.writeZipInt(crc32)
        sink.writeZipInt(compressedSize)
        sink.writeZipInt(uncompressedSize)
        val nameBytes = name.encodeToByteArray()
        sink.writeZipShort(nameBytes.size.toShort())
        sink.writeZipShort(extra.size.toShort())
        sink.write(nameBytes)
        sink.write(extra)
    }

    val headerSize: Int
        get() = 30 + name.encodeToByteArray().size + extra.size
}
