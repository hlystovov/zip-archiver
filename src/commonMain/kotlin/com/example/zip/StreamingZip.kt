package com.example.zip

import okio.BufferedSink
import okio.BufferedSource
import kotlin.experimental.and

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

    val recordSize: Int
        get() = 22 + comment.encodeToByteArray().size
}

// Helper extensions - ZIP uses little-endian format
// Note: These are named writeZipInt/writeZipShort to avoid conflict with kotlinx.io methods
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
        // Используем Data Descriptor для стриминга
        val useDataDescriptor = true
        val flags = if (useDataDescriptor) ZipFlags.DATA_DESCRIPTOR else 0

        val headerOffset = currentOffset

        // Создаем локальный заголовок с нулевыми размерами
        val localHeader = LocalFileHeader(
            flags = flags,
            compression = compression,
            crc32 = 0,
            compressedSize = 0,
            uncompressedSize = 0,
            name = name
        )

        // Пишем локальный заголовок
        localHeader.writeTo(sink)
        currentOffset += localHeader.headerSize

        // Начинаем отслеживать данные
        crc32.reset()
        var compressedSize = 0
        var uncompressedSize = 0

        // Создаем запись для отслеживания
        val entry = FileEntry(
            name = name,
            compression = compression,
            flags = flags,
            headerOffset = headerOffset
        )

        // Обрабатываем данные чанками
        val buffer = ByteArray(8192)

        while (!source.exhausted()) {
            val bytesRead = source.read(buffer, 0, buffer.size)
            if (bytesRead <= 0) break

            uncompressedSize += bytesRead

            // Обновляем CRC
            crc32.update(buffer, 0, bytesRead)

            // Сжимаем или сохраняем как есть
            val dataToWrite = if (compression == CompressionMethod.DEFLATE) {
                compress(buffer, bytesRead)
            } else {
                buffer.copyOf(bytesRead)
            }

            sink.write(dataToWrite)
            compressedSize += dataToWrite.size
            currentOffset += dataToWrite.size
        }

        // Обновляем entry
        entry.crc32 = crc32.getValue()
        entry.compressedSize = compressedSize
        entry.uncompressedSize = uncompressedSize
        entries.add(entry)

        // Пишем Data Descriptor
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
     * Более эффективно для небольших файлов
     *
     * @param sink Куда писать данные архива
     * @param name Имя файла в архиве
     * @param data Данные файла
     * @param compression Метод сжатия
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

        // Для небольших файлов вычисляем заранее
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
     * Не использует Data Descriptor - совместим со всеми ZIP-ридерами
     * Первый проход: вычисляет CRC и размер
     * Второй проход: пишет данные
     *
     * @param sink Куда писать данные архива
     * @param name Имя файла в архиве
     * @param fileSource Источник файла с возможностью повторного чтения
     * @param fileSize Размер файла
     * @param compression Метод сжатия
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

        // Первый проход: вычисляем CRC
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

        // Для STORE метода - размеры совпадают
        // Для DEFLATE - сжимаем во временный файл
        if (compression == CompressionMethod.DEFLATE) {
            compressLargeFileToTemp(fileSource, fileSize, sink, name, crc, headerOffset)
            return // compressLargeFileToTemp already added the entry
        }

        val compressedSize = intSize

        // Для STORE метода - пишем заголовок и данные
        val localHeader = LocalFileHeader(
            name = name,
            compression = compression,
            crc32 = crc,
            compressedSize = compressedSize,
            uncompressedSize = intSize
        )

        localHeader.writeTo(sink)
        currentOffset += localHeader.headerSize

        // Второй проход: пишем данные
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

    /**
     * Сжимает большой файл во временный файл, затем копирует в архив
     * Использует двухпроходный подход: сначала сжатие во временный файл для получения размера,
     * затем копирование в архив
     */
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
            // Сжимаем данные во временный файл
            val compressedSize = compressToTempFile(fileSource, fileSize, tempFile)

            // Если сжатие неэффективно (размер увеличился), используем STORE
            val useCompression = compressedSize < fileSize
            val finalCompression = if (useCompression) CompressionMethod.DEFLATE else CompressionMethod.STORE
            val finalCompressedSize = if (useCompression) compressedSize.toInt() else fileSize.toInt()

            // Пишем заголовок
            val localHeader = LocalFileHeader(
                name = name,
                compression = finalCompression,
                crc32 = crc,
                compressedSize = finalCompressedSize,
                uncompressedSize = fileSize.toInt()
            )

            localHeader.writeTo(sink)
            currentOffset += localHeader.headerSize

            // Копируем данные из временного файла в архив
            if (useCompression) {
                val bytesCopied = copyFromTempFile(tempFile, sink, compressedSize)
                currentOffset += bytesCopied.toInt()
            } else {
                // Если не используем сжатие, копируем оригинальные данные
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

    /**
     * Завершить архив - записать центральную директорию
     * Этот метод ДОЛЖЕН быть вызван после добавления всех файлов
     */
    fun finish(sink: BufferedSink) {
        val centralDirOffset = currentOffset
        var centralDirSize = 0

        // Пишем центральную директорию
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

        // Пишем EOCD
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
) {
    val dataOffset: Int
        get() = offset + headerSize
}

/**
 * Стриминговый ZIP Reader
 * Позволяет читать отдельные файлы без загрузки всего архива в память
 */
class StreamingZipReader {
    private val entries = mutableListOf<ZipEntryInfo>()

    /**
     * Загрузить структуру архива (центральную директорию)
     */
    fun load(source: BufferedSource, fileSize: Long) {
        entries.clear()
        // TODO: Реализовать чтение центральной директории
    }

    /**
     * Получить список файлов в архиве
     */
    fun getEntries(): List<ZipEntryInfo> = entries.toList()

    /**
     * Извлечь файл
     */
    fun extractFile(source: BufferedSource, entry: ZipEntryInfo, sink: BufferedSink) {
        // TODO: Реализовать извлечение с учетом Data Descriptor
    }
}

/**
 * Сжатие данных (Deflate)
 * Платформенно-зависимая реализация
 */
internal expect fun compress(data: ByteArray): ByteArray

internal expect fun compress(buffer: ByteArray, length: Int): ByteArray

/**
 * Распаковка данных (Inflate)
 */
internal expect fun decompress(compressed: ByteArray, uncompressedSize: Int): ByteArray

/**
 * Платформенно-зависимый FileSource с поддержкой seek
 */
expect class FileSource(path: String) {
    fun seek(position: Long)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun size(): Long
    fun close()
}

/**
 * Создание временного файла
 */
expect fun createTempFile(prefix: String): String

/**
 * Удаление временного файла
 */
expect fun deleteTempFile(path: String)

/**
 * Сжатие данных из source во временный файл
 * @return размер сжатых данных
 */
expect fun compressToTempFile(
    source: FileSource,
    sourceSize: Long,
    tempFilePath: String
): Long

/**
 * Копирование данных из временного файла в sink
 * @return количество скопированных байт
 */
expect fun copyFromTempFile(tempFilePath: String, sink: BufferedSink, size: Long): Long
