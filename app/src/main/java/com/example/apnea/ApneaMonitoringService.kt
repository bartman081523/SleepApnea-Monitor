package com.example.apnea

import android.app.*
import android.content.*
import android.hardware.*
import android.media.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class ApneaMonitoringService : Service(), SensorEventListener {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var wakeLock: PowerManager.WakeLock? = null
    
    // MediaRecorder for storage-efficient night log (AAC/M4A)
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordFile: File? = null

    // CSV Logging
    private var csvOutputStream: FileOutputStream? = null
    private var currentCsvFile: File? = null

    private lateinit var audioIntervention: AudioIntervention
    private lateinit var sensorManager: SensorManager
    private var lastMovementTime = System.currentTimeMillis()
    private var lastSnoreTime = 0L
    private var lastInterventionTime = 0L

    private var noiseFloor = 100.0
    private var silCounter = 0
    
    // Dynamic Settings
    private var isTestMode = false
    private var isAutoRecord = false
    private var alarmVolume = 50
    private var silenceThreshold = 250.0
    private var snoreThreshold = 1200.0
    private var triggerDurationMs = 12000L
    private var cooldownMs = 15 * 60 * 1000L
    private var alarmDurationMs = 3000L
    private var isAlarmRunning = false

    private var apneaWeightOffset = 0f
    private var serviceStartTime = 0L
    private var questionnaireTriggered = false

    private val ML_INPUT_SIZE = 80000 

    private var mlInterpreter: org.tensorflow.lite.Interpreter? = null
    private val classes = arrayOf("Snore", "Apnea", "Noise", "Media")

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                alarmVolume = it.getIntExtra("EXTRA_VOLUME", alarmVolume)
                silenceThreshold = it.getDoubleExtra("EXTRA_SILENCE_THRESHOLD", silenceThreshold)
                snoreThreshold = it.getDoubleExtra("EXTRA_SNORE_THRESHOLD", snoreThreshold)
                triggerDurationMs = it.getLongExtra("EXTRA_TRIGGER_DURATION", triggerDurationMs)
                cooldownMs = it.getLongExtra("EXTRA_COOLDOWN", cooldownMs)
                alarmDurationMs = it.getLongExtra("EXTRA_ALARM_DURATION", alarmDurationMs)
                Log.d("ApneaApp", "Settings updated in Service")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioIntervention = AudioIntervention(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(settingsReceiver, IntentFilter("APNEA_SETTINGS_UPDATE"))
        loadModel()
    }

    private fun loadModel() {
        try {
            val assetFileDescriptor = assets.openFd("apnea_model_quantized.tflite")
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.length
            val modelBuffer = fileChannel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            mlInterpreter = org.tensorflow.lite.Interpreter(modelBuffer)
        } catch (e: Exception) {
            Log.e("ApneaApp", "Model load failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_ALARM") {
            stopAlarm()
            return START_STICKY
        }

        isTestMode = intent?.getBooleanExtra("EXTRA_TEST_MODE", false) ?: false
        isAutoRecord = intent?.getBooleanExtra("EXTRA_AUTO_RECORD", false) ?: false
        alarmVolume = intent?.getIntExtra("EXTRA_VOLUME", 50) ?: 50
        silenceThreshold = intent?.getDoubleExtra("EXTRA_SILENCE_THRESHOLD", 250.0) ?: 250.0
        snoreThreshold = intent?.getDoubleExtra("EXTRA_SNORE_THRESHOLD", 1200.0) ?: 1200.0
        triggerDurationMs = intent?.getLongExtra("EXTRA_TRIGGER_DURATION", 12000L) ?: 12000L
        cooldownMs = intent?.getLongExtra("EXTRA_COOLDOWN", 3 * 60 * 1000L) ?: (3 * 60 * 1000L)
        alarmDurationMs = intent?.getLongExtra("EXTRA_ALARM_DURATION", 3000L) ?: 3000L

        val prefs = getSharedPreferences("ApneaPrefs", Context.MODE_PRIVATE)
        apneaWeightOffset = prefs.getFloat("apnea_weight_offset", 0f)
        serviceStartTime = System.currentTimeMillis()
        questionnaireTriggered = false

        startForeground(1, createNotification())
        startMonitoring()

        
        return START_STICKY
    }

    private fun startMonitoring() {
        isRecording = true
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ApneaApp:Lock")
        wakeLock?.acquire()
        
        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
        
        // 1. CSV Initialization
        try {
            val csvName = "night_data_$timeStamp.csv"
            currentCsvFile = File(getExternalFilesDir(null), csvName)
            csvOutputStream = FileOutputStream(currentCsvFile)
            // Save active settings in header for the "Self-Optimization Brain"
            val meta = "#SETTINGS:vol=$alarmVolume,sil=$silenceThreshold,sno=$snoreThreshold,tri=$triggerDurationMs,dur=$alarmDurationMs,test=$isTestMode\n"
            csvOutputStream?.write(meta.toByteArray())
            csvOutputStream?.write("Timestamp,Event,Snore,Apnea,Noise,Media\n".toByteArray())
        } catch (e: Exception) { Log.e("ApneaApp", "CSV Init failed", e) }

        // 2. AAC Recording (Storage Efficient)
        if (isAutoRecord && !isTestMode) {
            try {
                currentRecordFile = File(getExternalFilesDir(null), "night_record_$timeStamp.m4a")
                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
                mediaRecorder?.apply {
                    setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(16000)
                    setAudioChannels(1)
                    setAudioEncodingBitRate(32000)
                    setOutputFile(currentRecordFile?.absolutePath)
                    prepare()
                    start()
                }
            } catch (e: Exception) { Log.e("ApneaApp", "MediaRecorder failed", e) }
        }

        // 3. AudioRecord for ML Analysis
        Thread {
            val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize.coerceAtLeast(ML_INPUT_SIZE * 2))
            this.audioRecord = audioRecord
            
            val audioData = ShortArray(1600) // 100ms chunks
            val circularBuffer = ShortArray(ML_INPUT_SIZE)
            var bufferIndex = 0

            audioRecord.startRecording()
            sendStatusUpdate("AKTIV", "Warte auf Ereignisse")

            while (isRecording) {
                val read = audioRecord.read(audioData, 0, audioData.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) {
                        val s = audioData[i]
                        sum += s * s
                        circularBuffer[bufferIndex] = s
                        bufferIndex = (bufferIndex + 1) % ML_INPUT_SIZE
                    }
                    
                    val rms = sqrt(sum / read)
                    noiseFloor = noiseFloor * 0.999 + rms * 0.01 // Very slow noise floor tracking
                    
                    val adaptiveThreshold = noiseFloor * 4.0 
                    val effectiveSnoreThreshold = Math.min(snoreThreshold, Math.max(300.0, adaptiveThreshold))

                    val now = System.currentTimeMillis()
                    val timeSinceLastMovement = now - lastMovementTime
                    val isDeepSleepCandidate = isTestMode || (timeSinceLastMovement > 5 * 60 * 1000L)
                    val inCooldown = !isTestMode && (now - lastInterventionTime < cooldownMs)

                    if (rms < silenceThreshold) {
                        silCounter += read
                        val silenceDuration = (silCounter.toDouble() / 16000.0) * 1000.0
                        val snoreAge = now - lastSnoreTime
                        
                        if (silenceDuration >= triggerDurationMs && snoreAge < 60000L && isDeepSleepCandidate && !inCooldown && !isAlarmRunning) {
                            runMLAndTrigger(circularBuffer)
                        }
                    } else {
                        silCounter = 0
                        if (rms > effectiveSnoreThreshold && !isAlarmRunning) {
                            checkBackgroundML(circularBuffer)
                        }
                    }
                }
                Thread.sleep(50)
            }
            audioRecord.stop()
            audioRecord.release()
        }.start()
    }

    private fun checkBackgroundML(buffer: ShortArray) {
        val rawInput = FloatArray(ML_INPUT_SIZE)
        var maxAbs = 0f
        for (i in 0 until ML_INPUT_SIZE) {
            rawInput[i] = buffer[i].toFloat()
            if (Math.abs(rawInput[i]) > maxAbs) maxAbs = Math.abs(rawInput[i])
        }
        val inferenceInput = FloatArray(ML_INPUT_SIZE)
        if (maxAbs > 0.1f) {
            for (i in 0 until ML_INPUT_SIZE) inferenceInput[i] = rawInput[i] / maxAbs
        } else { return }

        val output = Array(1) { FloatArray(classes.size) }
        mlInterpreter?.run(arrayOf(inferenceInput), output)
        
        val snoreScore = output[0][0]
        val apneaScore = output[0][1]
        val noiseScore = output[0][2]
        val mediaScore = output[0][3]

        val timestamp = System.currentTimeMillis()
        try {
            csvOutputStream?.write(("$timestamp,ML_BG,${String.format(java.util.Locale.US, "%.3f", snoreScore)},${String.format(java.util.Locale.US, "%.3f", apneaScore)},${String.format(java.util.Locale.US, "%.3f", noiseScore)},${String.format(java.util.Locale.US, "%.3f", mediaScore)}\n").toByteArray())
        } catch (e: Exception) {}

        val confirmThresh = if (isTestMode) 0.15f else 0.80f
        val effectiveApneaThresh = 0.40f + apneaWeightOffset
        
        if (snoreScore > confirmThresh) {
            lastSnoreTime = System.currentTimeMillis()
            sendStatusUpdate("AKTIV", "Schnarchen (KI)")
        } else if (apneaScore > effectiveApneaThresh && !isAlarmRunning && (System.currentTimeMillis() - lastSnoreTime < 60000L)) {
            triggerIntervention("ML Distress erkannt")
        }
    }

    private fun runMLAndTrigger(buffer: ShortArray) {
        val rawInput = FloatArray(ML_INPUT_SIZE)
        var maxAbs = 0f
        for (i in 0 until ML_INPUT_SIZE) {
            rawInput[i] = buffer[i].toFloat()
            if (Math.abs(rawInput[i]) > maxAbs) maxAbs = Math.abs(rawInput[i])
        }
        val inferenceInput = FloatArray(ML_INPUT_SIZE)
        if (maxAbs > 0.0001f) {
            for (i in 0 until ML_INPUT_SIZE) inferenceInput[i] = rawInput[i] / maxAbs
        } else {
            for (i in 0 until ML_INPUT_SIZE) inferenceInput[i] = 0f
        }

        val output = Array(1) { FloatArray(classes.size) }
        mlInterpreter?.run(arrayOf(inferenceInput), output)
        
        val snoreScore = output[0][0]
        val apneaScore = output[0][1]
        val noiseScore = output[0][2]
        val mediaScore = output[0][3]

        val timestamp = System.currentTimeMillis()
        try {
            csvOutputStream?.write(("$timestamp,ML_TRIGGER,${String.format(java.util.Locale.US, "%.3f", snoreScore)},${String.format(java.util.Locale.US, "%.3f", apneaScore)},${String.format(java.util.Locale.US, "%.3f", noiseScore)},${String.format(java.util.Locale.US, "%.3f", mediaScore)}\n").toByteArray())
        } catch (e: Exception) {}

        val effectiveApneaThresh = 0.40f + apneaWeightOffset
        if (apneaScore > effectiveApneaThresh && mediaScore < 0.15f) {
            triggerIntervention("ML: Apnoe bestätigt")
        }
    }

    private fun triggerIntervention(reason: String) {
        val timestamp = System.currentTimeMillis()
        try {
            csvOutputStream?.write(("$timestamp,ALARM_START,0,0,0,0\n").toByteArray())
        } catch (e: Exception) {}

        Log.i("ADB_SIGNAL", "ALARM: START - Reason: $reason")
        audioIntervention.triggerAlarm(alarmVolume)
        isAlarmRunning = true
        lastInterventionTime = System.currentTimeMillis()
        sendStatusUpdate("ALARM!", reason)
        
        // Auto-Stop alarm after set duration
        Handler(Looper.getMainLooper()).postDelayed({
            stopAlarm()
        }, alarmDurationMs)
    }

    private fun stopAlarm() {
        if (!isAlarmRunning) return
        audioIntervention.stopAlarm()
        isAlarmRunning = false
        silCounter = 0
        sendStatusUpdate("AKTIV", "Alarm beendet")
    }

    private fun createNotification(): Notification {
        val channelId = "ApneaServiceChannel"
        val channel = NotificationChannel(channelId, "Apnea Monitoring", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Apnoe Wächter läuft")
            .setContentText("Überwachung ist aktiv...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
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
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val accel = sqrt(x*x + y*y + z*z)
            if (accel > 11.5) {
                lastMovementTime = System.currentTimeMillis()
                
                // Trigger Questionnaire if monitored > 3h and not already triggered
                val duration = System.currentTimeMillis() - serviceStartTime
                if (duration > 3 * 60 * 60 * 1000L && !questionnaireTriggered && !isTestMode) {
                    questionnaireTriggered = true
                    showQuestionnaireNotification()
                }
            }
        }
    }

    private fun showQuestionnaireNotification() {
        val channelId = "ApneaServiceChannel"
        val intent = Intent(this, QuestionnaireActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.q_title))
            .setContentText(getString(R.string.q_prompt))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2, notification)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onDestroy() {
        isRecording = false
        audioIntervention.stopAlarm()
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {}
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
