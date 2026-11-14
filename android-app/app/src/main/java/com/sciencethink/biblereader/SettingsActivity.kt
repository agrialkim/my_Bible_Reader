package com.sciencethink.biblereader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import java.io.File

class SettingsActivity : Activity() {
    private lateinit var importBibleButton: Button
    private lateinit var settingsVoiceSpinner: Spinner
    private lateinit var autoScrollSwitch: Switch
    private lateinit var saveOptionsButton: Button
    private lateinit var backButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("theme_mode", "normal") ?: "normal"
        val barColor = if (mode == "dark") android.graphics.Color.parseColor("#1E3A8A") else android.graphics.Color.parseColor("#2563EB")
        window.statusBarColor = barColor
        window.navigationBarColor = barColor

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }

        importBibleButton = findViewById(R.id.importBibleButton)
        settingsVoiceSpinner = findViewById(R.id.settingsVoiceSpinner)
        autoScrollSwitch = findViewById(R.id.autoScrollSwitch)
        saveOptionsButton = findViewById(R.id.saveOptionsButton)
        backButton = findViewById(R.id.backButton)

        backButton.setOnClickListener { finish() }

        importBibleButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
            }
            startActivityForResult(intent, 100)
        }

        setupSpinners()

        autoScrollSwitch.isChecked = prefs.getBoolean("auto_scroll_enabled", true)

        saveOptionsButton.setOnClickListener {
            val editor = prefs.edit()
            editor.putInt("voice_index", settingsVoiceSpinner.selectedItemPosition)
            editor.putBoolean("auto_scroll_enabled", autoScrollSwitch.isChecked)
            editor.apply()

            Toast.makeText(this, "옵션이 저장되었습니다.", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
                var fileName = uri.lastPathSegment ?: "imported.txt"
                if (!fileName.endsWith(".txt")) fileName += ".txt"
                
                val importDir = File(filesDir, "imported_bibles")
                importDir.mkdirs()
                val targetFile = File(importDir, fileName)
                targetFile.writeBytes(bytes)
                
                Toast.makeText(this, "$fileName 추가 완료! 메인 화면에서 선택하세요.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "파일 추가 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSpinners() {
        try {
            val prefs = getSharedPreferences("bible_prefs", Context.MODE_PRIVATE)

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
