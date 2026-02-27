package com.example.zip

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Deflater
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.deflate
import okio.internal.CRC32
import okio.use

/**
 * Стриминговый ZIP Writer с поддержкой Data Descriptor
 * Позволяет писать файлы в архив без предварительного знания размеров
 */
class StreamingZipWriter {
    private val entries = mutableListOf<FileEntry>()
    private var currentOffset = 0
    private val crc32 = CRC32()

    /**
     * Добавить файл в архив с использованием Data Descriptor
     * Размер и CRC вычисляются во время записи и пишутся после данных
     *
     * @param sink Куда писать данные архива
     * @param name Имя файла в архиве
     * @param source Источник данных для стриминговой записи
     * @param compression Метод сжатия (STORE или DEFLATE)
     * @param lastModifiedAtMillis Время последнего изменения файла в миллисекундах
     */
    fun addFileStreaming(
        sink: BufferedSink,
        name: String,
        source: BufferedSource,
        compression: Short = CompressionMethod.STORE,
        lastModifiedAtMillis: Long = 0
    ) {
        val flags = ZipFlags.DATA_DESCRIPTOR
        val headerOffset = currentOffset

        val (modTime, modDate) = DosDateTime.fromMillis(lastModifiedAtMillis)
        val localHeader = LocalFileHeader(
            flags = flags,
            compression = compression,
            crc32 = 0,
            compressedSize = 0,
            uncompressedSize = 0,
            name = name,
            modTime = modTime,
            modDate = modDate
        )

        localHeader.writeTo(sink)
        currentOffset += localHeader.headerSize

        crc32.reset()
        var compressedSize = 0
        var uncompressedSize = 0

        val entry = FileEntry(
            name = name,
            compression = compression,
            flags = flags,
            headerOffset = headerOffset,
            lastModifiedAtMillis = lastModifiedAtMillis
        )

        val buffer = ByteArray(8192)

        while (!source.exhausted()) {
            val bytesRead = source.read(buffer, 0, buffer.size)
            if (bytesRead <= 0) break

            uncompressedSize += bytesRead
            crc32.update(buffer, 0, bytesRead)

            val dataToWrite = if (compression == CompressionMethod.DEFLATE) {
                compress(buffer, bytesRead)
            } else {
                buffer.copyOf(bytesRead)
            }

            sink.write(dataToWrite)
            compressedSize += dataToWrite.size
            currentOffset += dataToWrite.size
        }

        entry.crc32 = crc32.getValue().toInt()
        entry.compressedSize = compressedSize
        entry.uncompressedSize = uncompressedSize
        entries.add(entry)

        val dataDescriptor = DataDescriptor(
            crc32 = entry.crc32,
            compressedSize = entry.compressedSize,
            uncompressedSize = entry.uncompressedSize,
            useSignature = true
        )
        dataDescriptor.writeTo(sink)
        currentOffset += dataDescriptor.size
    }

    /**
     * Добавить файл в архив с заранее известными данными
     */
    fun addFile(
        sink: BufferedSink,
        file: Path,
        compression: Short = CompressionMethod.STORE,
    ) {
        val fileSystem = FileSystem.SYSTEM
        val metadata = fileSystem.metadata(file)
        val fileSize = metadata.size ?: 0
        val lastModifiedAtMillis = metadata.lastModifiedAtMillis ?: 0

        crc32.reset()
        var totalRead = 0L
        val fileSource = fileSystem.source(file).buffer()
        while (totalRead < fileSize) {
            val toRead = minOf(65536, fileSize - totalRead)
            val buffer = fileSource.readByteArray(toRead)
            if (buffer.isEmpty()) break
            crc32.update(buffer)
            totalRead += buffer.size
        }
        val crc = crc32.getValue()

        val compressedData = if (compression == CompressionMethod.DEFLATE) {
            fileSystem.source(file).buffer().use { source ->
                val outputBuffer = Buffer()
                val deflater = Deflater(-1, true)
                val deflaterSink = outputBuffer.deflate(deflater)

                var totalRead = 0L
                while (totalRead < fileSize) {
                    val toRead = kotlin.math.min(65536, (fileSize - totalRead).toInt())
                    val buffer = source.readByteArray(toRead.toLong())
                    if (buffer.isEmpty()) break
                    deflaterSink.write(Buffer().apply { write(buffer) }, buffer.size.toLong())
                    totalRead += buffer.size
                }
                deflaterSink.close()
                outputBuffer.readByteArray()
            }
        } else {
            fileSystem.source(file).buffer().readByteArray()
        }
        val (modTime, modDate) = DosDateTime.fromMillis(lastModifiedAtMillis)
        val localHeader = LocalFileHeader(
            name = file.name,
            compression = compression,
            crc32 = crc.toInt(),
            compressedSize = compressedData.size,
            uncompressedSize = fileSize.toInt(),
            modTime = modTime,
            modDate = modDate
        )

        val headerOffset = currentOffset

        localHeader.writeTo(sink)
        currentOffset += localHeader.headerSize

        sink.write(compressedData)
        currentOffset += compressedData.size

        entries.add(
            FileEntry(
                name = file.name,
                compression = compression,
                flags = 0,
                headerOffset = headerOffset,
                crc32 = crc.toInt(),
                compressedSize = compressedData.size,
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
