package com.example.apnea

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

class AnalysisActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analysis)

        val csvPath = intent.getStringExtra("EXTRA_CSV_PATH") ?: return
        val file = File(csvPath)
        
        val tvTitle = findViewById<TextView>(R.id.tvAnalysisTitle)
        val tvSummary = findViewById<TextView>(R.id.tvSummary)
        val tvHints = findViewById<TextView>(R.id.tvHints)
        val btnApply = findViewById<android.widget.Button>(R.id.btnApplyRecommendation)
        val chartView = findViewById<ApneaChartView>(R.id.apneaChartView)
        
        tvTitle.text = getString(R.string.analysis_title, file.name)

        if (!file.exists()) {
            tvSummary.text = getString(R.string.analysis_not_found)
            return
        }

        val snoreData = ArrayList<Float>()
        val apneaData = ArrayList<Float>()
        val rawPulseData = ArrayList<Pair<Int, Float>>() // Index, BPM
        val alarmIndices = ArrayList<Int>()
        var alarmCount = 0
        var totalLines = 0
        
        var legacyAlarmsHeuristic = 0
        var apneaCounter = 0
        
        var recordedSilenceThresh = 250
        var isTestModeFile = false

        try {
            BufferedReader(FileReader(file)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    if (line.startsWith("#SETTINGS:")) {
                        val parts = line.removePrefix("#SETTINGS:").split(",")
                        for (p in parts) {
                            if (p.startsWith("sil=")) recordedSilenceThresh = p.substringAfter("=").toDoubleOrNull()?.toInt() ?: 250
                            if (p.startsWith("test=")) isTestModeFile = p.substringAfter("=").toBoolean()
                        }
                    } else if (!line.startsWith("Timestamp")) {
                        try {
                            val tokens = line.split(",")
                            if (tokens.size >= 2) {
                                val eventType = tokens[1]
                                if (eventType == "ALARM_START") {
                                    alarmCount++
                                    alarmIndices.add(apneaData.size)
                                } else if (eventType == "HEALTH_HR") {
                                    val bpm = tokens[2].toFloatOrNull() ?: 0f
                                    rawPulseData.add(Pair(apneaData.size, bpm))
                                } else if (eventType.startsWith("ML_")) {
                                    val snore = if (tokens.size > 2) tokens[2].toFloatOrNull() ?: 0f else 0f
                                    val apnea = if (tokens.size > 3) tokens[3].toFloatOrNull() ?: 0f else 0f
                                    snoreData.add(snore)
                                    apneaData.add(apnea)
                                    
                                    if (apnea > 0.95f) apneaCounter++
                                    else {
                                        if (apneaCounter >= 2) legacyAlarmsHeuristic++
                                        apneaCounter = 0
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                        totalLines++
                    }
                    line = br.readLine()
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        // Interpolate Pulse
        val pulseInterpolated = FloatArray(apneaData.size)
        if (rawPulseData.isNotEmpty()) {
            var pulseIdx = 0
            for (i in apneaData.indices) {
                if (pulseIdx < rawPulseData.size - 1 && i >= rawPulseData[pulseIdx + 1].first) {
                    pulseIdx++
                }
                if (pulseIdx < rawPulseData.size - 1) {
                    val p1 = rawPulseData[pulseIdx]; val p2 = rawPulseData[pulseIdx + 1]
                    val t = (i - p1.first).toFloat() / (p2.first - p1.first)
                    val bpm = p1.second + t * (p2.second - p1.second)
                    pulseInterpolated[i] = (bpm - 40f) / 80f // Normalize 40-120 range to 0-1
                } else if (rawPulseData.isNotEmpty()) {
                    pulseInterpolated[i] = (rawPulseData.last().second - 40f) / 80f
                }
            }
        }

        val finalAlarmCount = if (alarmCount > 0) alarmCount else legacyAlarmsHeuristic
        val isLegacy = alarmCount == 0 && legacyAlarmsHeuristic > 0

        tvSummary.text = getString(R.string.analysis_datapoints, totalLines, finalAlarmCount) + 
                        (if (isLegacy) getString(R.string.analysis_estimated) else "") +
                        (if (isTestModeFile) getString(R.string.analysis_test_run) else "")
        
        val factor = (snoreData.size / 1000).coerceAtLeast(1)
        val displaySnore = snoreData.filterIndexed { index, _ -> index % factor == 0 }
        val displayApnea = apneaData.filterIndexed { index, _ -> index % factor == 0 }
        val displayPulse = pulseInterpolated.filterIndexed { index, _ -> index % factor == 0 }
        val displayAlarms = alarmIndices.map { it / factor }
        
        chartView.setData(displaySnore, displayApnea, displayPulse, displayAlarms)

        // RECOMMENDATION BRAIN
        var hints = getString(R.string.analysis_hints_title)
        var recommendedSilChange = 0
        
        if (isTestModeFile) {
            hints += getString(R.string.analysis_hint_test)
        } else {
            if (finalAlarmCount == 0) {
                hints += getString(R.string.analysis_hint_no_alarms)
                recommendedSilChange = -50
            } else if (finalAlarmCount > 15) {
                hints += getString(R.string.analysis_hint_too_many, finalAlarmCount)
                recommendedSilChange = 50
            } else {
                hints += getString(R.string.analysis_hint_optimal, finalAlarmCount)
            }
        }

        if (recommendedSilChange != 0 && !isTestModeFile) {
            btnApply.visibility = android.view.View.VISIBLE
            btnApply.setOnClickListener {
                val prefs = getSharedPreferences("ApneaPrefs", MODE_PRIVATE)
                val newSil = (recordedSilenceThresh + recommendedSilChange).coerceIn(50, 1000)
                prefs.edit().putInt("silence", newSil).apply()
                MainActivity.sil = newSil
                android.widget.Toast.makeText(this, getString(R.string.toast_optimized, newSil), android.widget.Toast.LENGTH_SHORT).show()
                btnApply.visibility = android.view.View.GONE
            }
        }
        
        tvHints.text = hints
    }
}
