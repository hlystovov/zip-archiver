package com.example.zip

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import okio.BufferedSink
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_WRONLY
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.fstat
import platform.posix.getenv
import platform.posix.lseek
import platform.posix.open
import platform.posix.read
import platform.posix.stat
import platform.posix.unlink
import platform.posix.write
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream
import kotlin.random.Random

/**
 * Создание временного файла
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createTempFile(prefix: String): String {
    val tempDir = getenv("TMPDIR")?.toString() ?: "/tmp"
    val randomSuffix = Random.nextInt(100000, 999999)
    val tempPath = "$tempDir/${prefix}$randomSuffix.tmp"

    val fd = open(tempPath, O_RDWR or O_CREAT or O_EXCL, 384) // 0600
    if (fd < 0) {
        throw IllegalStateException("Cannot create temp file: $tempPath")
    }
    close(fd)

    return tempPath
}

/**
 * Удаление временного файла
 */
@OptIn(ExperimentalForeignApi::class)
actual fun deleteTempFile(path: String) {
    unlink(path)
}

/**
 * Сжатие данных из source во временный файл с использованием потокового DEFLATE
 * @return размер сжатых данных
 */
@OptIn(ExperimentalForeignApi::class)
actual fun compressToTempFile(
    source: FileSource,
    sourceSize: Long,
    tempFilePath: String
): Long {
    val fd = open(tempFilePath, O_WRONLY)
    if (fd < 0) {
        throw IllegalStateException("Cannot open temp file for writing: $tempFilePath")
    }

    return try {
        memScoped {
            val strm = alloc<z_stream>()

            val initResult = deflateInit2(
                strm.ptr,
                Z_DEFAULT_COMPRESSION,
                Z_DEFLATED,
                -15,
                8,
                Z_DEFAULT_STRATEGY
            )

            if (initResult != Z_OK) {
                throw IllegalStateException("deflateInit2 failed with code: $initResult")
            }

            try {
                val inputBuffer = ByteArray(65536)
                val outputBuffer = ByteArray(65536)
                var totalWritten = 0L
                var sourceOffset = 0L

                source.seek(0)

                while (sourceOffset < sourceSize) {
                    val toRead = minOf(inputBuffer.size.toLong(), sourceSize - sourceOffset).toInt()
                    val bytesRead = source.read(inputBuffer, 0, toRead)

                    if (bytesRead <= 0) break

                    inputBuffer.usePinned { inputPinned ->
                        strm.next_in = inputPinned.addressOf(0).reinterpret<UByteVar>()
                        strm.avail_in = bytesRead.toUInt()

                        var flush = Z_NO_FLUSH
                        if (sourceOffset + bytesRead >= sourceSize) {
                            flush = Z_FINISH
                        }

                        do {
                            outputBuffer.usePinned { outputPinned ->
                                strm.next_out = outputPinned.addressOf(0).reinterpret<UByteVar>()
                                strm.avail_out = outputBuffer.size.toUInt()

                                val result = deflate(strm.ptr, flush)

                                if (result != Z_OK && result != Z_STREAM_END) {
                                    throw IllegalStateException("deflate failed with code: $result")
                                }

                                val produced = outputBuffer.size - strm.avail_out.toInt()
                                if (produced > 0) {
                                    outputBuffer.usePinned { pinned ->
                                        write(fd, pinned.addressOf(0), produced.toULong())
                                    }
                                    totalWritten += produced
                                }
                            }
                        } while (strm.avail_out.toInt() == 0)
                    }

                    sourceOffset += bytesRead
                }

                totalWritten
            } finally {
                deflateEnd(strm.ptr)
            }
        }
    } finally {
        close(fd)
    }
}

/**
 * Копирование данных из временного файла в sink
 * @return количество скопированных байт
 */
@OptIn(ExperimentalForeignApi::class)
actual fun copyFromTempFile(tempFilePath: String, sink: BufferedSink, size: Long): Long {
    val fd = open(tempFilePath, O_RDONLY)
    if (fd < 0) {
        throw IllegalStateException("Cannot open temp file for reading: $tempFilePath")
    }

    try {
        val buffer = ByteArray(65536)
        var totalRead = 0L

        while (totalRead < size) {
            val toRead = minOf(buffer.size.toLong(), size - totalRead).toInt()
            val bytesRead = buffer.usePinned { pinned ->
                read(fd, pinned.addressOf(0), toRead.toULong()).toInt()
            }

            if (bytesRead <= 0) break

            sink.write(buffer.copyOf(bytesRead))
            totalRead += bytesRead
        }

        return totalRead
    } finally {
        close(fd)
    }
}

/**
 * POSIX FileSource реализация для Linux и macOS
 */
