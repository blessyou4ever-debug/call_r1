package com.example.callscheduler.data.parser

import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CallMetadata(
    val filePath: String,
    val contactName: String,
    val phoneNumber: String,
    val callDateTime: LocalDateTime
)

object CallFilenameParser {
    // 1순위: 사용자 지정 형식 -> "통화 이름_날짜_통화종료시간.m4u" (예: "통화 김대리_261018_121018.m4u", "통화 홍길동_20261018_1210.m4u")
    private val USER_CALL_FORMAT_REGEX = Regex(
        pattern = """^(?:통화(?:\s*녹음)?[_\s-]+)?(.+?)[_\s-]+(\d{6}|\d{8}|\d{4}-\d{2}-\d{2})[_\s-]+(\d{4}|\d{6})\.([a-zA-Z0-9]+)$"""
    )

    // 2순위: 이름 + 전화번호 + 날짜 + 통화종료시간 형식 -> "통화 녹음_김대리_010-1234-5678_261018_121018.m4u"
    private val NAME_AND_PHONE_REGEX = Regex(
        pattern = """^(?:통화(?:\s*녹음)?)[_\s-]+(.+?)[_\s-]+(01[016789][-_]?\d{3,4}[-_]?\d{4})(?:[_\s-]+(\d{6}|\d{8}|\d{4}-\d{2}-\d{2})[_\s-]?(\d{4}|\d{6}))?\.([a-zA-Z0-9]+)$"""
    )

    private val PHONE_ONLY_REGEX = Regex("""^01[016789][-_]?\d{3,4}[-_]?\d{4}$""")

    fun parse(file: File): CallMetadata {
        val fileName = file.name

        // 1. 이름 + 전화번호가 모두 들어있는 경우 먼저 체크
        val phoneMatch = NAME_AND_PHONE_REGEX.find(fileName)
        if (phoneMatch != null) {
            val (rawName, rawPhone, datePart, timePart) = phoneMatch.destructured
            val parsedDateTime = parseDateTime(datePart, timePart, file.lastModified())
            return CallMetadata(
                filePath = file.absolutePath,
                contactName = rawName.trim(),
                phoneNumber = rawPhone.replace("_", "-"),
                callDateTime = parsedDateTime
            )
        }

        // 2. "통화 이름_날짜_통화종료시간.m4u" (또는 .m4a 등) 형식 체크
        val userMatch = USER_CALL_FORMAT_REGEX.find(fileName)
        if (userMatch != null) {
            val (rawNamePart, datePart, endTimePart) = userMatch.destructured
            val cleanedName = rawNamePart
                .removePrefix("통화 녹음")
                .removePrefix("통화녹음")
                .removePrefix("통화")
                .trim('_', '-', ' ')
                .ifEmpty { "알 수 없는 상대" }

            val isPhoneAsName = PHONE_ONLY_REGEX.matches(cleanedName)
            val parsedDateTime = parseDateTime(datePart, endTimePart, file.lastModified())

            return CallMetadata(
                filePath = file.absolutePath,
                contactName = cleanedName,
                phoneNumber = if (isPhoneAsName) cleanedName.replace("_", "-") else "",
                callDateTime = parsedDateTime
            )
        }

        // 3. 기타 파일명 폴백 처리
        val fallbackName = file.nameWithoutExtension
            .removePrefix("통화 녹음")
            .removePrefix("통화녹음")
            .removePrefix("통화")
            .trim('_', '-', ' ')
            .ifEmpty { "알 수 없는 상대" }

        return CallMetadata(
            filePath = file.absolutePath,
            contactName = fallbackName,
            phoneNumber = if (PHONE_ONLY_REGEX.matches(fallbackName)) fallbackName else "",
            callDateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()),
                ZoneId.systemDefault()
            )
        )
    }

    private fun parseDateTime(datePart: String, timePart: String, fallbackMillis: Long): LocalDateTime {
        return try {
            val normalizedDate = datePart.replace("-", "")
            val normalizedTime = when (timePart.length) {
                4 -> timePart + "00" // HHmm -> HHmm00
                6 -> timePart        // HHmmss
                else -> "120000"
            }
            when (normalizedDate.length) {
                6 -> LocalDateTime.parse(normalizedDate + normalizedTime, DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                8 -> LocalDateTime.parse(normalizedDate + normalizedTime, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                else -> throw IllegalArgumentException("Unsupported date format: $datePart")
            }
        } catch (e: Exception) {
            LocalDateTime.ofInstant(
                Instant.ofEpochMilli(fallbackMillis.takeIf { it > 0L } ?: System.currentTimeMillis()),
                ZoneId.systemDefault()
            )
        }
    }
}
