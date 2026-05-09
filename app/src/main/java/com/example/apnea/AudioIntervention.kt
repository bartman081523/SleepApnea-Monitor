package com.example.apnea

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.app.NotificationManager
import android.media.RingtoneManager
import android.util.Log

class AudioIntervention(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var previousFilter: Int = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    private var previousVolume: Int = -1

    fun triggerAlarm(volumePercent: Int = 100) {
        // Falls schon ein Alarm läuft, nicht doppelt starten
        if (mediaPlayer?.isPlaying == true) return

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // DND Temporär aufheben
        if (notificationManager.isNotificationPolicyAccessGranted) {
            previousFilter = notificationManager.currentInterruptionFilter
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }

        // Systemlautstärke sichern und neu setzen
        previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val targetVolume = (maxVolume * (volumePercent / 100.0)).toInt()
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()

        mediaPlayer = MediaPlayer().apply {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            setDataSource(context, alarmUri)
            setAudioAttributes(attributes)
            isLooping = true // Alarm soll loopen bis er gestoppt wird
            prepare()
            start()
        }
        Log.i("ApneaApp", "Alarm gestartet (Vol: $volumePercent%)")
    }

    fun stopAlarm() {
        if (mediaPlayer == null) return

        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Lautstärke wiederherstellen
            if (previousVolume != -1) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
            }

            // DND wiederherstellen
            if (notificationManager.isNotificationPolicyAccessGranted && previousFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                notificationManager.setInterruptionFilter(previousFilter)
            }
            
            Log.i("ApneaApp", "Alarm manuell gestoppt.")
        } catch (e: Exception) {
            Log.e("ApneaApp", "Fehler beim Stoppen des Alarms", e)
        }
    }
}
