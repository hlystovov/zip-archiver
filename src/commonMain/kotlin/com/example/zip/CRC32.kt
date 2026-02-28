package com.example.zip

/**
 * CRC32 implementation for ZIP files
 * Based on the standard CRC32 polynomial: 0xEDB88320
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
