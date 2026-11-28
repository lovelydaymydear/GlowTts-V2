package com.exemplo.edgetts

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.exemplo.edgetts.databinding.ActivitySettingsBinding
import com.exemplo.edgetts.protocol.EdgeTtsClient
import com.exemplo.edgetts.protocol.EdgeVoice
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "edge_tts_prefs"
        private const val KEY_VOICE = "selected_voice"
        private const val KEY_VOICE_LIST = "selected_voices_list"
        private const val KEY_SINGLE_VOICE_MODE = "single_voice_mode"
        private const val KEY_DIALOGUE_VOICE_ENABLED = "dialogue_voice_enabled"
        private const val KEY_DIALOGUE_VOICE = "dialogue_voice"
        private const val KEY_RATE_PERCENT = "rate_percent"
        private const val KEY_PITCH_HZ = "pitch_hz"
        private const val KEY_CACHED_VOICES_JSON = "cached_voices_json"
        private const val DEFAULT_VOICE = "pt-BR-FranciscaNeural"
        private const val DEFAULT_DIALOGUE_VOICE = "pt-BR-AntonioNeural"
        private const val ALL_LANGUAGES_OPTION = "Todos os idiomas"
    }

    private lateinit var binding: ActivitySettingsBinding
    private val client = EdgeTtsClient()
    private val executor = Executors.newSingleThreadExecutor()

    private var allVoices: List<EdgeVoice> = EdgeTtsClient.DEFAULT_FALLBACK_VOICES
    private var languageOptions: List<String> = listOf(ALL_LANGUAGES_OPTION)
    private var selectedLanguage: String = ALL_LANGUAGES_OPTION
    private var filteredVoices: List<EdgeVoice> = EdgeTtsClient.DEFAULT_FALLBACK_VOICES
    private var savedVoice: String = DEFAULT_VOICE
    private var savedDialogueVoice: String = DEFAULT_DIALOGUE_VOICE
    private var dialogueVoiceEnabled: Boolean = false
    private var singleVoiceMode: Boolean = true
    private var isPopulatingSpinners: Boolean = false
    private val activeVoiceQueue = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        savedVoice = prefs.getString(KEY_VOICE, DEFAULT_VOICE)?.trim().orEmpty()
            .ifEmpty { DEFAULT_VOICE }

        val storedDialogueVoice = prefs.getString(KEY_DIALOGUE_VOICE, null)?.trim().orEmpty()
        savedDialogueVoice = if (storedDialogueVoice.isNotEmpty()) {
            storedDialogueVoice
        } else {
            DEFAULT_DIALOGUE_VOICE
        }

        dialogueVoiceEnabled = prefs.getBoolean(KEY_DIALOGUE_VOICE_ENABLED, false)

        val savedRate = prefs.getInt(KEY_RATE_PERCENT, 0).coerceIn(-50, 50)
        val savedPitch = prefs.getInt(KEY_PITCH_HZ, 0).coerceIn(-50, 50)

        val rawList = prefs.getString(KEY_VOICE_LIST, savedVoice).orEmpty()
        val parsedList = rawList.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        singleVoiceMode = prefs.getBoolean(KEY_SINGLE_VOICE_MODE, parsedList.size <= 1)

        activeVoiceQueue.clear()
        if (singleVoiceMode || parsedList.isEmpty()) {
            activeVoiceQueue.add(savedVoice)
        } else {
            activeVoiceQueue.addAll(parsedList)
        }
        updateActiveVoicesLabel()

        val cachedVoices = loadCachedVoices(prefs.getString(KEY_CACHED_VOICES_JSON, null))
        val hasCachedVoices = cachedVoices.isNotEmpty()
        allVoices = if (hasCachedVoices) cachedVoices else EdgeTtsClient.DEFAULT_FALLBACK_VOICES

        rebuildLanguagesAndVoices(allVoices, savedVoice)
        updateDialogueStatusLabel()

        binding.tvSettingsStatus.text = if (hasCachedVoices) {
            "Catálogo salvo carregado (${allVoices.size} vozes). Atualizando..."
        } else {
            "Carregando catálogo completo de vozes..."
        }

        binding.sbRate.progress = savedRate + 50
        binding.tvRateLabel.text = "Velocidade extra: ${formatSignedPercent(savedRate)}"

        binding.sbPitch.progress = savedPitch + 50
        binding.tvPitchLabel.text = "Tom extra: ${formatSignedHz(savedPitch)}"

        binding.spLanguages.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (isPopulatingSpinners) return
                selectedLanguage = languageOptions.getOrElse(position) { ALL_LANGUAGES_OPTION }
                applyFilters(binding.etVoiceSearch.text?.toString().orEmpty(), savedVoice)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.spVoices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (isPopulatingSpinners) return
                val pickedVoice = filteredVoices.getOrNull(position)?.shortName ?: return
                if (pickedVoice.isNotBlank()) {
                    savedVoice = pickedVoice
                    if (singleVoiceMode || activeVoiceQueue.size <= 1) {
                        activeVoiceQueue.clear()
                        activeVoiceQueue.add(pickedVoice)
                    } else if (!activeVoiceQueue.contains(pickedVoice)) {
                        activeVoiceQueue.add(0, pickedVoice)
                    }
                    updateActiveVoicesLabel()
                    persistAllSettings(showStatusMessage = false)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.etVoiceSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilters(s?.toString().orEmpty(), savedVoice)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnAddVoiceToList.setOnClickListener {
            val currentVoice = filteredVoices.getOrNull(binding.spVoices.selectedItemPosition)?.shortName
                ?: savedVoice
            if (currentVoice.isBlank()) return@setOnClickListener

            if (singleVoiceMode) {
                activeVoiceQueue.clear()
                activeVoiceQueue.add(savedVoice)
                singleVoiceMode = false
            }

            if (!activeVoiceQueue.contains(currentVoice)) {
                activeVoiceQueue.add(currentVoice)
            }
            savedVoice = activeVoiceQueue.firstOrNull() ?: currentVoice
            updateActiveVoicesLabel()
            persistAllSettings(showStatusMessage = false)
            binding.tvSettingsStatus.text = "Adicionada: $currentVoice (${activeVoiceQueue.size} voz(es) na fila)"
        }

        binding.btnClearVoiceList.setOnClickListener {
            val currentVoice = filteredVoices.getOrNull(binding.spVoices.selectedItemPosition)?.shortName
                ?: savedVoice
            singleVoiceMode = true
            savedVoice = currentVoice
            activeVoiceQueue.clear()
            activeVoiceQueue.add(currentVoice)
            updateActiveVoicesLabel()
            persistAllSettings(showStatusMessage = false)
            binding.tvSettingsStatus.text = "Modo de voz única: $currentVoice"
        }

        binding.btnSetDialogueVoice?.setOnClickListener {
            val currentVoice = filteredVoices.getOrNull(binding.spVoices.selectedItemPosition)?.shortName
                ?: savedDialogueVoice
            if (currentVoice.isNotBlank()) {
                savedDialogueVoice = currentVoice
                dialogueVoiceEnabled = true
                prefs.edit()
                    .putString(KEY_DIALOGUE_VOICE, savedDialogueVoice)
                    .putBoolean(KEY_DIALOGUE_VOICE_ENABLED, dialogueVoiceEnabled)
                    .apply()
                updateDialogueStatusLabel()
                binding.tvSettingsStatus.text = "Voz de diálogo definida: $savedDialogueVoice (ativa)"
            }
        }

        binding.btnToggleDialogueVoice?.setOnClickListener {
            dialogueVoiceEnabled = !dialogueVoiceEnabled
            prefs.edit()
                .putBoolean(KEY_DIALOGUE_VOICE_ENABLED, dialogueVoiceEnabled)
                .apply()
            updateDialogueStatusLabel()
            binding.tvSettingsStatus.text = if (dialogueVoiceEnabled) {
                "Voz para diálogos ativada ($savedDialogueVoice)"
            } else {
                "Voz para diálogos desativada"
            }
        }

        binding.btnReloadVoices.setOnClickListener {
            loadRemoteVoices()
        }

        binding.sbRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = progress - 50
                binding.tvRateLabel.text = "Velocidade extra: ${formatSignedPercent(rate)}"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                persistAllSettings(showStatusMessage = false)
            }
        })

        binding.sbPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = progress - 50
                binding.tvPitchLabel.text = "Tom extra: ${formatSignedHz(pitch)}"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                persistAllSettings(showStatusMessage = false)
            }
        })

        binding.btnSaveSettings.setOnClickListener {
            persistAllSettings(showStatusMessage = true)
        }

        loadRemoteVoices()
    }

    override fun onPause() {
        super.onPause()
        persistAllSettings(showStatusMessage = false)
    }

    private fun persistAllSettings(showStatusMessage: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val chosenVoice = savedVoice.trim().ifEmpty { DEFAULT_VOICE }
        savedVoice = chosenVoice

        val currentDialogueInPrefs = prefs.getString(KEY_DIALOGUE_VOICE, null)?.trim().orEmpty()
        val dialogueVoiceToKeep = if (currentDialogueInPrefs.isNotEmpty()) {
            currentDialogueInPrefs
        } else {
            savedDialogueVoice
        }
        savedDialogueVoice = dialogueVoiceToKeep

        val finalList = if (singleVoiceMode || activeVoiceQueue.size <= 1) {
            singleVoiceMode = true
            activeVoiceQueue.clear()
            activeVoiceQueue.add(chosenVoice)
            listOf(chosenVoice)
        } else {
            if (!activeVoiceQueue.contains(chosenVoice)) {
                activeVoiceQueue.add(0, chosenVoice)
            }
            activeVoiceQueue.distinct().toList()
        }

        val ratePercent = binding.sbRate.progress - 50
        val pitchHz = binding.sbPitch.progress - 50
        val serializedQueue = finalList.joinToString("|")

        prefs.edit()
            .putString(KEY_VOICE, savedVoice)
            .putString(KEY_VOICE_LIST, serializedQueue)
            .putBoolean(KEY_SINGLE_VOICE_MODE, singleVoiceMode)
            .putString(KEY_DIALOGUE_VOICE, dialogueVoiceToKeep)
            .putBoolean(KEY_DIALOGUE_VOICE_ENABLED, dialogueVoiceEnabled)
            .putInt(KEY_RATE_PERCENT, ratePercent)
            .putInt(KEY_PITCH_HZ, pitchHz)
            .apply()

        updateActiveVoicesLabel()
        updateDialogueStatusLabel()

        if (showStatusMessage) {
            binding.tvSettingsStatus.text = if (singleVoiceMode) {
                "Salvo! Voz geral: $savedVoice | Diálogo: $savedDialogueVoice (${if (dialogueVoiceEnabled) "ativo" else "inativo"})"
            } else {
                "Salvo! Rotação (${finalList.size} vozes): $serializedQueue"
            }
        }
    }

    private fun updateDialogueStatusLabel() {
        val status = if (dialogueVoiceEnabled) {
            "Diálogo: ATIVO ($savedDialogueVoice)"
        } else {
            "Diálogo: DESATIVADO ($savedDialogueVoice)"
        }
        binding.tvDialogueVoiceStatus?.text = status
    }

    private fun loadRemoteVoices() {
        binding.btnReloadVoices.isEnabled = false
        binding.tvSettingsStatus.text = "Buscando todas as vozes no servidor..."
        executor.execute {
            try {
                val remoteVoices = client.fetchAllVoices()
                val jsonToSave = serializeVoices(remoteVoices)
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CACHED_VOICES_JSON, jsonToSave)
                    .apply()

                runOnUiThread {
                    allVoices = remoteVoices
                    rebuildLanguagesAndVoices(allVoices, savedVoice)
                    binding.tvSettingsStatus.text = "${remoteVoices.size} vozes carregadas e salvas! Voz atual: $savedVoice"
                    binding.btnReloadVoices.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    rebuildLanguagesAndVoices(allVoices, savedVoice)
                    binding.tvSettingsStatus.text = "Mantendo ${allVoices.size} vozes (${e.message})"
                    binding.btnReloadVoices.isEnabled = true
                }
            }
        }
    }

    private fun serializeVoices(voices: List<EdgeVoice>): String {
        val arr = JSONArray()
        for (v in voices) {
            val obj = JSONObject()
            obj.put("shortName", v.shortName)
            obj.put("friendlyName", v.friendlyName)
            obj.put("locale", v.locale)
            obj.put("gender", v.gender)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun loadCachedVoices(rawJson: String?): List<EdgeVoice> {
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

    private fun rebuildLanguagesAndVoices(voices: List<EdgeVoice>, targetShortName: String) {
        isPopulatingSpinners = true
        try {
            val distinctLocales = voices
                .map { it.locale.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedWith(
                    compareBy<String> { !it.startsWith("pt", ignoreCase = true) }
                        .thenBy { it }
                )

            languageOptions = listOf(ALL_LANGUAGES_OPTION) + distinctLocales

            val targetVoiceLocale = voices.firstOrNull {
                it.shortName.equals(targetShortName, ignoreCase = true)
            }?.locale

            if (selectedLanguage == ALL_LANGUAGES_OPTION && !targetVoiceLocale.isNullOrBlank()) {
                selectedLanguage = targetVoiceLocale
            }

            val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageOptions)
            langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spLanguages.adapter = langAdapter

            val langIdx = languageOptions.indexOfFirst { it.equals(selectedLanguage, ignoreCase = true) }
            if (langIdx >= 0) {
                binding.spLanguages.setSelection(langIdx, false)
            } else {
                selectedLanguage = ALL_LANGUAGES_OPTION
                binding.spLanguages.setSelection(0, false)
            }

            applyFilters(binding.etVoiceSearch.text?.toString().orEmpty(), targetShortName)
        } finally {
            isPopulatingSpinners = false
        }
    }

    private fun applyFilters(query: String, targetShortName: String) {
        val previousFlag = isPopulatingSpinners
        isPopulatingSpinners = true
        try {
            val trimmed = query.trim().lowercase(Locale.ROOT)
            val matched = allVoices.filter { voice ->
                val matchesLang = selectedLanguage == ALL_LANGUAGES_OPTION ||
                    voice.locale.equals(selectedLanguage, ignoreCase = true)
                val matchesSearch = trimmed.isEmpty() ||
                    voice.displayLabel().lowercase(Locale.ROOT).contains(trimmed)
                matchesLang && matchesSearch
            }

            filteredVoices = if (matched.isEmpty()) allVoices else matched

            val labels = filteredVoices.map { it.displayLabel() }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spVoices.adapter = adapter

            val index = filteredVoices.indexOfFirst { it.shortName.equals(targetShortName, ignoreCase = true) }
            if (index >= 0) {
                binding.spVoices.setSelection(index, false)
            } else {
                binding.spVoices.setSelection(0, false)
            }
        } finally {
            isPopulatingSpinners = previousFlag
        }
    }

    private fun updateActiveVoicesLabel() {
        val display = if (singleVoiceMode || activeVoiceQueue.size <= 1) {
            "${activeVoiceQueue.firstOrNull() ?: savedVoice} (voz única)"
        } else {
            activeVoiceQueue.joinToString(" → ")
        }
        binding.tvActiveVoicesList.text = "Vozes em rotação: $display"
    }

    private fun formatSignedPercent(value: Int): String {
        return if (value >= 0) "+$value%" else "$value%"
    }

    private fun formatSignedHz(value: Int): String {
        return if (value >= 0) "+${value}Hz" else "${value}Hz"
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
