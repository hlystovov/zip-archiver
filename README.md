# Streaming ZIP Archiver

Стриминговый архиватор ZIP на Kotlin Native с поддержкой iOS, Linux и macOS ARM64.

## Особенности

- **Raw DEFLATE сжатие**: Настоящее DEFLATE без zlib заголовка, совместимое с ZIP
- **Стриминговая запись с Data Descriptor**: Создание ZIP архивов без предварительного знания размеров файлов
- **Кроссплатформенность**: Поддержка Linux x64, macOS ARM64, iOS ARM64/Simulator
- **Автоматический выбор метода сжатия**: Файлы > 1KB сжимаются с помощью DEFLATE, меньшие - STORE
- **Совместимость с macOS Archive Utility**: Не использует Data Descriptor в стандартном режиме

## Что такое Raw DEFLATE?

Стандартная функция `compress()` из zlib добавляет **zlib wrapper** (2 байта заголовка + 4 байта Adler32 чексуммы), что делает сжатые данные несовместимыми с ZIP форматом.

**Решение**: Используем `deflateInit2()` с `windowBits = -15`, что создает **raw DEFLATE** без заголовков:

```kotlin
// Стандартный zlib (несовместим с ZIP)
compress(data) // Добавляет zlib header + adler32

// Raw DEFLATE (совместим с ZIP)
deflateInit2(strm, Z_DEFAULT_COMPRESSION, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY)
```

## Структура проекта

```
zip-streaming-archiver/
├── build.gradle.kts          # Конфигурация Kotlin Native
├── settings.gradle.kts
├── gradle.properties
├── README.md
├── examples/
│   └── UsageExamples.kt      # Примеры использования
└── src/
    ├── commonMain/kotlin/com/example/zip/
    │   └── StreamingZip.kt    # Общий код архиватора
    ├── commonTest/kotlin/com/example/zip/
    │   └── StreamingZipTest.kt # Тесты
    └── nativeMain/kotlin/com/example/zip/
        ├── FileSource.kt       # POSIX реализация + raw DEFLATE
        └── Main.kt             # CLI приложение
```

## Сборка

### Требования

- JDK 17+
- Gradle 8.x (через wrapper или системный)
- Для iOS: Xcode 14+

### Сборка всех таргетов

```bash
./gradlew build
```

### macOS ARM64

```bash
./gradlew compileKotlinMacosArm64
./gradlew allTests  # Запуск тестов на macOS
```

### Linux x64

```bash
./gradlew compileKotlinLinuxX64
```

### iOS Framework

```bash
./gradlew compileKotlinIosArm64      # Устройство
./gradlew compileKotlinIosSimulatorArm64  # Simulator
```

## Использование

### CLI приложение

```bash
# Создание архива с автоматическим сжатием
./zip-archiver.kexe create archive.zip file1.txt file2.txt largefile.db
# Вывод:
#   Adding: file1.txt
#   Adding: file2.txt
#   Adding: largefile.db
#     Compressed with DEFLATE
# Archive created: 3 files
# Compatible with macOS Archive Utility

# Стриминговое создание (с Data Descriptor)
./zip-archiver.kexe create-streaming archive.zip large-file.dat

# Список файлов
./zip-archiver.kexe list archive.zip

# Извлечение файла
./zip-archiver.kexe extract archive.zip file.txt output.txt
```

### Как библиотека

```kotlin
import com.example.zip.*
import kotlinx.io.*

val buffer = Buffer()
val writer = StreamingZipWriter()

// Добавляем файл с автоматическим выбором сжатия
val data = "Hello, World!".encodeToByteArray()
writer.addFile(buffer, "readme.txt", data)

// Добавляем большой файл с принудительным сжатием
val largeData = ByteArray(100000) { it.toByte() }
writer.addFile(buffer, "data.bin", largeData, CompressionMethod.DEFLATE)

// Завершаем архив
writer.finish(buffer)
val zipBytes = buffer.readByteArray()
```

## Сжатие

### Автоматический выбор метода

```kotlin
// Файлы <= 1KB: STORE (быстрее, нет накладных расходов)
// Файлы > 1KB: DEFLATE (если выгодно)

val compression = if (content.size > 1024) {
    CompressionMethod.DEFLATE
} else {
    CompressionMethod.STORE
}
```

