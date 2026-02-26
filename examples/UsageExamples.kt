package com.example.zip

import okio.*

/**
 * Примеры использования стримингового ZIP архиватора с Data Descriptor
 */
object ZipExamples {
    
    /**
     * Пример 1: Создание архива с файлами известного размера
     */
    fun exampleSimpleArchive() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        // Добавляем файлы с заранее известными данными
        writer.addFile(buffer, "readme.txt", "Hello, World!".encodeToByteArray())
        writer.addFile(buffer, "data.json", """{"key": "value"}""".encodeToByteArray())
        
        // Завершаем архив
        writer.finish(buffer)
        
        val zipBytes = buffer.readByteArray()
        println("Created archive with ${writer.getEntryCount()} files, size: ${zipBytes.size} bytes")
    }
    
    /**
     * Пример 2: Стриминговая запись (с Data Descriptor)
     * Полезно когда данные поступают потоком и размер неизвестен заранее
     */
    fun exampleStreamingArchive() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        // Создаем источник данных (в реальности это может быть сетевой поток, файл и т.д.)
        val file1Source = Buffer().apply {
            write("Large file content that comes from a stream...".encodeToByteArray())
        }
        
        // Стриминговая запись с Data Descriptor
        // Заголовок пишется сразу, размер и CRC вычисляются на лету
        writer.addFileStreaming(buffer, "streamed.txt", file1Source)
        
        // Можно добавить еще файл
        val file2Source = Buffer().apply {
            write("Another streaming file".encodeToByteArray())
        }
        writer.addFileStreaming(buffer, "another.txt", file2Source)
        
        writer.finish(buffer)
        
        println("Created streaming archive with ${writer.getEntryCount()} files")
    }
    
    /**
     * Пример 3: Сжатие файлов
     */
    fun exampleCompressedArchive() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        val data = "This is repetitive data that compresses well. ".repeat(100).encodeToByteArray()
        
        // Файл с сжатием Deflate
        writer.addFile(
            buffer, 
            "compressed.txt", 
            data,
            compression = CompressionMethod.DEFLATE
        )
        
        // Тот же файл без сжатия для сравнения
        writer.addFile(
            buffer,
            "uncompressed.txt",
            data,
            compression = CompressionMethod.STORE
        )
        
        writer.finish(buffer)
        
        val zipBytes = buffer.readByteArray()
        println("Compressed archive size: ${zipBytes.size} bytes (original: ${data.size * 2} bytes)")
    }
    
    /**
     * Пример 4: Смешанный архив
     * Комбинируем стриминговые и обычные файлы
     */
    fun exampleMixedArchive() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()
        
        // Маленький файл - добавляем обычным способом
        writer.addFile(buffer, "small.txt", "Small".encodeToByteArray())
        
        // Большой файл из потока - стриминговый способ
        val largeSource = Buffer().apply {
            repeat(1000) { write("Line $it\n".encodeToByteArray()) }
        }
        writer.addFileStreaming(buffer, "large.txt", largeSource)
        
        // Еще один маленький файл
        writer.addFile(buffer, "metadata.json", """{"version": "1.0"}""".encodeToByteArray())
        
        writer.finish(buffer)
        
        println("Mixed archive created with ${writer.getEntryCount()} files")
    }
    
    /**
     * Пример 5: Обработка больших файлов по частям
     */
    fun exampleChunkedProcessing() {
        val buffer = Buffer()
        val writer = StreamingZipWriter()

        // Создаем источник, который имитирует поступление данных чанками
        val chunkedSource = object : BufferedSource {
            private var chunkIndex = 0
            private val totalChunks = 5

            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                if (chunkIndex >= totalChunks) return -1

                val chunk = "Chunk ${chunkIndex + 1} of data\n".encodeToByteArray()
                sink.write(chunk)
                chunkIndex++
                return chunk.size.toLong()
            }

            override fun timeout(): okio.Timeout = okio.Timeout.NONE
            override fun close() {}
        }

        // Записываем данные по мере поступления
        writer.addFileStreaming(buffer, "chunked.txt", chunkedSource)
        writer.finish(buffer)

        println("Chunked file archived")
    }
}

/**
 * Запуск примеров
 */
fun main() {
    println("=== Example 1: Simple Archive ===")
    ZipExamples.exampleSimpleArchive()
    
    println("\n=== Example 2: Streaming Archive ===")
    ZipExamples.exampleStreamingArchive()
    
    println("\n=== Example 3: Compressed Archive ===")
    ZipExamples.exampleCompressedArchive()
    
    println("\n=== Example 4: Mixed Archive ===")
    ZipExamples.exampleMixedArchive()
    
    println("\n=== Example 5: Chunked Processing ===")
    ZipExamples.exampleChunkedProcessing()
    
    println("\nAll examples completed!")
}