@OptIn(ExperimentalForeignApi::class)
actual class FileSource actual constructor(path: String) {
    private var fd: Int = -1
    private var fileSize: Long = 0

    init {
        fd = open(path, O_RDONLY)
        if (fd < 0) {
            throw IllegalStateException("Cannot open file: $path")
        }

        // Получаем размер файла
        memScoped {
            val statBuf = alloc<stat>()
            if (fstat(fd, statBuf.ptr) == 0) {
                fileSize = statBuf.st_size
            }
        }
    }

    actual fun seek(position: Long) {
        lseek(fd, position, SEEK_SET)
    }

    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return buffer.usePinned { pinned ->
            read(fd, pinned.addressOf(offset), length.toULong()).toInt()
        }
    }

    actual fun size(): Long = fileSize

    actual fun close() {
        if (fd >= 0) {
            close(fd)
            fd = -1
        }
    }
}

/**
 * Raw DEFLATE сжатие для ZIP (без zlib заголовка)
 * Использует windowBits = -15 для raw DEFLATE
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun compress(data: ByteArray): ByteArray {
    if (data.isEmpty()) return byteArrayOf()

    return memScoped {
        val strm = alloc<z_stream>()

        // Инициализация deflate с raw DEFLATE (без zlib заголовка)
        // windowBits = -15 означает raw DEFLATE
        val initResult = deflateInit2(
            strm.ptr,
            Z_DEFAULT_COMPRESSION,
            Z_DEFLATED,
            -15,  // Raw DEFLATE (без zlib заголовка и трейлера)
            8,    // memLevel
            Z_DEFAULT_STRATEGY
        )

        if (initResult != Z_OK) {
            throw IllegalStateException("deflateInit2 failed with code: $initResult")
        }

        try {
            // Выделяем буфер для сжатых данных
            // Максимальный размер сжатых данных с запасом
            val maxCompressedSize = (data.size * 1.1 + 12).toInt()
            val compressed = ByteArray(maxCompressedSize)

            data.usePinned { sourcePinned ->
                compressed.usePinned { destPinned ->
                    // Настраиваем входной буфер
                    strm.next_in = sourcePinned.addressOf(0).reinterpret<UByteVar>()
                    strm.avail_in = data.size.toUInt()

                    // Настраиваем выходной буфер
                    strm.next_out = destPinned.addressOf(0).reinterpret<UByteVar>()
                    strm.avail_out = maxCompressedSize.toUInt()

                    // Выполняем сжатие
                    val deflateResult = deflate(strm.ptr, Z_FINISH)

                    if (deflateResult != Z_STREAM_END) {
                        throw IllegalStateException("deflate failed with code: $deflateResult")
                    }

                    // Вычисляем размер сжатых данных
                    val compressedSize = maxCompressedSize - strm.avail_out.toInt()

                    // Возвращаем только сжатые данные
                    return compressed.copyOf(compressedSize)
                }
            }
        } finally {
            deflateEnd(strm.ptr)
        }
    }
}

/**
 * Raw DEFLATE сжатие чанка данных
 */
internal actual fun compress(buffer: ByteArray, length: Int): ByteArray {
    return compress(buffer.copyOf(length))
}

/**
 * Raw DEFLATE распаковка для ZIP (без zlib заголовка)
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun decompress(compressed: ByteArray, uncompressedSize: Int): ByteArray {
    if (compressed.isEmpty()) return byteArrayOf()

    return memScoped {
        val strm = alloc<z_stream>()

        // Инициализация inflate с raw DEFLATE (без zlib заголовка)
        // windowBits = -15 означает raw DEFLATE
        val initResult = inflateInit2(strm.ptr, -15)

        if (initResult != Z_OK) {
            throw IllegalStateException("inflateInit2 failed with code: $initResult")
        }

        try {
            val uncompressed = ByteArray(uncompressedSize)

            compressed.usePinned { sourcePinned ->
                uncompressed.usePinned { destPinned ->
                    // Настраиваем входной буфер
                    strm.next_in = sourcePinned.addressOf(0).reinterpret<UByteVar>()
                    strm.avail_in = compressed.size.toUInt()

                    // Настраиваем выходной буфер
                    strm.next_out = destPinned.addressOf(0).reinterpret<UByteVar>()
                    strm.avail_out = uncompressedSize.toUInt()

                    // Выполняем распаковку
                    val inflateResult = inflate(strm.ptr, Z_FINISH)

                    if (inflateResult != Z_STREAM_END && inflateResult != Z_OK) {
                        throw IllegalStateException("inflate failed with code: $inflateResult")
                    }
                }
            }

            return uncompressed
        } finally {
            inflateEnd(strm.ptr)
        }
    }
}
