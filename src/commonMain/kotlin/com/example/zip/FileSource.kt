package com.example.zip

import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer

/**
 * Кроссплатформенный FileSource на основе okio FileSystem
 * Использует FileSystem.SYSTEM.metadata для получения информации о файлах
 */
class FileSource(path: String) {
    private val fileSystem = FileSystem.SYSTEM
    private val pathObj = path.toPath()
    private var bufferedSource: BufferedSource? = null
    private var currentPosition: Long = 0

    init {
        bufferedSource = fileSystem.source(pathObj).buffer()
    }

    fun seek(position: Long) {
        bufferedSource?.close()
        bufferedSource = fileSystem.source(pathObj).buffer()
        currentPosition = 0

        var remaining = position
        while (remaining > 0) {
            val toSkip = kotlin.math.min(remaining.toInt(), 8192).toLong()
            val skipped = bufferedSource!!.readByteString(toSkip).size.toLong()
            if (skipped == 0L) break
            remaining -= skipped
            currentPosition += skipped
        }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val byteString = bufferedSource?.readByteString(length.toLong()) ?: return -1
        val bytes = byteString.toByteArray()
        val bytesToCopy = kotlin.math.min(bytes.size, length)
        bytes.copyInto(buffer, offset, 0, bytesToCopy)
        currentPosition += bytesToCopy
        return bytesToCopy
    }

    fun size(): Long {
        // Используем FileSystem.SYSTEM.metadata для получения размера файла
        return fileSystem.metadata(pathObj).size ?: 0L
    }

    fun close() {
        bufferedSource?.close()
        bufferedSource = null
    }
}
