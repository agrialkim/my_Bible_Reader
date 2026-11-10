package com.sciencethink.biblereader

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class SettingsActivity : Activity() {
    private lateinit var settingsAssetSpinner: Spinner
    private lateinit var settingsVoiceSpinner: Spinner
    private lateinit var autoScrollSwitch: Switch
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speedValueTextView: TextView
    private lateinit var saveOptionsButton: Button
    private lateinit var backButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settingsAssetSpinner = findViewById(R.id.settingsAssetSpinner)
        settingsVoiceSpinner = findViewById(R.id.settingsVoiceSpinner)
        autoScrollSwitch = findViewById(R.id.autoScrollSwitch)
        speedSeekBar = findViewById(R.id.speedSeekBar)
        speedValueTextView = findViewById(R.id.speedValueTextView)
        saveOptionsButton = findViewById(R.id.saveOptionsButton)
        backButton = findViewById(R.id.backButton)

        backButton.setOnClickListener { finish() }

        setupSpinners()

        val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
        autoScrollSwitch.isChecked = prefs.getBoolean("auto_scroll_enabled", true)

        speedSeekBar.max = 20 // 0.5x (progress 0) to 2.5x (progress 20)
        val savedMultiplier = prefs.getFloat("scroll_speed_multiplier", 1.0f)
        val initialProgress = ((savedMultiplier - 0.5f) * 10f).toInt().coerceIn(0, 20)
        speedSeekBar.progress = initialProgress
        updateSpeedLabel(initialProgress)

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSpeedLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        saveOptionsButton.setOnClickListener {
            val multiplier = 0.5f + (speedSeekBar.progress * 0.1f)
            val editor = prefs.edit()
            editor.putInt("asset_index", settingsAssetSpinner.selectedItemPosition)
            editor.putInt("voice_index", settingsVoiceSpinner.selectedItemPosition)
            editor.putBoolean("auto_scroll_enabled", autoScrollSwitch.isChecked)
            editor.putFloat("scroll_speed_multiplier", multiplier)
            editor.apply()

            Toast.makeText(this, "옵션이 저장되었습니다.", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun updateSpeedLabel(progress: Int) {
        val multiplier = 0.5f + (progress * 0.1f)
        speedValueTextView.text = String.format(Locale.US, "%.1f배", multiplier)
    }

    private fun setupSpinners() {
        try {
            val rawFiles = (assets.list("") ?: emptyArray()).filter { it.endsWith(".txt") }
            val preferredOrder = listOf("개역개정.txt")
            val assetFiles = rawFiles.sortedWith(Comparator { a, b ->
                val indexA = preferredOrder.indexOf(a)
                val indexB = preferredOrder.indexOf(b)
                if (indexA != -1 && indexB != -1) indexA.compareTo(indexB)
                else if (indexA != -1) -1
                else if (indexB != -1) 1
                else a.compareTo(b)
            })

            val displayNames = assetFiles.map { it.removeSuffix(".txt") }
            val assetAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            settingsAssetSpinner.adapter = assetAdapter

            val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
            settingsAssetSpinner.setSelection(prefs.getInt("asset_index", 0))

            val voiceNames = listOf("여성 음성 1", "남성 음성 1", "기본 음성")
            val voiceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            settingsVoiceSpinner.adapter = voiceAdapter
            settingsVoiceSpinner.setSelection(prefs.getInt("voice_index", 0))
        } catch (_: Exception) {
        }
    }
}