### Результаты сжатия

| Тип файла | Исходный размер | Сжатый размер | Эффективность |
|-----------|----------------|---------------|---------------|
| Текстовый лог | 15 KB | 236 байт | 98.5% |
| База данных | 10 KB | ~3 KB | 70% |
| Маленький файл (<1KB) | 70 байт | 70 байт | 0% (STORE) |

### Поддержка больших файлов с DEFLATE

Для файлов, которые не помещаются в память, используется двухпроходный подход с временными файлами:

```kotlin
// Первый проход: сжатие во временный файл для определения размера
val compressedSize = compressToTempFile(fileSource, fileSize, tempFile)

// Второй проход: запись заголовка и копирование сжатых данных
val localHeader = LocalFileHeader(
    name = name,
    compression = if (compressedSize < fileSize) DEFLATE else STORE,
    compressedSize = compressedSize.toInt(),
    uncompressedSize = fileSize.toInt()
)
localHeader.writeTo(sink)
copyFromTempFile(tempFile, sink, compressedSize)
```

**Особенности:**
- Автоматический выбор метода: если сжатие неэффективно (размер увеличивается), используется STORE
- Потоковая обработка: данные читаются и сжимаются чанками без загрузки всего файла в память
- Временные файлы: автоматически создаются и удаляются после использования

## Архитектура

### Raw DEFLATE реализация

```kotlin
// Инициализация с raw DEFLATE (windowBits = -15)
deflateInit2(
    strm.ptr,
    Z_DEFAULT_COMPRESSION,
    Z_DEFLATED,
    -15,  // Raw DEFLATE: без zlib заголовка
    8,    // memLevel
    Z_DEFAULT_STRATEGY
)
```

### Структура ZIP файла

```
[Local File Header 1]     <- версия, флаги=0, размеры, CRC
[File Data 1]             <- сжатые или несжатые данные
[Local File Header 2]
[File Data 2]
...
[Central Directory Header 1]
[Central Directory Header 2]
...
[End of Central Directory Record]
```

### Потоковая обработка

```
Источник данных (Source)
        ↓
    [Чанк 1] ────→ CRC32.update()
        ↓
    [Чанк 2] ────→ CRC32.update()
        ↓
    [Чанк N] ────→ CRC32.update()
        ↓
    [Raw DEFLATE] ────→ compress()
        ↓
    [Запись в архив]
```

## API

### StreamingZipWriter

```kotlin
class StreamingZipWriter {
    // Буферизованная запись с автоматическим сжатием
    fun addFile(
        sink: Sink,
        name: String,
        data: ByteArray,
        compression: Short = CompressionMethod.STORE
    )
    
    // Двухпроходная запись для больших файлов
    fun addLargeFile(
        sink: Sink,
        name: String,
        fileSource: FileSource,
        fileSize: Long,
        compression: Short = CompressionMethod.STORE
    )
    
    // Стриминговая запись с Data Descriptor
    fun addFileStreaming(
        sink: Sink,
        name: String,
        source: Source,
        compression: Short = CompressionMethod.STORE
    )
    
    // Завершить архив
    fun finish(sink: Sink)
}
```

### Compression Methods

```kotlin
object CompressionMethod {
    const val STORE: Short = 0      // Без сжатия
    const val DEFLATE: Short = 8    // Raw DEFLATE
}
```

## Тестирование

```bash
# Запуск всех тестов
./gradlew allTests

# Тест на macOS ARM64
./gradlew macosArm64Test

# Тест на iOS Simulator
./gradlew iosSimulatorArm64Test
```

## Ограничения

- Максимальный размер файла: 4GB (ZIP32)
- Для файлов > 4GB требуется ZIP64 (не реализовано)
- Не поддерживает шифрование

## Roadmap

- [x] Raw DEFLATE сжатие (без zlib wrapper)
- [x] Автоматический выбор метода сжатия
- [x] Двухпроходная обработка больших файлов
- [x] Поддержка DEFLATE для больших файлов через временные файлы
- [ ] ZIP64 поддержка для файлов > 4GB
- [ ] Асинхронное сжатие
- [ ] Параллельное сжатие нескольких файлов

## Лицензия

MIT
