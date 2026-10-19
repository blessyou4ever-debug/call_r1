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
    fun decodeToPcmInfo(inputAudioFile: File, outputPcmFile: File): Result<DecodedPcmInfo> = runCatching {
        val extractor = MediaExtractor()
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

        require(audioTrackIndex >= 0 && format != null) { "No audio track found" }
        extractor.selectTrack(audioTrackIndex)

        val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(16000)
        val channelCount = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var isEOS = false

        FileOutputStream(outputPcmFile).use { fos ->
            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                while (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        outputBuffer.clear()
                        fos.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val bytesPerSecond = (sampleRate * channelCount * 2).coerceAtLeast(1)
        val durationSeconds = outputPcmFile.length().toFloat() / bytesPerSecond.toFloat()
        DecodedPcmInfo(outputPcmFile, sampleRate, channelCount, durationSeconds)
    }

    /**
     * .m4u / 특이 컨테이너 오디오가 Gemini API에서 MIME/포맷 오류(HTTP 400)를 일으킬 때
     * 모든 Gemini 모델이 100% 표준 지원하는 16-bit PCM WAV 바이트 배열(audio/wav)로 즉시 변환합니다.
     */
    fun transcodeToWavBytes(inputAudioFile: File): Result<ByteArray> = runCatching {
        val tempPcm = File.createTempFile("gemini_wav_", ".pcm")
        try {
            val decoded = decodeToPcmInfo(inputAudioFile, tempPcm).getOrThrow()
            val pcmBytes = decoded.pcmFile.readBytes()
            require(pcmBytes.isNotEmpty()) { "디코딩된 PCM 데이터가 비어 있습니다." }

            val channels = decoded.channelCount.coerceIn(1, 2)
            val sampleRate = decoded.sampleRate.coerceIn(8000, 48000)
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
                putInt(16) // Subchunk1Size for PCM
                putShort(1.toShort()) // AudioFormat 1 = PCM
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
     * 통화 중 침묵(Pause) 때문에 Android SpeechRecognizer가 첫 마디("어 남편 음")만 인식하고
     * 끊어버리는 현상을 막기 위해, 긴 무음 구간을 제거하고 8초 단위의 연속 청크 파일들로 분할합니다.
     */
    fun splitAndTrimSilenceIntoChunks(
        decoded: DecodedPcmInfo,
        cacheDir: File,
        chunkSeconds: Int = 8
    ): List<File> {
        val rawBytes = runCatching { decoded.pcmFile.readBytes() }.getOrNull() ?: return listOf(decoded.pcmFile)
        if (rawBytes.isEmpty()) return emptyList()

        val frameBytes = (decoded.channelCount * 2).coerceAtLeast(2)
        val windowFrames = (decoded.sampleRate / 20).coerceAtLeast(160)
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
            if (avgAmp < 180L) {
                consecutiveSilentWindows++
                if (consecutiveSilentWindows <= 4) {
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
        val bytesPerChunk = (decoded.sampleRate * decoded.channelCount * 2 * chunkSeconds).coerceAtLeast(32000)

        val chunkFiles = mutableListOf<File>()
        var chunkStart = 0
        var idx = 0
        while (chunkStart < compactBytes.size) {
            val end = (chunkStart + bytesPerChunk).coerceAtMost(compactBytes.size)
            val len = end - chunkStart
            if (len > windowBytes) {
                val chunkFile = File(cacheDir, "stt_chunk_${System.currentTimeMillis()}_${idx++}.pcm")
                FileOutputStream(chunkFile).use { it.write(compactBytes, chunkStart, len) }
                chunkFiles.add(chunkFile)
            }
            chunkStart = end
        }
        return chunkFiles.ifEmpty { listOf(decoded.pcmFile) }
    }

    fun decodeToPcmFile(inputAudioFile: File, outputPcmFile: File): Result<File> =
        decodeToPcmInfo(inputAudioFile, outputPcmFile).map { it.pcmFile }
}
