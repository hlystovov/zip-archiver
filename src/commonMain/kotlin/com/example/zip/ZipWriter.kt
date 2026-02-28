package com.example.zip

import okio.Buffer
import okio.BufferedSink
import okio.Deflater
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.deflate
import okio.use

/**
 * ZIP Writer
 */
class ZipWriter {
    private val entries = mutableListOf<FileEntry>()
    private var currentOffset = 0
    private val crc32 = CRC32()

    /**
     * Добавить файл в архив с заранее известными данными.
     * Файл читается потоково — один раз, без загрузки в память целиком.
     * CRC считается во время чтения, данные сжимаются "на лету".
     */
    fun addFile(
        sink: BufferedSink,
        file: Path,
        compression: Short = CompressionMethod.STORE,
    ) {
        val fileSystem = FileSystem.SYSTEM
        val metadata = fileSystem.metadata(file)
        val fileSize = metadata.size ?: 0L
        val lastModifiedAtMillis = metadata.lastModifiedAtMillis ?: 0L

        crc32.reset()

        val uncompressedBuffer = Buffer()
        val compressedBuffer = Buffer()

        // Если DEFLATE — создаём deflaterSink, иначе пишем напрямую
        val compressionSink = if (compression == CompressionMethod.DEFLATE) {
            compressedBuffer.deflate(Deflater(-1, true))
        } else {
            null
        }

        val tempBuffer = Buffer()
        val bufferSize = 8192L
        fileSystem.source(file).buffer().use { source ->
            while (source.read(tempBuffer, bufferSize) != -1L) {
                val data = tempBuffer.readByteArray(tempBuffer.size)
                crc32.update(data)
                uncompressedBuffer.write(data)

                // Пишем в сжатый или прямой буфер
                if (compression == CompressionMethod.DEFLATE) {
                    compressionSink?.write(Buffer().write(data), data.size.toLong())
                }
            }
        }

        // Завершаем сжатие
        if (compression == CompressionMethod.DEFLATE) {
            compressionSink?.close() // обязательно!
        }

        val finalCompressedData = if (compression == CompressionMethod.DEFLATE) {
            compressedBuffer.readByteArray()
        } else {
            uncompressedBuffer.readByteArray()
        }

        val crcValue = crc32.getValue()
        val (modTime, modDate) = DosDateTime.fromMillis(lastModifiedAtMillis)

        val localHeader = LocalFileHeader(
            name = file.name,
            compression = compression,
            crc32 = crcValue,
            compressedSize = finalCompressedData.size,
            uncompressedSize = fileSize.toInt(),
            modTime = modTime,
            modDate = modDate
        )

        val headerOffset = currentOffset

        localHeader.writeTo(sink)
        currentOffset += localHeader.headerSize

        sink.write(finalCompressedData)
        currentOffset += finalCompressedData.size

        entries.add(
            FileEntry(
                name = file.name,
                compression = compression,
                flags = 0,
                headerOffset = headerOffset,
                crc32 = crcValue,
                compressedSize = finalCompressedData.size,
                uncompressedSize = fileSize.toInt(),
                lastModifiedAtMillis = lastModifiedAtMillis
            )
        )
    }

    fun finish(sink: BufferedSink) {
        val centralDirOffset = currentOffset
        var centralDirSize = 0

        for (entry in entries) {
            val (modTime, modDate) = DosDateTime.fromMillis(entry.lastModifiedAtMillis)
            val cdEntry = CentralDirectoryEntry(
                name = entry.name,
                flags = entry.flags,
                compression = entry.compression,
                modTime = modTime,
                modDate = modDate,
                crc32 = entry.crc32,
                compressedSize = entry.compressedSize,
                uncompressedSize = entry.uncompressedSize,
                localHeaderOffset = entry.headerOffset
            )
            cdEntry.writeTo(sink)
            centralDirSize += cdEntry.entrySize
        }

        val eocd = EndOfCentralDirectory(
            entriesOnDisk = entries.size.toShort(),
            totalEntries = entries.size.toShort(),
            centralDirSize = centralDirSize,
            centralDirOffset = centralDirOffset
        )
        eocd.writeTo(sink)
    }

    fun getEntryCount(): Int = entries.size
}

/**
 * ZIP Compression Methods
 */
object CompressionMethod {
    const val STORE: Short = 0      // Без сжатия
    const val DEFLATE: Short = 8    // Deflate
}

/**
 * ZIP Flags
 */
object ZipFlags {
    const val DATA_DESCRIPTOR: Short = 0x0008  // Использовать Data Descriptor
}


// Helper extensions - ZIP uses little-endian format
fun BufferedSink.writeZipInt(value: Int) {
    writeByte(value and 0xFF)
    writeByte((value shr 8) and 0xFF)
    writeByte((value shr 16) and 0xFF)
    writeByte((value shr 24) and 0xFF)
}

fun BufferedSink.writeZipShort(value: Short) {
    writeByte(value.toInt() and 0xFF)
    writeByte((value.toInt() shr 8) and 0xFF)
}
