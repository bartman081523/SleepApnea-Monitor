package com.example.apnea

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class QuestionnaireActivity : AppCompatActivity() {

    private var currentStep = 0
    private lateinit var tvQuestion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_questionnaire)

        tvQuestion = findViewById(R.id.tvQuestion)
        val btnYes = findViewById<Button>(R.id.btnYes)
        val btnNo = findViewById<Button>(R.id.btnNo)
        val btnSkip = findViewById<Button>(R.id.btnSkip)
        val btnEnd = findViewById<Button>(R.id.btnEnd)

        updateQuestion()

        btnYes.setOnClickListener { handleAnswer(true) }
        btnNo.setOnClickListener { handleAnswer(false) }
        btnSkip.setOnClickListener { 
            currentStep++
            updateQuestion() 
        }
        btnEnd.setOnClickListener { finish() }
    }

    private fun updateQuestion() {
        when (currentStep) {
            0 -> tvQuestion.text = getString(R.string.q_q1)
            1 -> tvQuestion.text = getString(R.string.q_q2)
            2 -> tvQuestion.text = getString(R.string.q_q3)
            else -> {
                Toast.makeText(this, "Vielen Dank für das Feedback!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun handleAnswer(yes: Boolean) {
        val prefs = getSharedPreferences("ApneaPrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        when (currentStep) {
            0 -> {
                // "Wurden Sie zu oft geweckt?"
                if (yes) {
                    val currentOffset = prefs.getFloat("apnea_weight_offset", 0f)
                    editor.putFloat("apnea_weight_offset", currentOffset + 0.05f) // Mach die Schwelle strenger
                }
            }
            1 -> {
                // "War der Alarmton zu laut?"
                if (yes) {
                    val currentVol = prefs.getInt("volume", 50)
                    editor.putInt("volume", (currentVol - 10).coerceAtLeast(10))
                }
            }
            2 -> {
                // "Gab es Alarme, obwohl Sie nicht geschnarcht haben?"
                if (yes) {
                    val currentSnoreThresh = prefs.getInt("snore", 1200)
                    editor.putInt("snore", (currentSnoreThresh + 200).coerceAtMost(5000))
                }
            }
        }
        editor.apply()
        
        currentStep++
        updateQuestion()
    }
}
