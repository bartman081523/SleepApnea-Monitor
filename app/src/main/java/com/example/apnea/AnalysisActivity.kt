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
        
        // Heuristik für alte Versionen: Zähle Phasen mit extrem hohem Apnoe-Score (>0.9) 
        // als potenziellen Alarm, falls kein ALARM_START Event gefunden wird.
        var legacyApneaCounter = 0
        var legacyAlarmsHeuristic = 0

        try {
            BufferedReader(FileReader(file)).use { br ->
                val header = br.readLine() // Read header
                var line = br.readLine()
                while (line != null) {
                    try {
                        val tokens = line.split(",")
                        if (tokens.size >= 2) {
                            val eventType = tokens[1]
                            
                            if (eventType == "ALARM_START") {
                                alarmCount++
                            } else if (eventType.startsWith("ML_") || tokens.size >= 6) {
                                // Versuche Scores zu extrahieren (Robust gegen fehlende Spalten)
                                val snore = if (tokens.size > 2) tokens[2].toFloatOrNull() ?: 0f else 0f
                                val apnea = if (tokens.size > 3) tokens[3].toFloatOrNull() ?: 0f else 0f
                                
                                snoreData.add(snore)
                                apneaData.add(apnea)
                                
                                // Heuristik für alte Logs
                                if (apnea > 0.95f) {
                                    legacyApneaCounter++
                                } else {
                                    if (legacyApneaCounter >= 2) { // min 10s hohe Konfidenz
                                        legacyAlarmsHeuristic++
                                    }
                                    legacyApneaCounter = 0
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Überspringe korrupte Zeilen
                    }
                    totalLines++
                    line = br.readLine()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Falls keine echten ALARM_START Events da sind (Legacy), nimm die Heuristik
        val finalAlarmCount = if (alarmCount > 0) alarmCount else legacyAlarmsHeuristic
        val isLegacy = alarmCount == 0 && legacyAlarmsHeuristic > 0

        tvSummary.text = "Datenpunkte: $totalLines\nAusgelöste Alarme: $finalAlarmCount" + 
                        (if (isLegacy) " (geschätzt aus Rohdaten)" else "")
        
        val factor = (snoreData.size / 1000).coerceAtLeast(1)
        val displaySnore = snoreData.filterIndexed { index, _ -> index % factor == 0 }
        val displayApnea = apneaData.filterIndexed { index, _ -> index % factor == 0 }
        
        chartView.setData(displaySnore, displayApnea)

        var hints = "Hinweise zur Optimierung:\n"
        if (finalAlarmCount == 0) {
            hints += "- Keine Alarme erkannt. Falls Sie Atemaussetzer hatten, reduzieren Sie die 'Stille-Schwelle' (RMS).\n"
        } else if (finalAlarmCount > 15) {
            hints += "- Sehr viele Alarme ($finalAlarmCount). Möglicherweise ist die App zu empfindlich.\n"
        } else {
            hints += "- Alarmanzahl ($finalAlarmCount) im erwarteten Rahmen für unbehandelte Apnoe.\n"
        }
        
        tvHints.text = hints
    }
}
