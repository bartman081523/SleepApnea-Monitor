package com.example.apnea

import android.Manifest
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {
    private val RECORD_AUDIO_REQUEST_CODE = 101
    
    companion object {
        var status = "BEREIT"
        var detail = "Warte auf Start..."
        var isAlarmRunning = false
        var logContent = "Log: ---"
        
        var vol = 50
        var sil = 250
        var sno = 1200
        var tri = 12
        var coo = 3
        var alarmDur = 3
        var isAutoRecord = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loadSettings()

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 3
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> DashboardFragment()
                    1 -> SettingsFragment()
                    else -> HistoryFragment()
                }
            }
        }

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_dashboard)
                1 -> getString(R.string.tab_settings)
                else -> getString(R.string.tab_history)
            }
        }.attach()

        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, IntentFilter("APNEA_STATUS_UPDATE"))
        checkForUpdates()
    }

    private fun checkForUpdates() {
        Thread {
            try {
                val url = java.net.URL("https://api.github.com/repos/bartman081523/Schlafapnoe-Wachter/releases/latest")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(response)
                    val latestTag = json.getString("tag_name")
                    val currentVersion = "v0.1.8" // Finalizing v0.1.8
                    if (latestTag > currentVersion) {
                        runOnUiThread { showUpdateDialog(latestTag) }
                    }
                }
            } catch (e: Exception) { Log.e("ApneaApp", "Update check failed", e) }
        }.start()
    }

    private fun showUpdateDialog(tag: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Update Verfügbar: $tag")
            .setMessage("Eine neue Version der App ist verfügbar.")
            .setPositiveButton("Download") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/bartman081523/Schlafapnoe-Wachter/releases/latest"))
                startActivity(intent)
            }
            .setNegativeButton("Später", null).show()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status = intent?.getStringExtra("EXTRA_STATUS") ?: ""
            detail = intent?.getStringExtra("EXTRA_DETAIL") ?: ""
            isAlarmRunning = intent?.getBooleanExtra("EXTRA_ALARM_RUNNING", false) ?: false
            LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(Intent("UI_REFRESH"))
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("ApneaPrefs", MODE_PRIVATE)
        vol = prefs.getInt("volume", 50)
        sil = prefs.getInt("silence", 250)
        sno = prefs.getInt("snore", 1200)
        tri = prefs.getInt("trigger", 12)
        coo = prefs.getInt("cooldown", 3)
        alarmDur = prefs.getInt("alarm_duration", 3)
        isAutoRecord = prefs.getBoolean("auto_record", false)
    }

    fun saveSettings() {
        val prefs = getSharedPreferences("ApneaPrefs", MODE_PRIVATE)
        prefs.edit().apply {
            putInt("volume", vol); putInt("silence", sil); putInt("snore", sno)
            putInt("trigger", tri); putInt("cooldown", coo); putInt("alarm_duration", alarmDur)
            putBoolean("auto_record", isAutoRecord); apply()
        }
        val intent = Intent("APNEA_SETTINGS_UPDATE").apply {
            putExtra("EXTRA_VOLUME", vol); putExtra("EXTRA_SILENCE_THRESHOLD", sil.toDouble())
            putExtra("EXTRA_SNORE_THRESHOLD", sno.toDouble()); putExtra("EXTRA_TRIGGER_DURATION", tri * 1000L)
            putExtra("EXTRA_COOLDOWN", coo * 60 * 1000L); putExtra("EXTRA_ALARM_DURATION", alarmDur * 1000L)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun checkPermissionsAndStart(isTestMode: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE)
        } else {
            val serviceIntent = Intent(this, ApneaMonitoringService::class.java).apply {
                putExtra("EXTRA_TEST_MODE", isTestMode)
                putExtra("EXTRA_AUTO_RECORD", isAutoRecord)
                putExtra("EXTRA_VOLUME", vol); putExtra("EXTRA_SILENCE_THRESHOLD", sil.toDouble())
                putExtra("EXTRA_SNORE_THRESHOLD", sno.toDouble()); putExtra("EXTRA_TRIGGER_DURATION", tri * 1000L)
                putExtra("EXTRA_COOLDOWN", coo * 60 * 1000L); putExtra("EXTRA_ALARM_DURATION", alarmDur * 1000L)
            }
            startForegroundService(serviceIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }
}

class DashboardFragment : Fragment() {
    private lateinit var statusText: TextView; private lateinit var detailStatusText: TextView
    private lateinit var btnStopAlarm: Button; private lateinit var btnStart: Button
    private lateinit var btnTest: Button; private lateinit var switchAutoRecord: com.google.android.material.switchmaterial.SwitchMaterial
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updateUI() }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_dashboard, container, false)
        statusText = view.findViewById(R.id.statusText); detailStatusText = view.findViewById(R.id.detailStatusText)
        btnStart = view.findViewById(R.id.btnStart); btnTest = view.findViewById(R.id.btnTest)
        btnStopAlarm = view.findViewById(R.id.btnStopAlarm); switchAutoRecord = view.findViewById(R.id.switchAutoRecord)
        switchAutoRecord.isChecked = MainActivity.isAutoRecord
        switchAutoRecord.setOnCheckedChangeListener { _, isChecked -> MainActivity.isAutoRecord = isChecked; (activity as MainActivity).saveSettings() }
        btnStart.setOnClickListener { (activity as MainActivity).checkPermissionsAndStart(false) }
        btnTest.setOnClickListener { (activity as MainActivity).checkPermissionsAndStart(true) }
        btnStopAlarm.setOnClickListener { val intent = Intent(activity, ApneaMonitoringService::class.java).apply { action = "ACTION_STOP_ALARM" }; activity?.startForegroundService(intent) }
        view.findViewById<Button>(R.id.btnStop).setOnClickListener { activity?.stopService(Intent(activity, ApneaMonitoringService::class.java)); MainActivity.status = "BEREIT"; updateUI() }
        view.findViewById<Button>(R.id.btnExit).setOnClickListener { activity?.stopService(Intent(activity, ApneaMonitoringService::class.java)); activity?.finish() }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(refreshReceiver, IntentFilter("UI_REFRESH"))
        updateUI(); return view
    }
    private fun updateUI() {
        statusText.text = MainActivity.status; detailStatusText.text = MainActivity.detail
        btnStopAlarm.visibility = if (MainActivity.isAlarmRunning) View.VISIBLE else View.GONE
        val running = !(MainActivity.status == "BEREIT" || MainActivity.status == "Gestoppt")
        btnStart.isEnabled = !running; btnTest.isEnabled = !running
    }
    override fun onDestroyView() { super.onDestroyView(); LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(refreshReceiver) }
}

