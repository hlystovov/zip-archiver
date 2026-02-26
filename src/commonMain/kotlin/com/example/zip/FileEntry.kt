package com.example.zip

/**
 * Информация о записанном файле
 */
internal data class FileEntry(
    val name: String,
    val compression: Short,
    val flags: Short,
    val headerOffset: Int,
    var crc32: Int = 0,
    var compressedSize: Int = 0,
    var uncompressedSize: Int = 0
)
