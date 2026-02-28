package ru.hlystovov.zip

import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZipTest {
    private val tempDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve("zip-test-${Random.nextInt()}")

    @BeforeTest
    fun setUp() {
        FileSystem.SYSTEM.createDirectory(tempDir, true)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(tempDir)
    }

    private fun createTempFile(name: String, content: String): Path {
        val path = tempDir.resolve(name)
        FileSystem.SYSTEM.write(path) {
            writeUtf8(content)
        }
        return path
    }

    private fun createTempFile(name: String, content: ByteArray): Path {
        val path = tempDir.resolve(name)
        FileSystem.SYSTEM.write(path) {
            write(content)
        }
        return path
    }

    @Test
    fun testCreateEmptyArchive() {
        val buffer = Buffer()
        val writer = ZipWriter()
        writer.finish(buffer)

        val bytes = buffer.readByteArray()
        // Пустой ZIP должен содержать минимум EOCD (22 байта)
        assertTrue(bytes.size >= 22, "Empty ZIP should be at least 22 bytes, got ${bytes.size}")
    }

    @Test
    fun testAddSingleFile() {
        val buffer = Buffer()
        val writer = ZipWriter()

        val testData = "Hello, World!"
        val testFile = createTempFile("test.txt", testData)
        writer.addFile(buffer, testFile)
        writer.finish(buffer)

        assertEquals(1, writer.getEntryCount(), "Should have 1 entry")

        val bytes = buffer.readByteArray()
        // Должно быть больше чем данные + заголовки
        assertTrue(bytes.size > testData.length, "Archive should contain headers plus data")
    }

    @Test
    fun testAddMultipleFiles() {
        val buffer = Buffer()
        val writer = ZipWriter()

        val file1 = createTempFile("file1.txt", "Content 1")
        val file2 = createTempFile("file2.txt", "Content 2")
        FileSystem.SYSTEM.createDirectory(tempDir.resolve("dir"))
        val file3 = createTempFile("dir/file3.txt", "Content 3")

        writer.addFile(buffer, file1)
        writer.addFile(buffer, file2)
        writer.addFile(buffer, file3)
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
    fun testCompressionMethods() {
        assertEquals(0, CompressionMethod.STORE, "STORE should be 0")
        assertEquals(8, CompressionMethod.DEFLATE, "DEFLATE should be 8")
    }

    @Test
    fun testZipFlags() {
        assertEquals(0x0008, ZipFlags.DATA_DESCRIPTOR, "DATA_DESCRIPTOR flag should be 0x0008")
    }

    @Test
    fun testAddFileWithoutDataDescriptor() {
        val buffer = Buffer()
        val writer = ZipWriter()

        val testData = "Test data for standard ZIP"
        val testFile = createTempFile("standard.txt", testData)
        writer.addFile(buffer, testFile, CompressionMethod.STORE)
        writer.finish(buffer)

        val bytes = buffer.readByteArray()

        // Проверяем структуру архива без Data Descriptor
        // [Local Header][Data][Central Directory][EOCD]
        // Local Header size = 30 + filename length
        // Для "standard.txt" (12 chars): 30 + 12 = 42 bytes
        val expectedHeaderSize = 30 + "standard.txt".length
        val expectedDataSize = testData.length
        val expectedCentralDirSize = 46 + "standard.txt".length
        val expectedEOCDSize = 22

        val expectedTotalSize = expectedHeaderSize + expectedDataSize + expectedCentralDirSize + expectedEOCDSize

        assertEquals(
            expectedTotalSize,
            bytes.size,
            "Archive size should match expected structure without Data Descriptor"
        )
    }

    @Test
    fun testEmptyFile() {
        val buffer = Buffer()
        val writer = ZipWriter()

        // Пустой файл
        val emptyFile = createTempFile("empty.txt", "")
        writer.addFile(buffer, emptyFile)
        writer.finish(buffer)

        assertEquals(1, writer.getEntryCount())

        val bytes = buffer.readByteArray()
        // Даже пустой файл должен иметь заголовки и центральную директорию
        assertTrue(bytes.size > 30, "Empty file archive should have headers")
    }

    @Test
    fun testFileWithCompression() {
        // Данные которые хорошо сжимаются
        val repetitiveData = "AAAAAAAAAABBBBBBBBBB".repeat(100)

        val buffer = Buffer()
        val writer = ZipWriter()

        // Без сжатия
        val uncompressedFile = createTempFile("uncompressed.txt", repetitiveData)
        writer.addFile(buffer, uncompressedFile, CompressionMethod.STORE)

        val uncompressedSize = buffer.size

        // Со сжатием
        val buffer2 = Buffer()
        val writer2 = ZipWriter()
        val compressedFile = createTempFile("compressed.txt", repetitiveData)
        writer2.addFile(buffer2, compressedFile, CompressionMethod.DEFLATE)
        writer2.finish(buffer2)

        val compressedSize = buffer2.size

        // Сжатый архив должен быть меньше
        println("Uncompressed: $uncompressedSize, Compressed: $compressedSize")
        assertTrue(compressedSize < uncompressedSize, "Compressed archive should be smaller")
    }

    @Test
    fun testCompressionDecision() {
        // Тестируем логику выбора метода сжатия
        val smallData = ByteArray(500) { it.toByte() }  // < 1KB
        val mediumData = ByteArray(2000) { it.toByte() } // > 1KB

        val smallFile = createTempFile("small.bin", smallData)
        val mediumFile = createTempFile("medium.bin", mediumData)

        val buffer1 = Buffer()
        val writer1 = ZipWriter()
        writer1.addFile(buffer1, smallFile)
        writer1.finish(buffer1)

        val buffer2 = Buffer()
        val writer2 = ZipWriter()
        writer2.addFile(buffer2, mediumFile)
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
