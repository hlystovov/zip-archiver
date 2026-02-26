package com.example.zip

import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use
import kotlin.random.Random

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

// Helper extensions - ZIP uses little-endian format
private fun BufferedSink.writeZipInt(value: Int) {
    writeByte(value and 0xFF)
    writeByte((value shr 8) and 0xFF)
    writeByte((value shr 16) and 0xFF)
    writeByte((value shr 24) and 0xFF)
}

private fun BufferedSink.writeZipShort(value: Short) {
    writeByte(value.toInt() and 0xFF)
    writeByte((value.toInt() shr 8) and 0xFF)
}

/**
 * CRC32 Calculator
 */
class CRC32 {
    private val table = IntArray(256) { i ->
        var crc = i
        repeat(8) {
            crc = if ((crc and 1) != 0) {
                (crc ushr 1) xor 0xEDB88320.toInt()
            } else {
                crc ushr 1
            }
        }
        crc
    }

    private var value = 0

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        var crc = value.inv()
        for (i in offset until offset + length) {
            crc = table[(crc xor bytes[i].toInt()) and 0xFF] xor (crc ushr 8)
        }
        value = crc.inv()
    }

    fun update(byte: Byte) {
        value = table[(value xor byte.toInt()) and 0xFF] xor (value ushr 8)
    }

    fun reset() {
        value = 0
    }

    fun getValue(): Int = value
}

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
     */
    fun addFileStreaming(
        sink: BufferedSink,
        name: String,
        source: BufferedSource,
        compression: Short = CompressionMethod.STORE
    ) {
        val flags = ZipFlags.DATA_DESCRIPTOR
        val headerOffset = currentOffset

        val localHeader = LocalFileHeader(
            flags = flags,
            compression = compression,
            crc32 = 0,
            compressedSize = 0,
            uncompressedSize = 0,
            name = name
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
            headerOffset = headerOffset
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
        compression: Short = CompressionMethod.STORE
    ) {
        crc32.reset()
        crc32.update(data)
        val crc = crc32.getValue()

        val compressedData = if (compression == CompressionMethod.DEFLATE) {
            compress(data)
        } else {
            data
        }

        val localHeader = LocalFileHeader(
            name = name,
            compression = compression,
            crc32 = crc,
            compressedSize = compressedData.size,
            uncompressedSize = data.size
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
                uncompressedSize = data.size
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
        compression: Short = CompressionMethod.STORE
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
            compressLargeFileToTemp(fileSource, fileSize, sink, name, crc, headerOffset)
            return
        }

        val compressedSize = intSize

        val localHeader = LocalFileHeader(
            name = name,
            compression = compression,
            crc32 = crc,
            compressedSize = compressedSize,
            uncompressedSize = intSize
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
                uncompressedSize = intSize
            )
        )
    }

    private fun compressLargeFileToTemp(
        fileSource: FileSource,
        fileSize: Long,
        sink: BufferedSink,
        name: String,
        crc: Int,
        headerOffset: Int
    ) {
        val tempFile = createTempFile("zip_compress_")
        try {
            val compressedSize = compressToTempFile(fileSource, fileSize, tempFile)

            val useCompression = compressedSize < fileSize
            val finalCompression = if (useCompression) CompressionMethod.DEFLATE else CompressionMethod.STORE
            val finalCompressedSize = if (useCompression) compressedSize.toInt() else fileSize.toInt()

            val localHeader = LocalFileHeader(
                name = name,
                compression = finalCompression,
                crc32 = crc,
                compressedSize = finalCompressedSize,
                uncompressedSize = fileSize.toInt()
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
                    uncompressedSize = fileSize.toInt()
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
            val cdEntry = CentralDirectoryEntry(
                name = entry.name,
                flags = entry.flags,
                compression = entry.compression,
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

/**
 * Создание временного файла
 */
fun createTempFile(prefix: String): String {
    val fileSystem = FileSystem.SYSTEM
    val tempDir = getTempDirPath()
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

    fileSystem.sink(tempPath).buffer().use { sink ->
        val buffer = ByteArray(8192)

        while (totalRead < sourceSize) {
            val toRead = kotlin.math.min(buffer.size, (sourceSize - totalRead).toInt())
            val bytesRead = source.read(buffer, 0, toRead)
            if (bytesRead <= 0) break
            val bos = compress(buffer)
            sink.write(bos)
            totalRead += bytesRead
        }
    }

    return totalRead
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

/**
 * Сжатие данных (Deflate) - платформенно-зависимая реализация
 */
internal expect fun compress(data: ByteArray): ByteArray

internal expect fun compress(buffer: ByteArray, length: Int): ByteArray

/**
 * Распаковка данных (Inflate)
 */
internal expect fun decompress(compressed: ByteArray, uncompressedSize: Int): ByteArray


expect fun getTempDirPath(): String
