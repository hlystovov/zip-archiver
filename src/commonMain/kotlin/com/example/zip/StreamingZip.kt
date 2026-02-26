package com.example.zip

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Deflater
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.deflate
import okio.use
import kotlin.random.Random

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

        entry.crc32 = crc32.getValue()
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
        name: String,
        data: ByteArray,
        compression: Short = CompressionMethod.STORE,
        lastModifiedAtMillis: Long = 0
    ) {
        crc32.reset()
        crc32.update(data)
        val crc = crc32.getValue()

        val compressedData = if (compression == CompressionMethod.DEFLATE) {
            compress(data)
        } else {
            data
        }

        val (modTime, modDate) = DosDateTime.fromMillis(lastModifiedAtMillis)
        val localHeader = LocalFileHeader(
            name = name,
            compression = compression,
            crc32 = crc,
            compressedSize = compressedData.size,
            uncompressedSize = data.size,
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
                name = name,
                compression = compression,
                flags = 0,
                headerOffset = headerOffset,
                crc32 = crc,
                compressedSize = compressedData.size,
                uncompressedSize = data.size,
                lastModifiedAtMillis = lastModifiedAtMillis
            )
        )
    }

    /**
     * Добавить файл в архив с двухпроходной обработкой (для больших файлов)
     */
    fun addLargeFile(
        sink: BufferedSink,
        name: String,
        fileSource: FileSource,
        fileSize: Long,
        compression: Short = CompressionMethod.STORE,
        lastModifiedAtMillis: Long = 0
    ) {
        require(fileSize <= Int.MAX_VALUE) { "File too large for ZIP32" }
        val headerOffset = currentOffset
        val intSize = fileSize.toInt()

        crc32.reset()
        val buffer = ByteArray(65536)
        var totalRead = 0L

        fileSource.seek(0)
        while (totalRead < fileSize) {
            val toRead = minOf(buffer.size.toLong(), fileSize - totalRead).toInt()
            val read = fileSource.read(buffer, 0, toRead)
            if (read <= 0) break
            crc32.update(buffer, 0, read)
            totalRead += read
        }
        val crc = crc32.getValue()

        if (compression == CompressionMethod.DEFLATE) {
            compressLargeFileToTemp(fileSource, fileSize, sink, name, crc, headerOffset, lastModifiedAtMillis)
            return
        }

        val compressedSize = intSize
        val (modTime, modDate) = DosDateTime.fromMillis(lastModifiedAtMillis)

        val localHeader = LocalFileHeader(
            name = name,
            compression = compression,
            crc32 = crc,
            compressedSize = compressedSize,
            uncompressedSize = intSize,
            modTime = modTime,
            modDate = modDate
        )

        localHeader.writeTo(sink)
        currentOffset += localHeader.headerSize

        fileSource.seek(0)
        totalRead = 0
        while (totalRead < fileSize) {
            val toRead = minOf(buffer.size.toLong(), fileSize - totalRead).toInt()
            val read = fileSource.read(buffer, 0, toRead)
            if (read <= 0) break
            sink.write(buffer.copyOf(read))
            currentOffset += read
            totalRead += read
        }

        entries.add(
            FileEntry(
                name = name,
                compression = compression,
                flags = 0,
                headerOffset = headerOffset,
                crc32 = crc,
                compressedSize = compressedSize,
                uncompressedSize = intSize,
                lastModifiedAtMillis = lastModifiedAtMillis
            )
        )
    }

    private fun compressLargeFileToTemp(
        fileSource: FileSource,
        fileSize: Long,
        sink: BufferedSink,
        name: String,
        crc: Int,
        headerOffset: Int,
        lastModifiedAtMillis: Long = 0
    ) {
        val tempFile = createTempFile("zip_compress_")
        try {
            fileSource.seek(0)
            val compressedSize = compressToTempFile(fileSource, fileSize, tempFile)

            val useCompression = compressedSize < fileSize
            val finalCompression = if (useCompression) CompressionMethod.DEFLATE else CompressionMethod.STORE
            val finalCompressedSize = if (useCompression) compressedSize.toInt() else fileSize.toInt()

            val (modTime, modDate) = DosDateTime.fromMillis(lastModifiedAtMillis)
            val localHeader = LocalFileHeader(
                name = name,
                compression = finalCompression,
                crc32 = crc,
                compressedSize = finalCompressedSize,
                uncompressedSize = fileSize.toInt(),
                modTime = modTime,
                modDate = modDate
            )

            localHeader.writeTo(sink)
            currentOffset += localHeader.headerSize

            if (useCompression) {
                val bytesCopied = copyFromTempFile(tempFile, sink, compressedSize)
                currentOffset += bytesCopied.toInt()
            } else {
                fileSource.seek(0)
                val buffer = ByteArray(65536)
                var totalRead = 0L
                while (totalRead < fileSize) {
                    val toRead = minOf(buffer.size.toLong(), fileSize - totalRead).toInt()
                    val read = fileSource.read(buffer, 0, toRead)
                    if (read <= 0) break
                    sink.write(buffer.copyOf(read))
                    currentOffset += read
                    totalRead += read
                }
            }

            entries.add(
                FileEntry(
                    name = name,
                    compression = finalCompression,
                    flags = 0,
                    headerOffset = headerOffset,
                    crc32 = crc,
                    compressedSize = finalCompressedSize,
                    uncompressedSize = fileSize.toInt(),
                    lastModifiedAtMillis = lastModifiedAtMillis
                )
            )
        } finally {
            deleteTempFile(tempFile)
        }
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

/**
 * Создание временного файла
 */
fun createTempFile(prefix: String): String {
    val fileSystem = FileSystem.SYSTEM
    val tempDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()
    val randomSuffix = Random.nextInt(100000, 999999)
    val tempPath = "$tempDir/${prefix}$randomSuffix.tmp".toPath()

    // Создаем пустой файл через okio
    fileSystem.write(tempPath) {
        // Пустой файл
    }

    return tempPath.toString()
}

/**
 * Удаление временного файла
 */
fun deleteTempFile(path: String) {
    val fileSystem = FileSystem.SYSTEM
    val pathObj = path.toPath()
    try {
        fileSystem.delete(pathObj)
    } catch (_: Exception) {
        // Игнорируем ошибки при удалении
    }
}

/**
 * Сжатие данных из source во временный файл
 */
fun compressToTempFile(
    source: FileSource,
    sourceSize: Long,
    tempFilePath: String
): Long {
    val fileSystem = FileSystem.SYSTEM
    val tempPath = tempFilePath.toPath()
    var totalRead = 0L
    var compressedSize = 0L

    fileSystem.sink(tempPath).buffer().use { sink ->
        // nowrap=true для raw DEFLATE (без zlib заголовка)
        val deflater = Deflater(-1, true)
        val deflaterSink = sink.deflate(deflater)

        deflaterSink.use { compressedSink ->
            val buffer = ByteArray(8192)

            while (totalRead < sourceSize) {
                val toRead = kotlin.math.min(buffer.size, (sourceSize - totalRead).toInt())
                val bytesRead = source.read(buffer, 0, toRead)
                if (bytesRead <= 0) break
                compressedSink.write(Buffer().apply { write(buffer, 0, bytesRead) }, bytesRead.toLong())
                totalRead += bytesRead
            }
            compressedSink.flush()
        }
    }

    // Получаем реальный размер сжатого файла
    compressedSize = fileSystem.metadata(tempPath).size ?: 0L
    return compressedSize
}

/**
 * Копирование данных из временного файла в sink
 */
fun copyFromTempFile(tempFilePath: String, sink: BufferedSink, size: Long): Long {
    val fileSystem = FileSystem.SYSTEM
    val path = tempFilePath.toPath()
    var totalRead = 0L

    fileSystem.source(path).use { source ->
        val bufferedSource = source.buffer()

        while (totalRead < size) {
            val toRead = kotlin.math.min(65536L, size - totalRead).toInt()
            val byteString = bufferedSource.readByteString(toRead.toLong())
            if (byteString.size == 0) break

            sink.write(byteString)
            totalRead += byteString.size
        }
    }

    return totalRead
}
