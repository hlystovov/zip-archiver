package ru.hlystovov.zip

import okio.BufferedSink

/**
 * ZIP Central Directory Entry
 */
internal data class CentralDirectoryEntry(
    val versionMadeBy: Short = 20,
    val versionNeeded: Short = 20,
    val flags: Short = 0,
    val compression: Short = 0,
    val modTime: Short = 0,
    val modDate: Short = 0,
    val crc32: Int = 0,
    val compressedSize: Int = 0,
    val uncompressedSize: Int = 0,
    val name: String,
    val extra: ByteArray = byteArrayOf(),
    val comment: String = "",
    val diskNumber: Short = 0,
    val internalAttrs: Short = 0,
    val externalAttrs: Int = 0,
    val localHeaderOffset: Int = 0
) {
    fun writeTo(sink: BufferedSink) {
        sink.writeZipInt(0x02014b50) // Central directory signature
        sink.writeZipShort(versionMadeBy)
        sink.writeZipShort(versionNeeded)
        sink.writeZipShort(flags)
        sink.writeZipShort(compression)
        sink.writeZipShort(modTime)
        sink.writeZipShort(modDate)
        sink.writeZipInt(crc32)
        sink.writeZipInt(compressedSize)
        sink.writeZipInt(uncompressedSize)
        val nameBytes = name.encodeToByteArray()
        val commentBytes = comment.encodeToByteArray()
        sink.writeZipShort(nameBytes.size.toShort())
        sink.writeZipShort(extra.size.toShort())
        sink.writeZipShort(commentBytes.size.toShort())
        sink.writeZipShort(diskNumber)
        sink.writeZipShort(internalAttrs)
        sink.writeZipInt(externalAttrs)
        sink.writeZipInt(localHeaderOffset)
        sink.write(nameBytes)
        sink.write(extra)
        sink.write(commentBytes)
    }

    val entrySize: Int
        get() = 46 + name.encodeToByteArray().size + extra.size + comment.encodeToByteArray().size
}
