package com.example.apnea

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class ApneaMonitoringService : Service(), SensorEventListener {

    private var audioRecord: AudioRecord? = null
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

    private lateinit var audioIntervention: AudioIntervention
    private var wakeLock: PowerManager.WakeLock? = null
    
    // TFLite
    private var tflite: Interpreter? = null
    private val ML_INPUT_SIZE = 80000 // 5s * 16000Hz (Updated from 60s)
    private var audioWindow = FloatArray(ML_INPUT_SIZE)
    private var windowPointer = 0

    // Einstellungen mit Defaults
    private var silenceThreshold = 250.0
    private var snoreThreshold = 400.0 // Gesenkt von 1200
    private var noiseFloor = 100.0 // Dynamischer Hintergrundpegel
    private var triggerDurationMs = 12000L 
    private var cooldownMs = 3 * 60 * 1000L
    private var alarmVolume = 50

    private var silenceStartTime: Long = 0
    private var lastSnoreTime: Long = 0
    private val SNORE_MEMORY_MS = 60000L 
    
    private lateinit var sensorManager: SensorManager
    private var lastMovementTime: Long = 0
    private var lastInterventionTime: Long = 0
    private var lastMLCheckTime: Long = 0
    private var isMediaBackground = false
    
    private var isTestMode = false
    private var isAlarmRunning = false
    private var isAutoRecord = false
    private var fileOutputStream: java.io.FileOutputStream? = null
    private var currentRecordFile: java.io.File? = null
    private var csvOutputStream: java.io.FileOutputStream? = null
    private var currentCsvFile: java.io.File? = null

    private var testMediaPlayer: android.media.MediaPlayer? = null
    private val testSequence = listOf(
        "speech_test.wav", 
        "snore_quiet_test.wav", 
        "snore_heavy_test.wav", 
        "snort_test.wav",
        "hiccup_test.wav",
        "gasp_test.wav",
        "cough_test.wav",
        "silence_synthetic_test.wav"
    )
    private var testSequenceIndex = 0

    private val settingsReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                alarmVolume = it.getIntExtra("EXTRA_VOLUME", alarmVolume)
                silenceThreshold = it.getDoubleExtra("EXTRA_SILENCE_THRESHOLD", silenceThreshold)
                snoreThreshold = it.getDoubleExtra("EXTRA_SNORE_THRESHOLD", snoreThreshold)
                triggerDurationMs = it.getLongExtra("EXTRA_TRIGGER_DURATION", triggerDurationMs)
                cooldownMs = it.getLongExtra("EXTRA_COOLDOWN", cooldownMs)
                Log.i("ApneaApp", "Live Settings Update: Cooldown=${cooldownMs/(60*1000)}m")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ApneaApp::MonitoringLock")
        audioIntervention = AudioIntervention(this)
        
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        
        lastMovementTime = System.currentTimeMillis()
        
        try {
            tflite = Interpreter(loadModelFile())
            Log.i("ApneaApp", "TFLite Modell erfolgreich geladen")
        } catch (e: Exception) {
            Log.e("ApneaApp", "Fehler beim Laden des TFLite Modells", e)
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(settingsReceiver, IntentFilter("APNEA_SETTINGS_UPDATE"))
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = assets.openFd("apnea_model_quantized.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isTestMode = intent?.getBooleanExtra("EXTRA_TEST_MODE", false) ?: false
        isAutoRecord = intent?.getBooleanExtra("EXTRA_AUTO_RECORD", false) ?: false
        alarmVolume = intent?.getIntExtra("EXTRA_VOLUME", 50) ?: 50
        silenceThreshold = intent?.getDoubleExtra("EXTRA_SILENCE_THRESHOLD", 250.0) ?: 250.0
        snoreThreshold = intent?.getDoubleExtra("EXTRA_SNORE_THRESHOLD", 1200.0) ?: 1200.0
        triggerDurationMs = intent?.getLongExtra("EXTRA_TRIGGER_DURATION", 12000L) ?: 12000L
        cooldownMs = intent?.getLongExtra("EXTRA_COOLDOWN", 3 * 60 * 1000L) ?: (3 * 60 * 1000L)

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "apnea_channel")
            .setContentTitle("Apnoe Wächter Aktiv")
            .setSmallIcon(android.R.drawable.ic_lock_idle_low_battery)
            .build()
        
        startForeground(1, notification)
        
        if (!isRecording) {
            startMonitoring()
            if (isTestMode) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startTestSequence()
                }, 3000)
            }
        }
        
        return START_STICKY
    }

    private fun startTestSequence() {
        Log.i("ADB_SIGNAL", "TEST_SEQUENCE: Starting...")
        testSequenceIndex = 0
        playNextTestFile()
    }

    private fun playNextTestFile() {
        if (testSequenceIndex >= testSequence.size) {
            Log.i("ADB_SIGNAL", "TEST_SEQUENCE: Completed all test files")
            return
        }

        val fileName = testSequence[testSequenceIndex]
        Log.i("ADB_SIGNAL", "TEST_SEQUENCE: Playing $fileName")
        
        // Reset Media Lock state for each test file to ensure fresh results
        isMediaBackground = false
        
        try {
            testMediaPlayer?.release()
            testMediaPlayer = android.media.MediaPlayer().apply {
                val descriptor = assets.openFd(fileName)
                setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
                descriptor.close()
                prepare()
                setVolume(1.0f, 1.0f)
                start()
                setOnCompletionListener {
                    Log.i("ADB_SIGNAL", "TEST_SEQUENCE: Finished $fileName")
                    testSequenceIndex++
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        playNextTestFile()
                    }, 5000)
                }
            }
        } catch (e: Exception) {
            Log.e("ADB_SIGNAL", "TEST_SEQUENCE: Error playing $fileName", e)
        }
    }

    private fun startMonitoring() {
        isRecording = true
        wakeLock?.acquire()
        
        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        
        try {
            val csvName = "night_data_$timeStamp.csv"
            currentCsvFile = java.io.File(getExternalFilesDir(null), csvName)
            csvOutputStream = java.io.FileOutputStream(currentCsvFile)
            csvOutputStream?.write("Timestamp,Event,Snore,Apnea,Noise,Media\n".toByteArray())
        } catch (e: Exception) {
            Log.e("ApneaApp", "Failed to create CSV", e)
        }

        if (isAutoRecord) {
            try {
                val fileName = "night_record_$timeStamp.wav"
                currentRecordFile = java.io.File(getExternalFilesDir(null), fileName)
                fileOutputStream = java.io.FileOutputStream(currentRecordFile)
                // WAV Header Platzhalter
                fileOutputStream?.write(ByteArray(44)) 
                Log.i("ApneaApp", "Recording started: ${currentRecordFile?.absolutePath}")
            } catch (e: Exception) {
                Log.e("ApneaApp", "Failed to start recording", e)
            }
        }
        
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        
        // AGC/NS deaktivieren, da sie das Signal für das ML-Modell zu stark verfälschen könnten
        // Wir verlassen uns auf die Volume Augmentation im Training
        
        audioRecord?.startRecording()

        Thread {
            val audioData = ShortArray(bufferSize)
            var totalSamplesWritten = 0L
            
            while (isRecording) {
                val read = audioRecord?.read(audioData, 0, bufferSize) ?: 0
                if (read <= 0) continue

                if (isAutoRecord && fileOutputStream != null) {
                    val byteBuffer = java.nio.ByteBuffer.allocate(read * 2)
                    byteBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) {
                        byteBuffer.putShort(audioData[i])
                    }
                    fileOutputStream?.write(byteBuffer.array())
                    totalSamplesWritten += read
                }

                var sum = 0.0
                for (i in 0 until read) {
                    val sample = audioData[i].toFloat()
                    sum += sample * sample
                    // In Circular Buffer schreiben
                    audioWindow[windowPointer] = sample / 32768.0f
                    windowPointer = (windowPointer + 1) % ML_INPUT_SIZE
                }
                
                val rms = sqrt(sum / read)
                
                // Adaptive Noise Floor Tracking
                noiseFloor = noiseFloor * 0.99 + rms * 0.01
                
                // Wesentlich robuster gegen Hintergrundrauschen
                // Faktor 4.0x und Minimum 300
                val adaptiveThreshold = noiseFloor * 4.0 
                val effectiveSnoreThreshold = Math.min(snoreThreshold, Math.max(300.0, adaptiveThreshold))

                val now = System.currentTimeMillis()
                val timeSinceLastMovement = now - lastMovementTime
                
                val isDeepSleepCandidate = isTestMode || (timeSinceLastMovement > 5 * 60 * 1000L)
                val inCooldown = !isTestMode && (now - lastInterventionTime < cooldownMs)

                if (rms > effectiveSnoreThreshold) {
                    // Heuristik entfernt: Nur ML darf lastSnoreTime updaten
                    // Wir loggen es nur noch als "Geräusch"
                    if (!isMediaBackground) {
                        sendStatusUpdate("AKTIV", "Geräusch erkannt")
                    }
                }

                // Periodischer ML Check alle 3 Sekunden während der Entwicklung, 
                // um Musik/Podcasts im Hintergrund extrem schnell zu erkennen
                if (now - lastMLCheckTime > 3000L) {
                    lastMLCheckTime = now
                    checkBackgroundML()
                }

                if (rms < silenceThreshold) {
                    if (silenceStartTime == 0L) silenceStartTime = now
                    val silenceDuration = now - silenceStartTime
                    val snoreAge = now - lastSnoreTime

                    if (silenceDuration % 1000 < 100) { // Log every second
                        Log.d("ADB_SIGNAL", "SILENCE: duration=${silenceDuration/1000}s, snoreAge=${snoreAge/1000}s, isMedia=$isMediaBackground")
                    }

                    val statusMsg = if (inCooldown) "Abklingphase..." else "Stille: ${silenceDuration/1000}s"
                    sendStatusUpdate("AKTIV", statusMsg)

                    // KRITISCHE LOGIK: Alarm NUR wenn:
                    // 1. Stille lang genug
                    // 2. Schnarchen VORHER erkannt wurde (innerhalb der letzten 60s)
                    // 3. KEINE Bewegung erkannt wurde
                    // 4. KEIN Medien-Hintergrund (Musik/Podcast) aktiv ist
                    // 5. Nicht in der Abklingphase
                    
                    val isMediaBlocked = isMediaBackground && !isTestMode
                    
                    if (silenceDuration >= triggerDurationMs && 
                        snoreAge < SNORE_MEMORY_MS && 
                        isDeepSleepCandidate && 
                        !inCooldown && 
                        !isMediaBlocked) {
                        
                        runMLAndTrigger()
                    }
                } else {
                    silenceStartTime = 0L 
                }
            }
            audioRecord?.stop()
            audioRecord?.release()
            
            if (fileOutputStream != null) {
                try {
                    fileOutputStream?.close()
                    fixWavHeader(currentRecordFile, totalSamplesWritten)
                    Log.i("ApneaApp", "Recording stopped and header fixed. Samples: $totalSamplesWritten")
                } catch (e: Exception) {
                    Log.e("ApneaApp", "Failed to finalize recording", e)
                }
            }
            
            stopSelf()
        }.start()
    }

    private fun fixWavHeader(file: java.io.File?, totalSamples: Long) {
        if (file == null || !file.exists()) return
        val totalDataLen = totalSamples * 2
        val totalAudioLen = totalDataLen + 36
        val byteRate = (16 * sampleRate * 1) / 8

        val raf = java.io.RandomAccessFile(file, "rw")
        raf.seek(0)
        val header = ByteArray(44)
        
        // RIFF header
        header[0] = 'R'.toByte(); header[1] = 'I'.toByte(); header[2] = 'F'.toByte(); header[3] = 'F'.toByte()
        header[4] = (totalAudioLen and 0xff).toByte()
        header[5] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[6] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[7] = ((totalAudioLen shr 24) and 0xff).toByte()
        header[8] = 'W'.toByte(); header[9] = 'A'.toByte(); header[10] = 'V'.toByte(); header[11] = 'E'.toByte()
        
        // fmt chunk
        header[12] = 'f'.toByte(); header[13] = 'm'.toByte(); header[14] = 't'.toByte(); header[15] = ' '.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // size of fmt chunk
        header[20] = 1; header[21] = 0 // format (PCM)
        header[22] = 1; header[23] = 0 // channels (mono)
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2; header[33] = 0 // block align
        header[34] = 16; header[35] = 0 // bits per sample
        
        // data chunk
        header[36] = 'd'.toByte(); header[37] = 'a'.toByte(); header[38] = 't'.toByte(); header[39] = 'a'.toByte()
        header[40] = (totalDataLen and 0xff).toByte()
        header[41] = ((totalDataLen shr 8) and 0xff).toByte()
        header[42] = ((totalDataLen shr 16) and 0xff).toByte()
        header[43] = ((totalDataLen shr 24) and 0xff).toByte()
        
        raf.write(header)
        raf.close()
    }

    private fun isSystemMediaActive(): Boolean {
        if (isTestMode) return false // Bypass für automatisierte Audio-Tests
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.isMusicActive
    }

    private fun checkBackgroundML() {
        if (tflite == null) return
        
        // FAIL-SAFE: Wenn das System sagt, es läuft Musik/Podcast, dann ML überspringen
        if (isSystemMediaActive()) {
            isMediaBackground = true
            lastSnoreTime = 0L
            Log.i("ADB_SIGNAL", "BACKGROUND_ML: System reports active media, locking interventions")
            sendStatusUpdate("AKTIV", "Hintergrund: Medien (System)")
            return
        }

        val rawInput = FloatArray(ML_INPUT_SIZE)
        var maxAbs = 0.0f
        for (i in 0 until ML_INPUT_SIZE) {
            val sample = audioWindow[(windowPointer + i) % ML_INPUT_SIZE]
            rawInput[i] = sample
            val absVal = Math.abs(sample)
            if (absVal > maxAbs) maxAbs = absVal
        }

        // Peak-Normalisierung wie im Training
        val inferenceInput = FloatArray(ML_INPUT_SIZE)
        if (maxAbs > 0.0001f) {
            for (i in 0 until ML_INPUT_SIZE) {
                inferenceInput[i] = rawInput[i] / maxAbs
            }
        } else {
            System.arraycopy(rawInput, 0, inferenceInput, 0, ML_INPUT_SIZE)
        }

        val output = Array(1) { FloatArray(4) }
        tflite?.run(arrayOf(inferenceInput), output)
        
        val classes = listOf("Snore", "Apnea", "Noise", "Media")
        val snoreScore = output[0][0]
        val apneaScore = output[0][1]
        val noiseScore = output[0][2]
        val mediaScore = output[0][3]

        // EXTREM-LOGGING für die Entwicklung
        Log.i("ADB_SIGNAL", "ML_SCORES: Snore=${String.format("%.3f", snoreScore)}, Apnea=${String.format("%.3f", apneaScore)}, Noise=${String.format("%.3f", noiseScore)}, Media=${String.format("%.3f", mediaScore)}")

        val timestamp = System.currentTimeMillis()
        try {
            csvOutputStream?.write(("$timestamp,ML_BG,${String.format(java.util.Locale.US, "%.4f", snoreScore)},${String.format(java.util.Locale.US, "%.4f", apneaScore)},${String.format(java.util.Locale.US, "%.4f", noiseScore)},${String.format(java.util.Locale.US, "%.4f", mediaScore)}\n").toByteArray())
        } catch (e: Exception) {}

        val sortedIndices = output[0].indices.sortedByDescending { output[0][it] }
        val topScore = output[0][sortedIndices[0]]

        // Sehr aggressive Medien-Erkennung: 
        // 1. Media-Score > 15% (Reduced from 25%)
        // 2. Media in den Top 2 Vorhersagen
        // 3. ODER: Wenn das Modell extrem unsicher ist (Top-Score < 40%) und Media > 10%
        val isLikelyMedia = (mediaScore > 0.15f) || 
                            (sortedIndices[0] == 3) || 
                            (sortedIndices[1] == 3) ||
                            (topScore < 0.40f && mediaScore > 0.10f)


        if (isLikelyMedia) {
            isMediaBackground = true
            if (!isTestMode) lastSnoreTime = 0L 
            Log.i("ADB_SIGNAL", "BACKGROUND_ML: Media detected/suspected (M=${String.format("%.3f", mediaScore)}), locking interventions")
            sendStatusUpdate("AKTIV", "Hintergrund: Medien")
        } else {
            // Zurücksetzen, wenn Media-Score SEHR niedrig ist 
            // ODER wenn Modell sicher ist, dass es Apnoe/Noise ist
            if (mediaScore < 0.02f || (topScore > 0.85f && (sortedIndices[0] == 1 || sortedIndices[0] == 2) && mediaScore < 0.10f)) {
                if (isMediaBackground) {
                    Log.i("ADB_SIGNAL", "BACKGROUND_ML: Media lock released (M=${String.format("%.3f", mediaScore)}, Top=${classes[sortedIndices[0]]})")
                }
                isMediaBackground = false
            }
            
            // NEU: Wenn ML Schnarchen erkennt, lastSnoreTime updaten
            // Nur bei hoher Konfidenz (0.80 statt 0.70)
            if (snoreScore > 0.80f) {
                lastSnoreTime = System.currentTimeMillis()
                Log.d("ADB_SIGNAL", "ML_DETECT: Snore confirmed via ML (Score=${String.format("%.2f", snoreScore)})")
                sendStatusUpdate("AKTIV", "Schnarchen (ML)")
            }
        }
    }

    private fun runMLAndTrigger() {
        if (tflite == null) {
            triggerIntervention("ML Modell fehlt")
            return
        }

        val rawInput = FloatArray(ML_INPUT_SIZE)
        var maxAbs = 0.0f
        for (i in 0 until ML_INPUT_SIZE) {
            val sample = audioWindow[(windowPointer + i) % ML_INPUT_SIZE]
            rawInput[i] = sample
            val absVal = Math.abs(sample)
            if (absVal > maxAbs) maxAbs = absVal
        }

        // Peak-Normalisierung wie im Training
        val inferenceInput = FloatArray(ML_INPUT_SIZE)
        if (maxAbs > 0.0001f) {
            for (i in 0 until ML_INPUT_SIZE) {
                inferenceInput[i] = rawInput[i] / maxAbs
            }
        } else {
            System.arraycopy(rawInput, 0, inferenceInput, 0, ML_INPUT_SIZE)
        }

        val output = Array(1) { FloatArray(4) }
        tflite?.run(arrayOf(inferenceInput), output)
        
        val classes = listOf("Snore", "Apnea", "Noise", "Media")
        val snoreScore = output[0][0]
        val apneaScore = output[0][1]
        val noiseScore = output[0][2]
        val mediaScore = output[0][3]

        Log.i("ADB_SIGNAL", "ML_FINAL_RESULT: S=${String.format("%.2f", snoreScore)}, A=${String.format("%.2f", apneaScore)}, N=${String.format("%.2f", noiseScore)}, M=${String.format("%.2f", mediaScore)}")

        val sortedIndices = output[0].indices.sortedByDescending { output[0][it] }
        val topClass = classes[sortedIndices[0]]
        val topScore = output[0][sortedIndices[0]]

        // Alarm nur wenn Apnea führt UND Media-Score extrem niedrig ist
        if (topClass == "Apnea" && topScore > 0.40f && mediaScore < 0.15f && !isMediaBackground) {
            triggerIntervention("ML: Apnea confirmed")
        } else if (mediaScore > 0.15f || isMediaBackground) {
            isMediaBackground = true
            silenceStartTime = 0L // Reset silence to avoid immediate re-trigger
            Log.i("ADB_SIGNAL", "TRIGGER_BLOCKED: Media suspected (M=${String.format("%.3f", mediaScore)})")
            sendStatusUpdate("AKTIV", "Gesperrt (Medien-Verdacht)")
        } else {
            sendStatusUpdate("AKTIV", "ML: $topClass")
        }
    }

    private fun triggerIntervention(reason: String) {
        Log.i("ADB_SIGNAL", "ALARM: START - Reason: $reason")
        audioIntervention.triggerAlarm(alarmVolume)
        isAlarmRunning = true
        lastInterventionTime = System.currentTimeMillis()
        sendStatusUpdate("ALARM!", reason)
        silenceStartTime = System.currentTimeMillis() 

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isAlarmRunning) {
                Log.i("ADB_SIGNAL", "ALARM: STOP")
                audioIntervention.stopAlarm()
                isAlarmRunning = false
                sendStatusUpdate("AKTIV", "Abklingphase (${cooldownMs/(60*1000)}m)")
            }
        }, 3000)
    }

    private fun sendStatusUpdate(status: String, detail: String) {
        val intent = Intent("APNEA_STATUS_UPDATE").apply {
            putExtra("EXTRA_STATUS", status)
            putExtra("EXTRA_DETAIL", detail)
            putExtra("EXTRA_ALARM_RUNNING", isAlarmRunning)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val acceleration = sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2])
            if (Math.abs(acceleration - 9.81) > 0.5) {
                lastMovementTime = System.currentTimeMillis()
                Log.d("ADB_SIGNAL", "TYPE=MOVEMENT")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel("apnea_channel", "Apnea Monitoring Channel", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        isRecording = false
        audioIntervention.stopAlarm()
        testMediaPlayer?.release()
        
        try {
            csvOutputStream?.close()
        } catch (e: Exception) {}
        
        if (wakeLock?.isHeld == true) wakeLock?.release()
        sensorManager.unregisterListener(this)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(settingsReceiver)
        sendStatusUpdate("BEREIT", "Gestoppt")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
