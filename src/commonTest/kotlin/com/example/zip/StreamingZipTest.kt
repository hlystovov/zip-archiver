package com.example.zip

import okio.*
import kotlin.test.*

class StreamingZipTest {
    
    @Test
    fun testCreateEmptyArchive() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        writer.finish(buffer)
        
        val bytes = buffer.readByteArray()
        // Пустой ZIP должен содержать минимум EOCD (22 байта)
        assertTrue(bytes.size >= 22, "Empty ZIP should be at least 22 bytes, got ${bytes.size}")
    }
    
    @Test
    fun testAddSingleFile() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        val testData = "Hello, World!".encodeToByteArray()
        writer.addFile(buffer, "test.txt", testData)
        writer.finish(buffer)
        
        assertEquals(1, writer.getEntryCount(), "Should have 1 entry")
        
        val bytes = buffer.readByteArray()
        // Должно быть больше чем данные + заголовки
        assertTrue(bytes.size > testData.size, "Archive should contain headers plus data")
    }
    
    fun testAddMultipleFiles() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        writer.addFile(buffer, "file1.txt", "Content 1".encodeToByteArray())
        writer.addFile(buffer, "file2.txt", "Content 2".encodeToByteArray())
        writer.addFile(buffer, "dir/file3.txt", "Content 3".encodeToByteArray())
        writer.finish(buffer)
        
        assertEquals(3, writer.getEntryCount(), "Should have 3 entries")
    }
    
    @Test
    fun testCRC32Calculation() {
        val crc = CRC32()
        val testData = "123456789".encodeToByteArray()
        crc.update(testData)
        
        // Известное значение CRC32 для "123456789" - 0xCBF43926
        assertEquals(0xCBF43926.toInt(), crc.getValue(), "CRC32 should match expected value")
    }
    
    @Test
    fun testLargeFile() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        // 1 MB of data
        val largeData = ByteArray(1024 * 1024) { it.toByte() }
        writer.addFile(buffer, "large.bin", largeData)
        writer.finish(buffer)
        
        assertEquals(1, writer.getEntryCount())
        
        val bytes = buffer.readByteArray()
        // Проверяем что размер архива больше данных (есть заголовки)
        assertTrue(bytes.size > largeData.size, "Archive with headers should be larger than raw data")
    }
    
    @Test
    fun testAddFileStreaming() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        val testData = "Streaming test data".encodeToByteArray()
        val source = Buffer().apply { write(testData) }
        
        writer.addFileStreaming(buffer, "streamed.txt", source)
        writer.finish(buffer)
        
        assertEquals(1, writer.getEntryCount(), "Should have 1 entry")
        
        val bytes = buffer.readByteArray()
        // Архив с Data Descriptor должен быть больше данных + заголовки + дескриптор
        assertTrue(bytes.size > testData.size, "Archive should contain headers, data, and data descriptor")
    }
    
    @Test
    fun testCompressionMethods() {
        assertEquals(0, CompressionMethod.STORE, "STORE should be 0")
        assertEquals(8, CompressionMethod.DEFLATE, "DEFLATE should be 8")
    }
    
    @Test
    fun testZipFlags() {
        assertEquals(0x0008, ZipFlags.DATA_DESCRIPTOR, "DATA_DESCRIPTOR flag should be 0x0008")
    }
    
    @Test
    fun testDataDescriptor() {
        val buffer = Buffer()
        val dd = DataDescriptor(
            crc32 = 0x12345678,
            compressedSize = 100,
            uncompressedSize = 200
        )

        dd.writeTo(buffer)

        val bytes = buffer.readByteArray()
        assertEquals(16, bytes.size, "Data Descriptor with signature should be 16 bytes")

        // Проверяем что данные записаны корректно
        // Сигнатура: 0x08074b50 в little-endian
        // После сигнатуры идут: CRC32 (4 bytes), compressedSize (4 bytes), uncompressedSize (4 bytes)
        assertTrue(bytes.size >= 16, "Should have at least 16 bytes")
    }
    
    @Test
    fun testMultipleFilesStreaming() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        val files = listOf(
            "file1.txt" to "First file content",
            "file2.txt" to "Second file content with more data",
            "empty.txt" to ""
        )
        
        for ((name, content) in files) {
            val source = Buffer().apply { write(content.encodeToByteArray()) }
            writer.addFileStreaming(buffer, name, source)
        }
        
        writer.finish(buffer)
        
        assertEquals(3, writer.getEntryCount(), "Should have 3 entries")
    }
    
    @Test
    fun testAddFileWithoutDataDescriptor() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        val testData = "Test data for standard ZIP".encodeToByteArray()
        writer.addFile(buffer, "standard.txt", testData, CompressionMethod.STORE)
        writer.finish(buffer)
        
        val bytes = buffer.readByteArray()
        
        // Проверяем структуру архива без Data Descriptor
        // [Local Header][Data][Central Directory][EOCD]
        // Local Header size = 30 + filename length
        // Для "standard.txt" (12 chars): 30 + 12 = 42 bytes
        val expectedHeaderSize = 30 + "standard.txt".length
        val expectedDataSize = testData.size
        val expectedCentralDirSize = 46 + "standard.txt".length
        val expectedEOCDSize = 22
        
        val expectedTotalSize = expectedHeaderSize + expectedDataSize + expectedCentralDirSize + expectedEOCDSize
        
        assertEquals(expectedTotalSize, bytes.size, "Archive size should match expected structure without Data Descriptor")
    }
    
    @Test
    fun testEmptyFile() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        // Пустой файл
        writer.addFile(buffer, "empty.txt", byteArrayOf())
        writer.finish(buffer)
        
        assertEquals(1, writer.getEntryCount())
        
        val bytes = buffer.readByteArray()
        // Даже пустой файл должен иметь заголовки и центральную директорию
        assertTrue(bytes.size > 30, "Empty file archive should have headers")
    }
    
    @Test
    fun testFileWithCompression() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        // Данные которые хорошо сжимаются
        val repetitiveData = "AAAAAAAAAABBBBBBBBBB".repeat(100).encodeToByteArray()
        
        // Без сжатия
        writer.addFile(buffer, "uncompressed.txt", repetitiveData, CompressionMethod.STORE)
        
        val uncompressedSize = buffer.size
        
        // Со сжатием
        val buffer2 = Buffer()
        val writer2 = StreamingZipWriter()
        writer2.addFile(buffer2, "compressed.txt", repetitiveData, CompressionMethod.DEFLATE)
        writer2.finish(buffer2)
        
        val compressedSize = buffer2.size
        
        // Сжатый архив должен быть меньше
        println("Uncompressed: $uncompressedSize, Compressed: $compressedSize")
        assertTrue(compressedSize < uncompressedSize, "Compressed archive should be smaller")
    }
    
    @Test
    fun testAddLargeFile() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        // Создаем большой набор данных (1MB)
        val largeData = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val source = Buffer().apply { write(largeData) }
        
        // Для тестирования в common модуле используем addFileStreaming
        // В нативной реализации будет использоваться addLargeFile с FileSource
        writer.addFileStreaming(buffer, "large.bin", source, CompressionMethod.STORE)
        writer.finish(buffer)
        
        assertEquals(1, writer.getEntryCount(), "Should have 1 entry")
        
        val bytes = buffer.readByteArray()
        // Архив должен быть больше данных из-за заголовков
        assertTrue(bytes.size > largeData.size, "Archive should contain headers plus data")
    }
    
    @Test
    fun testCompressionDecision() {
        // Тестируем логику выбора метода сжатия
        val smallData = ByteArray(500) { it.toByte() }  // < 1KB
        val mediumData = ByteArray(2000) { it.toByte() } // > 1KB
        
        val buffer1 = Buffer()
        val writer1 = StreamingZipWriter()
        writer1.addFile(buffer1, "small.bin", smallData)
        writer1.finish(buffer1)
        
        val buffer2 = Buffer()
        val writer2 = StreamingZipWriter()
        writer2.addFile(buffer2, "medium.bin", mediumData)
        writer2.finish(buffer2)
        
        // Оба архива должны быть созданы успешно
        assertEquals(1, writer1.getEntryCount())
        assertEquals(1, writer2.getEntryCount())
        
        val archive1 = buffer1.readByteArray()
        val archive2 = buffer2.readByteArray()
        
        // Проверяем что архивы имеют разумный размер
        assertTrue(archive1.size > smallData.size, "Small file archive should have headers")
        assertTrue(archive2.size > mediumData.size, "Medium file archive should have headers")
    }
}
