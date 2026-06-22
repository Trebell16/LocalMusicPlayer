package com.example.playback

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.Equalizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioEqualizerManager(private val context: Context) {

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences("equalizer_settings", Context.MODE_PRIVATE)

    private var equalizer: Equalizer? = null
    private var currentAudioSessionId: Int? = null

    // Default 5-band frequency labels in Hz
    val bandFrequencies = listOf(60, 230, 910, 4000, 14000)

    // Flows for UI state subscription
    private val _bandLevels = MutableStateFlow<List<Float>>(listOf(0f, 0f, 0f, 0f, 0f))
    val bandLevels: StateFlow<List<Float>> = _bandLevels.asStateFlow()

    private val _currentPreset = MutableStateFlow("Flat")
    val currentPreset: StateFlow<String> = _currentPreset.asStateFlow()

    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val enabled = sharedPrefs.getBoolean("eq_enabled", true)
        _isEnabled.value = enabled

        val preset = sharedPrefs.getString("eq_preset", "Flat") ?: "Flat"
        _currentPreset.value = preset

        val levels = mutableListOf<Float>()
        for (i in 0 until 5) {
            val defaultVal = getPresetLevelForBand(preset, i)
            val level = sharedPrefs.getFloat("eq_band_$i", defaultVal)
            levels.add(level)
        }
        _bandLevels.value = levels
    }

    private fun saveSettings() {
        sharedPrefs.edit().apply {
            putBoolean("eq_enabled", _isEnabled.value)
            putString("eq_preset", _currentPreset.value)
            for (i in _bandLevels.value.indices) {
                putFloat("eq_band_$i", _bandLevels.value[i])
            }
            apply()
        }
    }

    /**
     * Set the active audio session ID to attach the hardware equalizer.
     */
    fun onAudioSessionIdChanged(sessionId: Int?) {
        if (sessionId == null) {
            releaseEqualizer()
            currentAudioSessionId = null
            return
        }

        if (currentAudioSessionId == sessionId && equalizer != null) {
            // Already attached to this session
            return
        }

        currentAudioSessionId = sessionId
        try {
            releaseEqualizer()
            
            // Intialize native equalizer
            val eq = Equalizer(0, sessionId)
            eq.enabled = _isEnabled.value
            
            equalizer = eq
            applyGainsToHardware()
            Log.d("AudioEqualizerManager", "Successfully attached Equalizer to session: $sessionId")
        } catch (e: Exception) {
            Log.e("AudioEqualizerManager", "Failed to initialize native Equalizer for session: $sessionId. Running in simulated mode.", e)
            equalizer = null
        }
    }

    /**
     * Turn the equalizer on or off.
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        try {
            equalizer?.enabled = enabled
        } catch (e: Exception) {
            Log.e("AudioEqualizerManager", "Failed to set enabled state on hardware", e)
        }
        saveSettings()
    }

    /**
     * Update a single band level manually in decibels (-15dB to +15dB).
     */
    fun setBandLevel(bandIndex: Int, levelDb: Float) {
        val clampedLevel = levelDb.coerceIn(-15f, 15f)
        val currentList = _bandLevels.value.toMutableList()
        if (bandIndex in currentList.indices) {
            currentList[bandIndex] = clampedLevel
            _bandLevels.value = currentList
            
            // Any manual adjustment breaks the current preset match unless it matches exactly
            _currentPreset.value = "Custom"
            
            applyGainsToHardware()
            saveSettings()
        }
    }

    /**
     * Set a preset configuration.
     */
    fun setPreset(presetName: String) {
        if (presetName == "Custom") return
        _currentPreset.value = presetName
        
        val newLevels = List(5) { i -> getPresetLevelForBand(presetName, i) }
        _bandLevels.value = newLevels
        
        applyGainsToHardware()
        saveSettings()
    }

    private fun getPresetLevelForBand(preset: String, bandIndex: Int): Float {
        return when (preset) {
            "Bass Boost" -> {
                when (bandIndex) {
                    0 -> 8f
                    1 -> 5f
                    2 -> 0f
                    3 -> -2f
                    4 -> -3f
                    else -> 0f
                }
            }
            "Vocal" -> {
                when (bandIndex) {
                    0 -> -3f
                    1 -> -1f
                    2 -> 4f
                    3 -> 6f
                    4 -> 2f
                    else -> 0f
                }
            }
            // "Flat" or anything else defaults to 0dB gains
            else -> 0f
        }
    }

    private fun applyGainsToHardware() {
        val eq = equalizer ?: return
        if (!_isEnabled.value) return

        try {
            // Android Equalizer has native bands (typically 5)
            // Query the count of bands supported by hardware
            val numBandsOnHardware = eq.numberOfBands.toInt()
            
            for (i in 0 until numBandsOnHardware) {
                // Determine our target decibel level for this index, defaulting to 0f if outside our list range
                val userLevelDb = _bandLevels.value.getOrNull(i) ?: 0f
                
                // Convert to millibels (1 dB = 100 mB)
                val targetMilliBels = (userLevelDb * 100).toInt().toShort()
                
                // Fetch valid range from hardware to clamp safely
                val bandRange = eq.bandLevelRange
                val clampedMilliBels = if (bandRange.size >= 2) {
                    targetMilliBels.coerceIn(bandRange[0], bandRange[1])
                } else {
                    targetMilliBels.coerceIn(-1500, 1500)
                }
                
                eq.setBandLevel(i.toShort(), clampedMilliBels)
            }
        } catch (e: Exception) {
            Log.e("AudioEqualizerManager", "Failed to apply band gains to hardware Equalizer", e)
        }
    }

    fun releaseEqualizer() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
            Log.e("AudioEqualizerManager", "Failed to release Equalizer", e)
        }
        equalizer = null
    }
}
