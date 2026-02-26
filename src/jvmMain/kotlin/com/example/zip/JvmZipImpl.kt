package com.example.zip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

actual fun getTempDirPath(): String = System.getProperty("java.io.tmpdir") ?: "/tmp"
