package com.example.callscheduler.data.parser

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class DecodedPcmInfo(
    val pcmFile: File,
    val sampleRate: Int,
    val channelCount: Int,
    val durationSeconds: Float
)

object AudioPcmDecoder {
    private const val TARGET_SAMPLE_RATE = 16000

    /**
     * 모든 통화 녹음(.m4u, .m4a, .amr, .mp3, .wav, .3gp 등)을 디코딩한 뒤
     * Android SpeechRecognizer 및 Gemini WAV 폴백에서 100% 호환되는
     * 표준 [16,000Hz · Mono(1채널) · 16-bit Little-Endian PCM]으로 즉시 리샘플링 및 볼륨 정규화합니다.
     * MediaExtractor 및 MediaCodec 전체 수명주기를 try/finally로 보호하여 손상된 파일에서도 네이티브 리소스 누수를 방지합니다.
     */
    fun decodeToPcmInfo(inputAudioFile: File, outputPcmFile: File): Result<DecodedPcmInfo> = runCatching {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val srcSampleRate: Int
        val srcChannels: Int
        val rawOut: ByteArrayOutputStream

        try {
            extractor.setDataSource(inputAudioFile.absolutePath)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            require(audioTrackIndex >= 0 && format != null) { "오디오 트랙을 찾을 수 없습니다." }
            extractor.selectTrack(audioTrackIndex)

            srcSampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(16000)
            srcChannels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1).coerceAtLeast(1)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val createdCodec = MediaCodec.createDecoderByType(mime)
            codec = createdCodec
            createdCodec.configure(format, null, null, 0)
            createdCodec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            rawOut = ByteArrayOutputStream((inputAudioFile.length().toInt() * 3).coerceIn(65536, 8 * 1024 * 1024))

            var isInputEOS = false
            var isOutputEOS = false
            var loopSafetyCounter = 0
            val maxLoops = 25_000 // 최대 약 25,000 프레임(충분히 긴 통화 디코딩 보장 및 무한루프 방지)

            while (!isOutputEOS && loopSafetyCounter++ < maxLoops) {
                if (!isInputEOS) {
                    val inIndex = createdCodec.dequeueInputBuffer(8_000)
                    if (inIndex >= 0) {
                        val inputBuffer = createdCodec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            val advanced = if (sampleSize > 0) extractor.advance() else false
                            if (sampleSize <= 0 || !advanced) {
                                createdCodec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            } else {
                                createdCodec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            }
                        }
                    }
                }

                var outIndex = createdCodec.dequeueOutputBuffer(bufferInfo, 8_000)
                while (outIndex >= 0) {
                    val outputBuffer = createdCodec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        outputBuffer.clear()
                        rawOut.write(chunk)
                    }
                    createdCodec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                        break
                    }
                    outIndex = createdCodec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        } finally {
            codec?.let { c ->
                runCatching { c.stop() }
                runCatching { c.release() }
            }
            runCatching { extractor.release() }
        }

        val normalized16kMono = convertAndNormalizeTo16kMono(
            rawPcm = rawOut.toByteArray(),
            srcSampleRate = srcSampleRate,
            srcChannels = srcChannels
        )

        FileOutputStream(outputPcmFile).use { fos ->
            fos.write(normalized16kMono)
        }

        val bytesPerSecond = TARGET_SAMPLE_RATE * 2
        val durationSeconds = normalized16kMono.size.toFloat() / bytesPerSecond.toFloat()
        DecodedPcmInfo(outputPcmFile, TARGET_SAMPLE_RATE, 1, durationSeconds)
    }

    private fun convertAndNormalizeTo16kMono(
        rawPcm: ByteArray,
        srcSampleRate: Int,
        srcChannels: Int
    ): ByteArray {
        if (rawPcm.size < 4) return rawPcm
        val frameBytes = (srcChannels * 2).coerceAtLeast(2)
        val totalSrcFrames = rawPcm.size / frameBytes
        if (totalSrcFrames <= 0) return rawPcm

        val monoSrc = ShortArray(totalSrcFrames)
        var maxPeak = 1
        for (f in 0 until totalSrcFrames) {
            var sum = 0
            val baseIdx = f * frameBytes
            for (c in 0 until srcChannels) {
                val idx = baseIdx + c * 2
                if (idx + 1 < rawPcm.size) {
                    val lo = rawPcm[idx].toInt() and 0xFF
                    val hi = rawPcm[idx + 1].toInt()
                    val sample = (hi shl 8) or lo
                    sum += sample
                }
            }
            val avg = (sum / srcChannels).coerceIn(-32768, 32767)
            monoSrc[f] = avg.toShort()
            val absVal = abs(avg)
            if (absVal > maxPeak) maxPeak = absVal
        }

        // 통화 상대방 목소리가 작아서 STT가 인식하지 못하는 현상을 막기 위한 자동 음량 증폭(Gain Normalization)
        val gain = if (maxPeak in 500..14000) {
            (22000.0 / maxPeak.toDouble()).coerceIn(1.0, 4.5)
        } else {
            1.0
        }

        val targetFrames = if (srcSampleRate == TARGET_SAMPLE_RATE) {
            totalSrcFrames
        } else {
            ((totalSrcFrames.toLong() * TARGET_SAMPLE_RATE) / srcSampleRate.coerceAtLeast(4000)).toInt().coerceAtLeast(1)
        }

        val outBytes = ByteArray(targetFrames * 2)
        val ratio = totalSrcFrames.toDouble() / targetFrames.toDouble()

        for (i in 0 until targetFrames) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, totalSrcFrames - 1)
            val idx1 = (idx0 + 1).coerceIn(0, totalSrcFrames - 1)
            val frac = srcPos - idx0
            val interpolated = monoSrc[idx0] * (1.0 - frac) + monoSrc[idx1] * frac
            val amplified = (interpolated * gain).toInt().coerceIn(-32767, 32767)
            outBytes[i * 2] = (amplified and 0xFF).toByte()
            outBytes[i * 2 + 1] = ((amplified shr 8) and 0xFF).toByte()
        }
        return outBytes
    }

    /**
     * .m4u / 특이 컨테이너 오디오가 Gemini API에서 MIME/포맷 오류(HTTP 400)를 일으킬 때
     * 모든 Gemini 모델이 100% 표준 지원하는 16kHz 16-bit Mono PCM WAV 바이트 배열(audio/wav)로 즉시 변환합니다.
     */
    fun transcodeToWavBytes(inputAudioFile: File): Result<ByteArray> = runCatching {
        val tempPcm = File.createTempFile("gemini_wav_", ".pcm")
        try {
            val decoded = decodeToPcmInfo(inputAudioFile, tempPcm).getOrThrow()
            val pcmBytes = decoded.pcmFile.readBytes()
            require(pcmBytes.isNotEmpty()) { "디코딩된 PCM 데이터가 비어 있습니다." }

            val channels = 1
            val sampleRate = TARGET_SAMPLE_RATE
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * (bitsPerSample / 8)
            val blockAlign = channels * (bitsPerSample / 8)
            val dataSize = pcmBytes.size
            val totalChunkSize = 36 + dataSize

            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalChunkSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1.toShort())
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }.array()

            val out = ByteArray(44 + dataSize)
            System.arraycopy(header, 0, out, 0, 44)
            System.arraycopy(pcmBytes, 0, out, 44, dataSize)
            out
        } finally {
            runCatching { tempPcm.delete() }
        }
    }

    /**
     * 긴 무음 구간을 제거하고 10초 단위의 연속 청크 파일들(최대 6개 = 60초 핵심 대화)로 분할하며,
     * 각 청크 끝에 0.5초의 무음 패딩을 붙여 Android SpeechRecognizer가 문장 끝을 1초 내에 즉시 확정하도록 만듭니다.
     */
    fun splitAndTrimSilenceIntoChunks(
        decoded: DecodedPcmInfo,
        cacheDir: File,
        chunkSeconds: Int = 10
    ): List<File> {
        val rawBytes = runCatching { decoded.pcmFile.readBytes() }.getOrNull() ?: return listOf(decoded.pcmFile)
        if (rawBytes.isEmpty()) return emptyList()

        val frameBytes = 2 // 16kHz Mono 16-bit
        val windowFrames = TARGET_SAMPLE_RATE / 20 // 50ms = 800 frames
        val windowBytes = windowFrames * frameBytes

        val activeAudioOut = ByteArrayOutputStream(rawBytes.size)
        var consecutiveSilentWindows = 0

        var offset = 0
        while (offset + windowBytes <= rawBytes.size) {
            var sumAbs = 0L
            var samples = 0
            var i = offset
            while (i + 1 < offset + windowBytes) {
                val lo = rawBytes[i].toInt() and 0xFF
                val hi = rawBytes[i + 1].toInt()
                val sample = (hi shl 8) or lo
                sumAbs += abs(sample)
                samples++
                i += frameBytes
            }
            val avgAmp = if (samples > 0) (sumAbs / samples) else 0L
            if (avgAmp < 220L) {
                consecutiveSilentWindows++
                if (consecutiveSilentWindows <= 5) {
                    activeAudioOut.write(rawBytes, offset, windowBytes)
                }
            } else {
                consecutiveSilentWindows = 0
                activeAudioOut.write(rawBytes, offset, windowBytes)
            }
            offset += windowBytes
        }
        if (offset < rawBytes.size) {
            activeAudioOut.write(rawBytes, offset, rawBytes.size - offset)
        }

        val compactBytes = activeAudioOut.toByteArray().takeIf { it.size >= windowBytes } ?: rawBytes
        val bytesPerChunk = (TARGET_SAMPLE_RATE * 2 * chunkSeconds).coerceAtLeast(64000)
        val trailingSilencePadding = ByteArray(TARGET_SAMPLE_RATE) // 0.5초 무음 패딩 (16,000 bytes)

        val chunkFiles = mutableListOf<File>()
        var chunkStart = 0
        var idx = 0
        while (chunkStart < compactBytes.size && idx < 6) {
            val end = (chunkStart + bytesPerChunk).coerceAtMost(compactBytes.size)
            val len = end - chunkStart
            if (len > windowBytes) {
                val chunkFile = File(cacheDir, "stt_chunk_${System.currentTimeMillis()}_${idx++}.pcm")
                FileOutputStream(chunkFile).use { fos ->
                    fos.write(compactBytes, chunkStart, len)
                    fos.write(trailingSilencePadding)
                }
                chunkFiles.add(chunkFile)
            }
            chunkStart = end
        }
        return chunkFiles.ifEmpty { listOf(decoded.pcmFile) }
    }

    fun decodeToPcmFile(inputAudioFile: File, outputPcmFile: File): Result<File> =
        decodeToPcmInfo(inputAudioFile, outputPcmFile).map { it.pcmFile }
}
