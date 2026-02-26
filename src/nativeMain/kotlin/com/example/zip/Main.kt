package com.example.zip

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import okio.Buffer
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.SEEK_END
import platform.posix.close
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.lseek
import platform.posix.open
import platform.posix.read
import platform.posix.stat
import platform.posix.write

/**
 * Пример использования стримингового ZIP архиватора с Data Descriptor
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "create" -> createArchive(args)
        "create-streaming" -> createStreamingArchive(args)
        "list" -> listArchive(args)
        "extract" -> extractFile(args)
        else -> {
            println("Unknown command: ${args[0]}")
            printUsage()
        }
    }
}

private fun printUsage() {
    println("Streaming ZIP Archiver")
    println()
    println("Usage: zip-archiver <command> [options]")
    println()
    println("Commands:")
    println("  create <archive.zip> <file1> [file2...]")
    println("           Create ZIP archive (macOS/Linux compatible)")
    println("           - Small files (<10MB): buffered with optional DEFLATE compression")
    println("           - Large files (>=10MB): two-pass processing, no Data Descriptor")
    println()
    println("  create-streaming <archive.zip> <file1> [file2...]")
    println("           Create ZIP archive with Data Descriptor (true streaming)")
    println("           WARNING: May not be compatible with macOS Archive Utility")
    println()
    println("  list <archive.zip>")
    println("           List archive contents")
    println()
    println("  extract <archive.zip> <file> [output]")
    println("           Extract file from archive")
}

/**
 * Создание ZIP архива в буферизованном режиме
 * Для больших файлов (>10MB) использует двухпроходную обработку без Data Descriptor
 */
@OptIn(ExperimentalForeignApi::class)
private fun createArchive(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: create <archive.zip> <file1> [file2...]")
        return
    }

    val archivePath = args[1]
    val files = args.drop(2)

    println("Creating archive (compatible mode): $archivePath")

    val fd = open(archivePath, O_WRONLY or O_CREAT or O_TRUNC, 420) // 0644
    if (fd < 0) {
        println("Error: Cannot create file $archivePath")
        return
    }

    try {
        val writer = StreamingZipWriter()
        val buffer = Buffer()
        val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024L // 10 MB

        for (filePath in files) {
            println("  Adding: $filePath")
            val name = filePath.substringAfterLast('/')

            // Открываем файл для определения размера
            val fileSource = FileSource(filePath)
            val fileSize = fileSource.size()

            try {
                if (fileSize > LARGE_FILE_THRESHOLD) {
                    // Большой файл - используем двухпроходную обработку
                    println("    Large file (${fileSize / 1024 / 1024}MB), using two-pass processing...")
                    writer.addLargeFile(buffer, name, fileSource, fileSize, CompressionMethod.STORE)
                } else if (fileSize <= Int.MAX_VALUE) {
                    // Маленький файл - читаем через fileSource вместо повторного открытия
                    val content = readFileFromSource(fileSource, fileSize)
                    if (content != null) {
                        // Используем DEFLATE для файлов > 1KB, иначе STORE
                        val compression = if (content.size > 1024) {
                            CompressionMethod.DEFLATE
                        } else {
                            CompressionMethod.STORE
                        }
                        writer.addFile(buffer, name, content, compression)
                        if (compression == CompressionMethod.DEFLATE) {
                            println("    Compressed with DEFLATE")
                        }
                    } else {
                        println("    Warning: Cannot read $filePath")
                    }
                } else {
                    println("    Warning: File too large: $filePath")
                }
            } finally {
                fileSource.close()
            }
        }

        writer.finish(buffer)

        // Записываем буфер в файл
        var totalWritten = 0L
        while (!buffer.exhausted()) {
            val bytes = buffer.readByteArray(minOf(buffer.size, 8192L))
            writeToFd(fd, bytes)
            totalWritten += bytes.size
        }

        fsync(fd)

        // Проверяем валидность архива
        val fileSize = getFileSize(fd)
        println("Archive created: ${writer.getEntryCount()} files")
        println("Archive size: $fileSize bytes")
        println("Data written: $totalWritten bytes")

        // Проверяем EOCD сигнатуру в конце файла
        lseek(fd, -22, SEEK_END)
        val eocdBuffer = ByteArray(4)
        val read = eocdBuffer.usePinned { pinned ->
            read(fd, pinned.addressOf(0), 4u)
        }
        if (read == 4L) {
            val signature = (eocdBuffer[0].toInt() and 0xFF) or
                    ((eocdBuffer[1].toInt() and 0xFF) shl 8) or
                    ((eocdBuffer[2].toInt() and 0xFF) shl 16) or
                    ((eocdBuffer[3].toInt() and 0xFF) shl 24)
            if (signature == 0x06054b50) {
                println("✓ EOCD signature verified")
            } else {
                val hexSig = buildString {
                    append("0x")
                    append(((signature shr 24) and 0xFF).toString(16).padStart(2, '0'))
                    append(((signature shr 16) and 0xFF).toString(16).padStart(2, '0'))
                    append(((signature shr 8) and 0xFF).toString(16).padStart(2, '0'))
                    append((signature and 0xFF).toString(16).padStart(2, '0'))
                }
                println("✗ Invalid EOCD signature: $hexSig")
            }
        }

        println("Compatible with macOS Archive Utility (no Data Descriptor)")

    } finally {
        close(fd)
    }
}

