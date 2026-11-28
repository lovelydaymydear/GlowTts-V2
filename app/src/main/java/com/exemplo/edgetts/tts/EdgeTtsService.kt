package com.exemplo.edgetts.tts

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.exemplo.edgetts.native.EdgeTtsNative
import com.exemplo.edgetts.protocol.EdgeTtsClient
import com.exemplo.edgetts.protocol.EdgeVoice
import org.json.JSONArray
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class EdgeTtsService : TextToSpeechService() {

    companion object {
        private const val PREFS_NAME = "edge_tts_prefs"
        private const val KEY_VOICE = "selected_voice"
        private const val KEY_VOICE_LIST = "selected_voices_list"
        private const val KEY_SINGLE_VOICE_MODE = "single_voice_mode"
        private const val KEY_DIALOGUE_VOICE_ENABLED = "dialogue_voice_enabled"
        private const val KEY_DIALOGUE_VOICE = "dialogue_voice"
        private const val KEY_RATE_PERCENT = "rate_percent"
        private const val KEY_PITCH_HZ = "pitch_hz"
        private const val KEY_LAST_DIAG = "last_diag_message"
        private const val KEY_CACHED_VOICES_JSON = "cached_voices_json"
        private const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
        private const val DEFAULT_DIALOGUE_VOICE = "pt-BR-AntonioNeural"
        private const val SAMPLE_RATE_HZ = 48000

        private const val MIN_CHUNK_CHARS_BEFORE_SPLIT = 140
        private const val TARGET_MAX_CHUNK_CHARS = 280
    }

    private data class SpeechSegment(
        val text: String,
        val isDialogue: Boolean
    )

    private val stopRequested = AtomicBoolean(false)
    private val prefetchExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var activeRequestId: String? = null

    @Volatile
    private var prefetchedRequestId: String? = null

    @Volatile
    private var prefetchFuture: Future<*>? = null

    private var currentLanguage = arrayOf("por", "BRA", "")

    override fun onGetLanguage(): Array<String> = currentLanguage

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val normalizedLang = (lang ?: "").lowercase(Locale.US)
        return when {
            normalizedLang.startsWith("por") || normalizedLang.startsWith("pt") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("eng") || normalizedLang.startsWith("en") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("spa") || normalizedLang.startsWith("es") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("fra") || normalizedLang.startsWith("fr") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("deu") || normalizedLang.startsWith("de") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("ita") || normalizedLang.startsWith("it") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("jpn") || normalizedLang.startsWith("ja") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.startsWith("zho") || normalizedLang.startsWith("zh") -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            normalizedLang.isNotBlank() -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability != TextToSpeech.LANG_NOT_SUPPORTED) {
            currentLanguage = arrayOf(lang ?: "por", country ?: "BRA", variant ?: "")
        }
        return availability
    }

    private fun getAvailableEdgeVoices(context: Context): List<EdgeVoice> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedJson = prefs.getString(KEY_CACHED_VOICES_JSON, null)
        return parseCachedVoices(cachedJson).ifEmpty {
            EdgeTtsClient.DEFAULT_FALLBACK_VOICES
        }
    }

    private fun getPersistedVoice(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_VOICE, null)?.trim()
        return if (!saved.isNullOrEmpty()) saved else DEFAULT_VOICE
    }

    override fun onGetVoices(): List<Voice> {
        val sourceList = getAvailableEdgeVoices(this)

        return sourceList.map { v ->
            val parts = v.locale.split("-")
            val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale("pt", "BR")
            Voice(v.shortName, locale, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, true, emptySet())
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName.isNullOrBlank()) return TextToSpeech.ERROR
        val matched = findMatchingVoiceShortName(voiceName, getAvailableEdgeVoices(this))
        return if (matched != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName.isNullOrBlank()) return TextToSpeech.ERROR
        val voices = getAvailableEdgeVoices(this)
        val matched = voices.firstOrNull {
            it.shortName.equals(voiceName.trim(), ignoreCase = true)
        } ?: voices.firstOrNull {
            it.shortName.contains(voiceName.trim(), ignoreCase = true) ||
                voiceName.trim().contains(it.shortName, ignoreCase = true)
        } ?: return TextToSpeech.ERROR

        val parts = matched.locale.split("-")
        val lang = parts.getOrNull(0).orEmpty()
        val country = parts.getOrNull(1).orEmpty()
        currentLanguage = arrayOf(lang, country, matched.shortName)
        return TextToSpeech.SUCCESS
    }

    private fun findMatchingVoiceShortName(voiceName: String?, voices: List<EdgeVoice>): String? {
        if (voiceName.isNullOrBlank()) return null
        val target = voiceName.trim()

        voices.firstOrNull { it.shortName.equals(target, ignoreCase = true) }?.let {
            return it.shortName
        }

        voices.firstOrNull {
            it.shortName.contains(target, ignoreCase = true) ||
                target.contains(it.shortName, ignoreCase = true)
        }?.let {
            return it.shortName
        }

        return null
    }

    private fun parseCachedVoices(rawJson: String?): List<EdgeVoice> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val list = ArrayList<EdgeVoice>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val shortName = obj.optString("shortName").trim()
                if (shortName.isEmpty()) continue
                val friendlyName = obj.optString("friendlyName").ifBlank { shortName }.trim()
                val locale = obj.optString("locale").trim()
                val gender = obj.optString("gender").trim()
                list.add(EdgeVoice(shortName, friendlyName, locale, gender))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onStop() {
        stopRequested.set(true)
        cancelPrefetch()
        activeRequestId?.let { EdgeTtsNative.nativeStop(it) }
    }

    override fun onDestroy() {
        stopRequested.set(true)
        cancelPrefetch()
        activeRequestId?.let { EdgeTtsNative.nativeStop(it) }
        prefetchExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return

        stopRequested.set(false)
        cancelPrefetch()

        val text = request.charSequenceText?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            saveDiag("Texto vazio")
            callback.done()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val configuredVoice = getPersistedVoice(this)
        val voices = getAvailableEdgeVoices(this)
        val primaryVoice = resolveVoiceForRequest(request, configuredVoice, voices)

        val dialogueVoiceEnabled = prefs.getBoolean(KEY_DIALOGUE_VOICE_ENABLED, false)
        val dialogueVoice = prefs.getString(KEY_DIALOGUE_VOICE, DEFAULT_DIALOGUE_VOICE)?.trim().orEmpty()
            .ifEmpty { DEFAULT_DIALOGUE_VOICE }

        val segments = if (dialogueVoiceEnabled && dialogueVoice.isNotBlank()) {
            buildDialogueAwareSegments(text)
        } else {
            buildBalancedChunks(text).map { SpeechSegment(it, isDialogue = false) }
        }

        if (segments.isEmpty()) {
            saveDiag("Texto vazio")
            callback.done()
            return
        }

        val savedList = prefs.getString(KEY_VOICE_LIST, primaryVoice).orEmpty()
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val singleVoiceMode = prefs.getBoolean(KEY_SINGLE_VOICE_MODE, savedList.size <= 1)

        val voiceRotation = if (singleVoiceMode || savedList.size <= 1) {
            listOf(primaryVoice)
        } else {
            savedList
        }

        val savedRatePercent = prefs.getInt(KEY_RATE_PERCENT, 0).coerceIn(-50, 100)
        val ratePercent = if (request.speechRate > 0 && request.speechRate != 100) {
            (request.speechRate - 100).coerceIn(-50, 100)
        } else {
            savedRatePercent
        }

        val savedPitchHz = prefs.getInt(KEY_PITCH_HZ, 0).coerceIn(-50, 50)
        val pitchHz = if (request.pitch > 0 && request.pitch != 100) {
            (request.pitch - 100).coerceIn(-50, 50)
        } else {
            savedPitchHz
        }

        val rateStr = if (ratePercent >= 0) "+${ratePercent}%" else "${ratePercent}%"
        val pitchStr = if (pitchHz >= 0) "+${pitchHz}Hz" else "${pitchHz}Hz"
        val volumeStr = "+0%"

        var callbackStarted = false
        var totalBytesAll = 0
        var narrationIndex = 0

        fun resolveSegmentVoice(seg: SpeechSegment): String {
            if (seg.isDialogue && dialogueVoiceEnabled && dialogueVoice.isNotBlank()) {
                return dialogueVoice
            }
            val voice = if (singleVoiceMode || voiceRotation.size <= 1) {
                primaryVoice
            } else {
                voiceRotation[narrationIndex % voiceRotation.size]
            }
            narrationIndex++
            return voice
        }

        var currentVoice = resolveSegmentVoice(segments[0])
        var currentReqId = UUID.randomUUID().toString()
        activeRequestId = currentReqId

        val startErr = EdgeTtsNative.nativeBeginSynthesis(
            requestId = currentReqId,
            text = segments[0].text,
            voice = currentVoice,
            rate = rateStr,
            volume = volumeStr,
            pitch = pitchStr
        )
        if (!startErr.isNullOrBlank()) {
            saveDiag("Erro início: $startErr")
            callback.error()
            return
        }

        try {
            for (index in segments.indices) {
                if (stopRequested.get()) break

                val reqId = currentReqId
                val activeVoiceForChunk = currentVoice
                activeRequestId = reqId
                saveDiag("A sintetizar (${index + 1}/${segments.size}, $activeVoiceForChunk)...")

                if (index + 1 < segments.size) {
                    val nextSeg = segments[index + 1]
                    val nextVoice = resolveSegmentVoice(nextSeg)
                    val nextReqId = UUID.randomUUID().toString()
                    prefetchedRequestId = nextReqId
                    prefetchFuture = prefetchExecutor.submit {
                        if (!stopRequested.get()) {
                            EdgeTtsNative.nativeBeginSynthesis(
                                requestId = nextReqId,
                                text = nextSeg.text,
                                voice = nextVoice,
                                rate = rateStr,
                                volume = volumeStr,
                                pitch = pitchStr
                            )
                        }
                    }
                    currentVoice = nextVoice
                    currentReqId = nextReqId
                } else {
                    prefetchedRequestId = null
                    prefetchFuture = null
                }

                if (!callbackStarted) {
                    callback.start(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
                    callbackStarted = true
                }

                val chunkMax = callback.maxBufferSize.coerceAtLeast(8192)
                var segmentBytes = 0

                while (!stopRequested.get()) {
                    val pcmChunk = EdgeTtsNative.nativeReadPcmChunk(reqId, chunkMax) ?: break
                    if (pcmChunk.isEmpty()) continue
                    segmentBytes += pcmChunk.size
                    totalBytesAll += pcmChunk.size
                    val status = callback.audioAvailable(pcmChunk, 0, pcmChunk.size)
                    if (status != TextToSpeech.SUCCESS) {
                        stopRequested.set(true)
                        break
                    }
                }

                EdgeTtsNative.nativeStop(reqId)

                val nativeErr = EdgeTtsNative.nativeGetLastError(reqId)
                if (!nativeErr.isNullOrBlank() && segmentBytes == 0 && totalBytesAll == 0) {
                    cancelPrefetch()
                    saveDiag("Erro: $nativeErr")
                    callback.error()
                    return
                }
            }

            if (!callbackStarted) {
                callback.start(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
            }
            saveDiag("OK ($totalBytesAll bytes PCM)")
            callback.done()
        } catch (e: Exception) {
            cancelPrefetch()
            saveDiag("Erro: ${e.message ?: e.javaClass.simpleName}")
            try {
                callback.error()
            } catch (_: Exception) {
            }
        } finally {
            cancelPrefetch()
            activeRequestId?.let { EdgeTtsNative.nativeStop(it) }
            activeRequestId = null
        }
    }

    private fun resolveVoiceForRequest(
        request: SynthesisRequest,
        configuredVoice: String,
        availableVoices: List<EdgeVoice>
    ): String {
        val matchedRequestedVoice = findMatchingVoiceShortName(request.voiceName, availableVoices)
        if (matchedRequestedVoice != null) {
            return matchedRequestedVoice
        }

        if (configuredVoice.isNotBlank()) {
            return configuredVoice
        }

        val lang = (request.language ?: "").lowercase(Locale.US)
        val country = (request.country ?: "").uppercase(Locale.US)
        return when {
            lang.startsWith("por") || lang.startsWith("pt") ->
                if (country == "PRT" || country == "PT") "pt-PT-RaquelNeural" else "pt-BR-FranciscaNeural"
            lang.startsWith("eng") || lang.startsWith("en") ->
                if (country == "GBR" || country == "GB") "en-GB-SoniaNeural" else "en-US-JennyNeural"
            lang.startsWith("spa") || lang.startsWith("es") -> "es-ES-ElviraNeural"
            lang.startsWith("fra") || lang.startsWith("fr") -> "fr-FR-DeniseNeural"
            lang.startsWith("deu") || lang.startsWith("de") -> "de-DE-KatjaNeural"
            lang.startsWith("ita") || lang.startsWith("it") -> "it-IT-ElsaNeural"
            lang.startsWith("jpn") || lang.startsWith("ja") -> "ja-JP-NanamiNeural"
            lang.startsWith("zho") || lang.startsWith("zh") -> "zh-CN-XiaoxiaoNeural"
            else -> DEFAULT_VOICE
        }
    }

    private fun buildDialogueAwareSegments(text: String): List<SpeechSegment> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val rawSegments = ArrayList<SpeechSegment>()
        val paragraphs = normalized.split("\n")

        for (rawParagraph in paragraphs) {
            val line = rawParagraph.trim()
            if (line.isEmpty()) continue
            extractSegmentsFromLine(line, rawSegments)
        }

        if (rawSegments.isEmpty()) {
            return listOf(SpeechSegment(normalized, isDialogue = false))
        }

        val merged = ArrayList<SpeechSegment>()
        for (seg in rawSegments) {
            val cleanText = seg.text.trim()
            if (cleanText.isEmpty()) continue

            val last = merged.lastOrNull()
            if (last != null &&
                last.isDialogue == seg.isDialogue &&
                (last.text.length + 1 + cleanText.length) <= TARGET_MAX_CHUNK_CHARS
            ) {
                merged[merged.lastIndex] = SpeechSegment(
                    text = "${last.text} $cleanText",
                    isDialogue = last.isDialogue
                )
            } else if (cleanText.length > TARGET_MAX_CHUNK_CHARS) {
                for (subChunk in buildBalancedChunks(cleanText)) {
                    merged.add(SpeechSegment(subChunk, isDialogue = seg.isDialogue))
                }
            } else {
                merged.add(SpeechSegment(cleanText, isDialogue = seg.isDialogue))
            }
        }

        return merged
    }

    private fun extractSegmentsFromLine(line: String, out: MutableList<SpeechSegment>) {
        if (startsWithDash(line)) {
            val parts = splitByDashMarkers(line)
            if (parts.isNotEmpty()) {
                for (i in parts.indices) {
                    val piece = parts[i].trim()
                    if (piece.isEmpty()) continue
                    val isSpeech = (i % 2 == 0)
                    out.add(SpeechSegment(piece, isDialogue = isSpeech))
                }
                return
            }
        }

        val quoteRegex = Regex("""(["“«][^"”»]+["”»])""")
        var cursor = 0
        var matchedAny = false

        for (match in quoteRegex.findAll(line)) {
            matchedAny = true
            val before = line.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) {
                out.add(SpeechSegment(before, isDialogue = false))
            }
            val speech = match.value.trim()
            if (speech.isNotEmpty()) {
                out.add(SpeechSegment(speech, isDialogue = true))
            }
            cursor = match.range.last + 1
        }

        if (matchedAny) {
            val after = line.substring(cursor).trim()
            if (after.isNotEmpty()) {
                out.add(SpeechSegment(after, isDialogue = false))
            }
            return
        }

        out.add(SpeechSegment(line, isDialogue = false))
    }

    private fun startsWithDash(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("—") || t.startsWith("–") || t.startsWith("- ")
    }

    private fun splitByDashMarkers(line: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            val isLongDash = (ch == '—' || ch == '–')
            val isHyphenMarker = (ch == '-' && i + 1 < line.length && line[i + 1] == ' ' &&
                (i == 0 || line[i - 1].isWhitespace()))

            if ((isLongDash || isHyphenMarker) && current.isNotBlank()) {
                result.add(current.toString().trim())
                current.setLength(0)
            }

            current.append(ch)
            i++
        }

        if (current.isNotBlank()) {
            result.add(current.toString().trim())
        }
        return result
    }

    private fun cancelPrefetch() {
        prefetchFuture?.cancel(true)
        prefetchFuture = null
        prefetchedRequestId?.let { EdgeTtsNative.nativeStop(it) }
        prefetchedRequestId = null
    }

    private fun buildBalancedChunks(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").trim()
        if (normalized.length <= TARGET_MAX_CHUNK_CHARS) {
            return listOf(normalized)
        }

        val result = ArrayList<String>()
        val current = StringBuilder()
        var i = 0

        while (i < normalized.length) {
            val ch = normalized[i]
            current.append(ch)

            val isSentenceEnd = ch == '.' || ch == '!' || ch == '?' || ch == '\n'
            val nextIsSpaceOrEnd = (i + 1 >= normalized.length) || normalized[i + 1].isWhitespace()

            if (isSentenceEnd && nextIsSpaceOrEnd) {
                val len = current.length
                if (len >= MIN_CHUNK_CHARS_BEFORE_SPLIT || len >= TARGET_MAX_CHUNK_CHARS) {
                    val piece = current.toString().trim()
                    if (piece.isNotEmpty()) {
                        result.add(piece)
                    }
                    current.setLength(0)
                }
            } else if (current.length >= TARGET_MAX_CHUNK_CHARS && ch.isWhitespace()) {
                val piece = current.toString().trim()
                if (piece.isNotEmpty()) {
                    result.add(piece)
                }
                current.setLength(0)
            }

            i++
        }

        val remainder = current.toString().trim()
        if (remainder.isNotEmpty()) {
            if (remainder.length < 60 && result.isNotEmpty()) {
                val lastIdx = result.lastIndex
                result[lastIdx] = "${result[lastIdx]} $remainder"
            } else {
                result.add(remainder)
            }
        }

        return if (result.isEmpty()) listOf(normalized) else result
    }

    private fun saveDiag(message: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DIAG, message)
            .apply()
    }
}
