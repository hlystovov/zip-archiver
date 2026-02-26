package com.example.zip

import okio.BufferedSink

/**
 * ZIP End of Central Directory Record
 */
internal data class EndOfCentralDirectory(
    val diskNumber: Short = 0,
    val centralDirDisk: Short = 0,
    val entriesOnDisk: Short = 0,
    val totalEntries: Short = 0,
    val centralDirSize: Int = 0,
    val centralDirOffset: Int = 0,
    val comment: String = ""
) {
    fun writeTo(sink: BufferedSink) {
        sink.writeZipInt(0x06054b50) // EOCD signature
        sink.writeZipShort(diskNumber)
        sink.writeZipShort(centralDirDisk)
        sink.writeZipShort(entriesOnDisk)
        sink.writeZipShort(totalEntries)
        sink.writeZipInt(centralDirSize)
        sink.writeZipInt(centralDirOffset)
        val commentBytes = comment.encodeToByteArray()
        sink.writeZipShort(commentBytes.size.toShort())
        sink.write(commentBytes)
    }
}
