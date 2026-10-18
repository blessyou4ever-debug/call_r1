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

    // 파일명 끝에 오는 날짜+시간 패턴:
    // 1) 분리형: _20261019_143022, _261019_143022, _2026-10-19_14-30-22, _261019_1430
    private val TRAILING_SPLIT_DATETIME_REGEX = Regex(
        pattern = """[_\s-]+(\d{6}|\d{8}|\d{4}-\d{2}-\d{2})[_\s-]+(\d{4}|\d{6}|\d{2}-\d{2}-\d{2})$"""
    )

    // 2) T전화 등 통합형: _20261019143022 (14자리), _261019143022 (12자리), _202610191430 (12자리), _2610191430 (10자리)
    private val TRAILING_COMBINED_DATETIME_REGEX = Regex(
        pattern = """[_\s-]+(\d{14}|\d{12}|\d{10})$"""
    )

    // 전화번호 토큰 또는 문자열 내 전화번호 패턴 (일정 제목/메모/이름에서 완전 제거용)
    private val PHONE_TOKEN_REGEX = Regex(
        pattern = """^(?:\+?82[-_\s]?)?0?\d{1,2}[-_\s]?\d{3,4}[-_\s]?\d{4}$|^\d{7,13}$"""
    )
    private val INLINE_PHONE_CLEAN_REGEX = Regex(
        pattern = """(?:\+?82[-_\s]?)?0\d{1,2}[-_\s]?\d{3,4}[-_\s]?\d{4}|\b\d{8,13}\b"""
    )

    fun parse(file: File): CallMetadata {
        val baseName = file.nameWithoutExtension.trim()
        var working = baseName
        var parsedDateTime: LocalDateTime? = null

        // 1단계: 끝에 붙은 날짜·시간 추출 및 제거
        val splitMatch = TRAILING_SPLIT_DATETIME_REGEX.find(working)
        if (splitMatch != null) {
            val datePart = splitMatch.groupValues[1].replace("-", "")
            val timePart = splitMatch.groupValues[2].replace("-", "")
            parsedDateTime = parseDateTimeParts(datePart, timePart, file.lastModified())
            working = working.substring(0, splitMatch.range.first).trim()
        } else {
            val combinedMatch = TRAILING_COMBINED_DATETIME_REGEX.find(working)
            if (combinedMatch != null) {
                val dtRaw = combinedMatch.groupValues[1]
                parsedDateTime = parseCombinedDateTime(dtRaw, file.lastModified())
                working = working.substring(0, combinedMatch.range.first).trim()
            }
        }

        // 2단계: 앞쪽 불필요 접두어("통화 녹음", "통화녹음", "통화", "T전화", "에이닷", "녹음") 제거
        working = working
            .replace(Regex("""^(?:T전화|에이닷|통화\s*녹음|통화녹음|통화|녹음)[_\s-]+""", RegexOption.IGNORE_CASE), "")
            .trim('_', '-', ' ')

        // 3단계: 언더바(_) 또는 하이픈(-)으로 구분된 토큰 중 전화번호(010..., 02..., 숫자열)를 모두 제거하고 순수 이름만 추출
        val tokens = working.split(Regex("""[_]+""")).map { it.trim() }.filter { it.isNotBlank() }
        val nameTokens = tokens.filterNot { token ->
            PHONE_TOKEN_REGEX.matches(token)
        }.map { token ->
            // 혹시 "홍길동-01012345678" 처럼 하이픈으로 붙어있는 경우 전화번호 부분만 제거
            INLINE_PHONE_CLEAN_REGEX.replace(token, "").trim('_', '-', ' ', '(', ')')
        }.filter { it.isNotBlank() }

        val cleanName = nameTokens.joinToString(" ")
            .replace(Regex("""^(?:통화\s*녹음|통화녹음|통화|녹음)\s*"""), "")
            .trim('_', '-', ' ', '(', ')')
            .ifEmpty { "통화 상대" }

        val finalDateTime = parsedDateTime ?: LocalDateTime.ofInstant(
            Instant.ofEpochMilli(file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()),
            ZoneId.systemDefault()
        )

        // 전화번호 등 불필요한 개인/메타 정보가 일정에 등록되지 않도록 phoneNumber는 항상 빈 문자열로 유지
        return CallMetadata(
            filePath = file.absolutePath,
            contactName = cleanName,
            phoneNumber = "",
            callDateTime = finalDateTime
        )
    }

    /**
     * 일정 제목이나 설명에 전화번호가 섞여 들어가는 것을 방지하기 위한 정제 함수
     */
    fun stripPhoneNumbers(text: String): String {
        return INLINE_PHONE_CLEAN_REGEX.replace(text, "")
            .replace("()", "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun parseCombinedDateTime(dtRaw: String, fallbackMillis: Long): LocalDateTime {
        return try {
            when (dtRaw.length) {
                14 -> LocalDateTime.parse(dtRaw, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                12 -> {
                    if (dtRaw.startsWith("20")) {
                        LocalDateTime.parse(dtRaw + "00", DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    } else {
                        LocalDateTime.parse(dtRaw, DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                    }
                }
                10 -> LocalDateTime.parse(dtRaw + "00", DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                else -> throw IllegalArgumentException("Unsupported combined datetime: $dtRaw")
            }
        } catch (e: Exception) {
            LocalDateTime.ofInstant(
                Instant.ofEpochMilli(fallbackMillis.takeIf { it > 0L } ?: System.currentTimeMillis()),
                ZoneId.systemDefault()
            )
        }
    }

    private fun parseDateTimeParts(datePart: String, timePart: String, fallbackMillis: Long): LocalDateTime {
        return try {
            val normalizedDate = datePart.replace("-", "")
            val normalizedTime = when (timePart.length) {
                4 -> timePart + "00"
                6 -> timePart
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
