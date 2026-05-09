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
        val chartView = findViewById<ApneaChartView>(R.id.apneaChartView)
        
        tvTitle.text = "Auswertung: ${file.name}"

        if (!file.exists()) {
            tvSummary.text = "CSV Datei nicht gefunden."
            return
        }

        val snoreData = ArrayList<Float>()
        val apneaData = ArrayList<Float>()
        var alarmCount = 0
        var totalLines = 0

        try {
            BufferedReader(FileReader(file)).use { br ->
                br.readLine() // skip header
                var line = br.readLine()
                while (line != null) {
                    val tokens = line.split(",")
                    if (tokens.size >= 6) {
                        val eventType = tokens[1]
                        if (eventType == "ALARM_START") {
                            alarmCount++
                        } else if (eventType == "ML_BG" || eventType == "ML_TRIGGER") {
                            val snore = tokens[2].toFloatOrNull() ?: 0f
                            val apnea = tokens[3].toFloatOrNull() ?: 0f
                            snoreData.add(snore)
                            apneaData.add(apnea)
                        }
                    }
                    totalLines++
                    line = br.readLine()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        tvSummary.text = "Datenpunkte: $totalLines\nAusgelöste Alarme: $alarmCount"
        
        // Reduziere Datenmenge für den Graph, falls zu viele (e.g. max 1000 Punkte)
        val factor = (snoreData.size / 1000).coerceAtLeast(1)
        val displaySnore = snoreData.filterIndexed { index, _ -> index % factor == 0 }
        val displayApnea = apneaData.filterIndexed { index, _ -> index % factor == 0 }
        
        chartView.setData(displaySnore, displayApnea)

        // Generiere Hinweise
        var hints = "Hinweise zur Optimierung:\n"
        if (alarmCount == 0) {
            hints += "- Keine Alarme erkannt. Falls Sie Atemaussetzer hatten, reduzieren Sie die 'Stille-Schwelle' (RMS) in den Einstellungen.\n"
        } else if (alarmCount > 15) {
            hints += "- Sehr viele Alarme ($alarmCount). Möglicherweise ist die App zu empfindlich. Erhöhen Sie die 'Atempause bis Alarm' oder die 'Stille-Schwelle'.\n"
        } else {
            hints += "- Alarmanzahl ($alarmCount) im erwarteten Rahmen für unbehandelte Apnoe. Beobachten Sie den Trend.\n"
        }
        
        tvHints.text = hints
    }
}