/**
 * Создание ZIP архива в стриминговом режиме с Data Descriptor
 */
@OptIn(ExperimentalForeignApi::class)
private fun createStreamingArchive(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: create-streaming <archive.zip> <file1> [file2...]")
        return
    }

    val archivePath = args[1]
    val files = args.drop(2)

    println("Creating archive (streaming mode with Data Descriptor): $archivePath")

    val fd = open(archivePath, O_WRONLY or O_CREAT or O_TRUNC, 420) // 0644
    if (fd < 0) {
        println("Error: Cannot create file $archivePath")
        return
    }

    try {
        val writer = StreamingZipWriter()
        val buffer = Buffer()

        for (filePath in files) {
            println("  Streaming: $filePath")

            // Читаем файл чанками через временный Source
            val fileContent = readFile(filePath)
            if (fileContent != null) {
                val name = filePath.substringAfterLast('/')
                val source = Buffer().apply { write(fileContent) }
                writer.addFileStreaming(buffer, name, source, CompressionMethod.STORE)
            } else {
                println("    Warning: Cannot read $filePath")
            }
        }

        writer.finish(buffer)

        // Записываем буфер в файл
        while (!buffer.exhausted()) {
            val bytes = buffer.readByteArray(minOf(buffer.size, 8192L))
            writeToFd(fd, bytes)
        }

        fsync(fd)

        println("Archive created: ${writer.getEntryCount()} files (using Data Descriptor)")

    } finally {
        close(fd)
    }
}

/**
 * Список содержимого архива
 */
private fun listArchive(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: list <archive.zip>")
        return
    }

    val archivePath = args[1]
    println("Listing archive: $archivePath")

    // TODO: Реализовать чтение центральной директории
    println("Archive listing not yet implemented")
}

/**
 * Извлечение файла из архива
 */
private fun extractFile(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: extract <archive.zip> <file> [output]")
        return
    }

    val archivePath = args[1]
    val fileName = args[2]
    val outputPath = args.getOrNull(3) ?: fileName

    println("Extracting $fileName from $archivePath to $outputPath")

    // TODO: Реализовать извлечение
    println("Extraction not yet implemented")
}

/**
 * Чтение файла в ByteArray
 */
@OptIn(ExperimentalForeignApi::class)
private fun readFile(path: String): ByteArray? {
    val fd = open(path, O_RDONLY)
    if (fd < 0) return null

    return try {
        val size = getFileSize(fd)
        readFileFromFd(fd, size)
    } finally {
        close(fd)
    }
}

/**
 * Чтение файла из FileSource
 */
@OptIn(ExperimentalForeignApi::class)
private fun readFileFromSource(fileSource: FileSource, size: Long): ByteArray? {
    if (size > Int.MAX_VALUE) return null

    fileSource.seek(0)
    val buffer = ByteArray(size.toInt())
    var totalRead = 0

    while (totalRead < size) {
        val read = fileSource.read(buffer, totalRead, (size - totalRead).toInt())
        if (read <= 0) break
        totalRead += read
    }

    return if (totalRead == size.toInt()) buffer else null
}

/**
 * Чтение файла из дескриптора
 */
@OptIn(ExperimentalForeignApi::class)
private fun readFileFromFd(fd: Int, size: Long): ByteArray? {
    if (size > Int.MAX_VALUE) return null

    val buffer = ByteArray(size.toInt())
    var totalRead = 0

    while (totalRead < size) {
        val read = buffer.usePinned { pinned ->
            read(fd, pinned.addressOf(totalRead), (size - totalRead).toULong())
        }
        if (read <= 0) break
        totalRead += read.toInt()
    }

    return if (totalRead == size.toInt()) buffer else null
}

/**
 * Получение размера файла
 */
@OptIn(ExperimentalForeignApi::class)
private fun getFileSize(fd: Int): Long {
    return memScoped {
        val statBuf = alloc<stat>()
        if (fstat(fd, statBuf.ptr) == 0) {
            statBuf.st_size
        } else {
            0L
        }
    }
}

/**
 * Запись в файловый дескриптор
 */
@OptIn(ExperimentalForeignApi::class)
private fun writeToFd(fd: Int, bytes: ByteArray) {
    bytes.usePinned { pinned ->
        var written = 0
        while (written < bytes.size) {
            val result = write(fd, pinned.addressOf(written), (bytes.size - written).toULong())
            if (result < 0) break
            written += result.toInt()
        }
    }
}
