package com.example.zip

import kotlinx.cinterop.*
import platform.posix.*
import platform.zlib.*

/**
 * POSIX FileSource реализация для Linux и macOS
 */
@OptIn(ExperimentalForeignApi::class)
actual class FileSource actual constructor(private val path: String) {
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
                fileSize = statBuf.st_size.toLong()
            }
        }
    }

    actual fun seek(position: Long) {
        lseek(fd, position, SEEK_SET)
    }

    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return buffer.usePinned { pinned ->
            platform.posix.read(fd, pinned.addressOf(offset), length.toULong()).toInt()
        }
    }

    actual fun size(): Long = fileSize

    actual fun close() {
        if (fd >= 0) {
            platform.posix.close(fd)
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
