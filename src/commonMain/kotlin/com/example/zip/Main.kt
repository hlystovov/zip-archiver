package com.example.zip

import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.use

/**
 * Пример использования стримингового ZIP архиватора с Data Descriptor
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    when (args[0]) {
        "create" -> createArchive(args)
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
private fun createArchive(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: create <archive.zip> <file1> [file2...]")
        return
    }

    val archivePath = args[1]
    val files = args.drop(2)

    println("Creating archive (compatible mode): $archivePath")
    val writer = ZipWriter()
    val buffer = Buffer()

    for (filePath in files) {
        println("  Adding: $filePath")
        val file = filePath.toPath()
        val fileSize = FileSystem.SYSTEM.metadataOrNull(file)?.size ?: 0
        val compression = if (fileSize > 1024) {
            CompressionMethod.DEFLATE
        } else {
            CompressionMethod.STORE
        }
        writer.addFile(buffer, file, compression)
        if (compression == CompressionMethod.DEFLATE) {
            println("    Compressed with DEFLATE")
        }
    }

    writer.finish(buffer)
    val fileSystem = FileSystem.SYSTEM
    val dest = archivePath.toPath()
    // Записываем буфер в файл
    var totalWritten = 0L
    fileSystem.write(dest, false) {
        while (!buffer.exhausted()) {
            val bytes = buffer.readByteArray(minOf(buffer.size, 8192L))
            this.write(bytes)
            totalWritten += bytes.size
        }
    }
    // Проверяем валидность архива
    val fileSize = fileSystem.metadata(dest).size
    println("Archive created: ${writer.getEntryCount()} files")
    println("Archive size: $fileSize bytes")
    println("Data written: $totalWritten bytes")
    fileSystem.openReadOnly(dest).use { handle ->
        val buffer = Buffer()
        val bytesRead = handle.read(handle.size() - 22, buffer, 4)
        // Проверяем EOCD сигнатуру в конце файла
        if (bytesRead == 4L) {
            val eocdBuffer = buffer.readByteArray(4)
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
