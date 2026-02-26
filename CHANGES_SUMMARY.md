# Summary of Changes

## Проблема
Исходная реализация `addLargeFile` не поддерживала DEFLATE сжатие для больших файлов. При попытке использовать DEFLATE для файлов, которые не помещаются в память, возникала ошибка.

## Решение
Реализована двухпроходная обработка больших файлов с использованием временных файлов:

### 1. Добавлены expect/actual функции для работы с временными файлами:
- `createTempFile(prefix: String): String` - создание временного файла
- `deleteTempFile(path: String)` - удаление временного файла
- `compressToTempFile(source: FileSource, sourceSize: Long, tempFilePath: String): Long` - сжатие данных во временный файл
- `copyFromTempFile(tempFilePath: String, sink: Sink, size: Long): Long` - копирование данных из временного файла

### 2. Реализован метод `compressLargeFileToTemp` в `StreamingZipWriter`:
- Сжимает данные во временный файл для определения размера
- Автоматически выбирает метод сжатия (DEFLATE или STORE) в зависимости от эффективности
- Записывает заголовок с правильными размерами
- Копирует сжатые или несжатые данные в архив

### 3. Исправлены логические ошибки:
- Убрана некорректная проверка возвращаемого значения `compressLargeFileToTemp`
- Исправлено дублирование обновления `currentOffset`
- Добавлено правильное приведение типов (Long → Int) для `currentOffset`

### 4. Нативная реализация (POSIX):
- Использование `deflateInit2` с `windowBits = -15` для raw DEFLATE (без zlib заголовка)
- Потоковое сжатие чанками по 64KB
- Корректная обработка временных файлов через POSIX API

## Тестирование
1. Добавлены новые тесты для проверки работы с большими файлами
2. Протестирована работа CLI приложения с файлами размером 5MB+
3. Проверена совместимость с стандартными утилитами (unzip)
4. Все существующие тесты проходят успешно

## Результат
- ✅ Поддержка DEFLATE сжатия для файлов любого размера
- ✅ Автоматический выбор метода сжатия (DEFLATE/STORE)
- ✅ Потоковая обработка без загрузки всего файла в память
- ✅ Совместимость со стандартными ZIP утилитами
- ✅ Корректная работа на macOS ARM64, Linux x64, iOS

## Пример использования
```kotlin
val writer = StreamingZipWriter()
val fileSource = FileSource("large_file.dat")

writer.addLargeFile(
    buffer,
    "compressed.dat",
    fileSource,
    fileSize,
    compression = CompressionMethod.DEFLATE  // Автоматически использует временные файлы
)

writer.finish(buffer)
```