class SettingsFragment : Fragment() {
    private lateinit var volBar: SeekBar; private lateinit var volTxt: TextView
    private lateinit var silBar: SeekBar; private lateinit var silTxt: TextView
    private lateinit var snoBar: SeekBar; private lateinit var snoTxt: TextView
    private lateinit var triBar: SeekBar; private lateinit var triTxt: TextView
    private lateinit var cooBar: SeekBar; private lateinit var cooTxt: TextView
    private lateinit var durBar: SeekBar; private lateinit var durTxt: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        volBar = view.findViewById(R.id.volumeSeekBar); volTxt = view.findViewById(R.id.txtVolumeVal)
        silBar = view.findViewById(R.id.silenceSeekBar); silTxt = view.findViewById(R.id.txtSilenceVal)
        snoBar = view.findViewById(R.id.snoreSeekBar); snoTxt = view.findViewById(R.id.txtSnoreVal)
        triBar = view.findViewById(R.id.triggerSeekBar); triTxt = view.findViewById(R.id.txtTriggerVal)
        cooBar = view.findViewById(R.id.cooldownSeekBar); cooTxt = view.findViewById(R.id.txtCooldownVal)
        durBar = view.findViewById(R.id.alarmDurSeekBar); durTxt = view.findViewById(R.id.txtAlarmDurVal)

        val refresh = {
            volBar.progress = MainActivity.vol; volTxt.text = "${MainActivity.vol}%"
            silBar.progress = MainActivity.sil; silTxt.text = "${MainActivity.sil}"
            snoBar.progress = MainActivity.sno; snoTxt.text = "${MainActivity.sno}"
            triBar.progress = MainActivity.tri; triTxt.text = "${MainActivity.tri}s"
            cooBar.progress = MainActivity.coo; cooTxt.text = "${MainActivity.coo}m"
            durBar.progress = MainActivity.alarmDur; durTxt.text = "${MainActivity.alarmDur}s"
        }
        refresh()

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                when(s.id) {
                    R.id.volumeSeekBar -> { MainActivity.vol = p; volTxt.text = "$p%" }
                    R.id.silenceSeekBar -> { MainActivity.sil = p; silTxt.text = "$p" }
                    R.id.snoreSeekBar -> { MainActivity.sno = p; snoTxt.text = "$p" }
                    R.id.triggerSeekBar -> { MainActivity.tri = p; triTxt.text = "${p}s" }
                    R.id.cooldownSeekBar -> { MainActivity.coo = p; cooTxt.text = "${p}m" }
                    R.id.alarmDurSeekBar -> { MainActivity.alarmDur = p; durTxt.text = "${p}s" }
                }
                (activity as MainActivity).saveSettings()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        listOf(volBar, silBar, snoBar, triBar, cooBar, durBar).forEach { it.setOnSeekBarChangeListener(listener) }
        
        // Listen for internal refresh (from AnalysisActivity)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(object: BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { refresh() }
        }, IntentFilter("UI_REFRESH"))

        view.findViewById<Button>(R.id.btnStartQuestionnaire).setOnClickListener {
            startActivity(Intent(context, QuestionnaireActivity::class.java))
        }

        view.findViewById<Button>(R.id.btnResetSettings).setOnClickListener {
            MainActivity.vol = 50; MainActivity.sil = 250; MainActivity.sno = 1200
            MainActivity.tri = 12; MainActivity.coo = 3; MainActivity.alarmDur = 3
            refresh(); (activity as MainActivity).saveSettings()
        }
        return view
    }
}

class HistoryFragment : Fragment() {
    private lateinit var listView: android.widget.ListView; private lateinit var emptyText: TextView
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_history, container, false)
        listView = view.findViewById(R.id.historyListView); emptyText = view.findViewById(R.id.historyEmptyText)
        loadHistory(); return view
    }
    private fun loadHistory() {
        val dir = context?.getExternalFilesDir(null)
        val files = dir?.listFiles { file -> file.name.startsWith("night_data_") && file.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) { emptyText.visibility = View.VISIBLE; listView.visibility = View.GONE } else {
            emptyText.visibility = View.GONE; listView.visibility = View.VISIBLE
            listView.adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, files.map { it.name })
            listView.setOnItemClickListener { _, _, position, _ ->
                startActivity(Intent(context, AnalysisActivity::class.java).apply { putExtra("EXTRA_CSV_PATH", files[position].absolutePath) })
            }
        }
    }
}
