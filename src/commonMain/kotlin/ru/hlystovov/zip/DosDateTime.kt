package ru.hlystovov.zip

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Конвертация времени в DOS формат для ZIP
 * DOS время: биты 0-4 (секунды/2), биты 5-10 (минуты), биты 11-15 (часы)
 * DOS дата: биты 0-4 (день), биты 5-8 (месяц), биты 9-15 (год-1980)
 */
object DosDateTime {
    fun fromMillis(millis: Long): Pair<Short, Short> {
        val instant = if (millis > 0) {
            Instant.fromEpochMilliseconds(millis)
        } else {
            Clock.System.now()
        }
        val dateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())

        val dosTime = (((dateTime.hour and 0x1F) shl 11) or
                      ((dateTime.minute and 0x3F) shl 5) or
                      ((dateTime.second / 2) and 0x1F)).toShort()

        val year = dateTime.year - 1980
        val dosDate = (((year and 0x7F) shl 9) or
                      ((dateTime.monthNumber and 0xF) shl 5) or
                      (dateTime.dayOfMonth and 0x1F)).toShort()

        return Pair(dosTime, dosDate)
    }

    fun fromMillis(millis: Long, defaultTime: Short, defaultDate: Short): Pair<Short, Short> {
        if (millis <= 0) return Pair(defaultTime, defaultDate)
        return fromMillis(millis)
    }
}
