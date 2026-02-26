package com.example.zip

/**
 * Информация о файле в ZIP архиве
 */
data class ZipEntryInfo(
    val name: String,
    val compressedSize: Int,
    val uncompressedSize: Int,
    val crc32: Int,
    val offset: Int,
    val headerSize: Int,
    val hasDataDescriptor: Boolean = false
)
