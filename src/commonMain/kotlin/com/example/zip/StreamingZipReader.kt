package com.example.zip

import okio.BufferedSink
import okio.BufferedSource

/**
 * Стриминговый ZIP Reader
 */
class StreamingZipReader {
    private val entries = mutableListOf<ZipEntryInfo>()

    fun load(source: BufferedSource, fileSize: Long) {
        entries.clear()
        // TODO: Реализовать чтение центральной директории
    }

    fun getEntries(): List<ZipEntryInfo> = entries.toList()

    fun extractFile(source: BufferedSource, entry: ZipEntryInfo, sink: BufferedSink) {
        // TODO: Реализовать извлечение с учетом Data Descriptor
    }
}
