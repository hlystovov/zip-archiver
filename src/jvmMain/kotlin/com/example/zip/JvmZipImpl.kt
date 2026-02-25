package com.example.zip

import kotlinx.io.Sink
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

internal actual fun compress(data: ByteArray): ByteArray {
    val bos = ByteArrayOutputStream()
    val dos = DeflaterOutputStream(bos)
    dos.write(data)
    dos.close()
    return bos.toByteArray()
}

internal actual fun compress(buffer: ByteArray, length: Int): ByteArray {
    val bos = ByteArrayOutputStream()
    val dos = DeflaterOutputStream(bos)
    dos.write(buffer, 0, length)
    dos.close()
    return bos.toByteArray()
}

internal actual fun decompress(compressed: ByteArray, uncompressedSize: Int): ByteArray {
    val bis = ByteArrayInputStream(compressed)
    val dis = InflaterInputStream(bis)
    val buffer = ByteArray(uncompressedSize)
    var totalRead = 0
    var bytesRead: Int
    while (totalRead < uncompressedSize) {
        bytesRead = dis.read(buffer, totalRead, uncompressedSize - totalRead)
        if (bytesRead == -1) break
        totalRead += bytesRead
    }
    dis.close()
    return buffer
}

actual fun createTempFile(prefix: String): String {
    return File.createTempFile(prefix, null).absolutePath
}

actual fun deleteTempFile(path: String) {
    File(path).delete()
}

actual fun compressToTempFile(
    source: FileSource,
    sourceSize: Long,
    tempFilePath: String
): Long {
    val tempFile = File(tempFilePath)
    val bos = FileOutputStream(tempFile)
    val dos = DeflaterOutputStream(bos)

    val buffer = ByteArray(8192)
    var bytesRead: Int
    var totalRead = 0L

    while (totalRead < sourceSize) {
        bytesRead = source.read(buffer, 0, minOf(buffer.size, (sourceSize - totalRead).toInt()))
        if (bytesRead == -1) break
        dos.write(buffer, 0, bytesRead)
        totalRead += bytesRead
    }

    dos.close()
    return totalRead
}

actual fun copyFromTempFile(tempFilePath: String, sink: Sink, size: Long): Long {
    val tempFile = File(tempFilePath)
    val fis = FileInputStream(tempFile)
    val buffer = ByteArray(8192)
    var totalRead = 0L
    var bytesRead: Int

    while (totalRead < size) {
        bytesRead = fis.read(buffer, 0, minOf(buffer.size, (size - totalRead).toInt()))
        if (bytesRead == -1) break
        sink.write(buffer, 0, bytesRead)
        totalRead += bytesRead
    }

    fis.close()
    return totalRead
}

actual class FileSource {
    private val file: File
    private val fis: FileInputStream

    actual constructor(path: String) {
        this.file = File(path)
        this.fis = FileInputStream(file)
    }

    actual fun seek(position: Long) {
        fis.channel.position(position)
    }

    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return fis.read(buffer, offset, length)
    }

    actual fun size(): Long {
        return file.length()
    }

    actual fun close() {
        fis.close()
    }
}
