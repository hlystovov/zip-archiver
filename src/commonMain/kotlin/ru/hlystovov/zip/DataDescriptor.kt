package ru.hlystovov.zip

import okio.BufferedSink

/**
 * ZIP Data Descriptor
 * Используется когда размер и CRC неизвестны при записи заголовка
 */
internal data class DataDescriptor(
    val crc32: Int,
    val compressedSize: Int,
    val uncompressedSize: Int,
    val useSignature: Boolean = true
) {
    fun writeTo(sink: BufferedSink) {
        if (useSignature) {
            sink.writeZipInt(0x08074b50) // Data descriptor signature
        }
        sink.writeZipInt(crc32)
        sink.writeZipInt(compressedSize)
        sink.writeZipInt(uncompressedSize)
    }

    val size: Int = if (useSignature) 16 else 12
}
