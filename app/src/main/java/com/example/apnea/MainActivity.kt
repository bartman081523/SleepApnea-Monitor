package com.example.apnea

import android.Manifest
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
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
    
    // Globale Zustände für UI-Sync
    companion object {
        var status = "BEREIT"
        var detail = "Warte auf Start..."
        var isAlarmRunning = false
        var logContent = "Log: ---"
        
        // Settings-Werte
        var vol = 50
        var sil = 250
        var sno = 1200
        var tri = 12
        var coo = 3
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
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status = intent?.getStringExtra("EXTRA_STATUS") ?: ""
            detail = intent?.getStringExtra("EXTRA_DETAIL") ?: ""
            isAlarmRunning = intent?.getBooleanExtra("EXTRA_ALARM_RUNNING", false) ?: false
            logContent = "Log: $status - $detail"
            
            // Fragmente benachrichtigen (via BroadCast oder statischem Refresh)
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
        isAutoRecord = prefs.getBoolean("auto_record", false)
    }

    fun saveSettings() {
        val prefs = getSharedPreferences("ApneaPrefs", MODE_PRIVATE)
        prefs.edit().apply {
            putInt("volume", vol)
            putInt("silence", sil)
            putInt("snore", sno)
            putInt("trigger", tri)
            putInt("cooldown", coo)
            putBoolean("auto_record", isAutoRecord)
            apply()
        }
        
        // Live-Update an Service senden
        val intent = Intent("APNEA_SETTINGS_UPDATE").apply {
            putExtra("EXTRA_VOLUME", vol)
            putExtra("EXTRA_SILENCE_THRESHOLD", sil.toDouble())
            putExtra("EXTRA_SNORE_THRESHOLD", sno.toDouble())
            putExtra("EXTRA_TRIGGER_DURATION", tri * 1000L)
            putExtra("EXTRA_COOLDOWN", coo * 60 * 1000L)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun checkPermissionsAndStart(isTestMode: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST_CODE)
        } else {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                startActivity(intent)
            } else {
                val serviceIntent = Intent(this, ApneaMonitoringService::class.java).apply {
                    putExtra("EXTRA_TEST_MODE", isTestMode)
                    putExtra("EXTRA_AUTO_RECORD", isAutoRecord)
                    putExtra("EXTRA_VOLUME", vol)
                    putExtra("EXTRA_SILENCE_THRESHOLD", sil.toDouble())
                    putExtra("EXTRA_SNORE_THRESHOLD", sno.toDouble())
                    putExtra("EXTRA_TRIGGER_DURATION", tri * 1000L)
                    putExtra("EXTRA_COOLDOWN", coo * 60 * 1000L)
                }
                startForegroundService(serviceIntent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }
}

class DashboardFragment : Fragment() {
    private lateinit var statusText: TextView
    private lateinit var detailStatusText: TextView
    private lateinit var btnStopAlarm: Button
    private lateinit var btnStart: Button
    private lateinit var btnTest: Button
    private lateinit var logDisplay: TextView
    private lateinit var switchAutoRecord: com.google.android.material.switchmaterial.SwitchMaterial

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUI()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_dashboard, container, false)
        statusText = view.findViewById(R.id.statusText)
        detailStatusText = view.findViewById(R.id.detailStatusText)
        btnStart = view.findViewById(R.id.btnStart)
        btnTest = view.findViewById(R.id.btnTest)
        btnStopAlarm = view.findViewById(R.id.btnStopAlarm)
        logDisplay = view.findViewById(R.id.logDisplay)
        switchAutoRecord = view.findViewById(R.id.switchAutoRecord)

        switchAutoRecord.isChecked = MainActivity.isAutoRecord
        switchAutoRecord.setOnCheckedChangeListener { _, isChecked ->
            MainActivity.isAutoRecord = isChecked
            (activity as MainActivity).saveSettings()
        }

        btnStart.setOnClickListener { (activity as MainActivity).checkPermissionsAndStart(false) }
        btnTest.setOnClickListener { (activity as MainActivity).checkPermissionsAndStart(true) }
        btnStopAlarm.setOnClickListener {
            val intent = Intent(activity, ApneaMonitoringService::class.java).apply { action = "ACTION_STOP_ALARM" }
            activity?.startForegroundService(intent)
        }
        view.findViewById<Button>(R.id.btnStop).setOnClickListener {
            activity?.stopService(Intent(activity, ApneaMonitoringService::class.java))
            MainActivity.status = "BEREIT"
            MainActivity.detail = "Wird gestoppt..."
            updateUI()
        }
        view.findViewById<Button>(R.id.btnExit).setOnClickListener {
            activity?.stopService(Intent(activity, ApneaMonitoringService::class.java))
            activity?.finish()
        }

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(refreshReceiver, IntentFilter("UI_REFRESH"))
        updateUI()
        return view
    }

    private fun updateUI() {
        statusText.text = MainActivity.status
        detailStatusText.text = MainActivity.detail
        logDisplay.text = MainActivity.logContent
        btnStopAlarm.visibility = if (MainActivity.isAlarmRunning) View.VISIBLE else View.GONE
        
        val running = !(MainActivity.status == "BEREIT" || MainActivity.status == "Gestoppt")
        btnStart.isEnabled = !running
        btnTest.isEnabled = !running
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(refreshReceiver)
    }
}

class SettingsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        
        val volBar = view.findViewById<SeekBar>(R.id.volumeSeekBar)
        val silBar = view.findViewById<SeekBar>(R.id.silenceSeekBar)
        val snoBar = view.findViewById<SeekBar>(R.id.snoreSeekBar)
        val triBar = view.findViewById<SeekBar>(R.id.triggerSeekBar)
        val cooBar = view.findViewById<SeekBar>(R.id.cooldownSeekBar)

        val volTxt = view.findViewById<TextView>(R.id.txtVolumeVal)
        val silTxt = view.findViewById<TextView>(R.id.txtSilenceVal)
        val snoTxt = view.findViewById<TextView>(R.id.txtSnoreVal)
        val triTxt = view.findViewById<TextView>(R.id.txtTriggerVal)
        val cooTxt = view.findViewById<TextView>(R.id.txtCooldownVal)

        // Initialwerte setzen
        volBar.progress = MainActivity.vol; volTxt.text = "${MainActivity.vol}%"
        silBar.progress = MainActivity.sil; silTxt.text = "${MainActivity.sil}"
        snoBar.progress = MainActivity.sno; snoTxt.text = "${MainActivity.sno}"
        triBar.progress = MainActivity.tri; triTxt.text = "${MainActivity.tri}s"
        cooBar.progress = MainActivity.coo; cooTxt.text = "${MainActivity.coo}m"

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, f: Boolean) {
                when(s.id) {
                    R.id.volumeSeekBar -> { MainActivity.vol = p; volTxt.text = "$p%" }
                    R.id.silenceSeekBar -> { MainActivity.sil = p; silTxt.text = "$p" }
                    R.id.snoreSeekBar -> { MainActivity.sno = p; snoTxt.text = "$p" }
                    R.id.triggerSeekBar -> { MainActivity.tri = p; triTxt.text = "${p}s" }
                    R.id.cooldownSeekBar -> { MainActivity.coo = p; cooTxt.text = "${p}m" }
                }
                (activity as MainActivity).saveSettings()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }

        volBar.setOnSeekBarChangeListener(listener)
        silBar.setOnSeekBarChangeListener(listener)
        snoBar.setOnSeekBarChangeListener(listener)
        triBar.setOnSeekBarChangeListener(listener)
        cooBar.setOnSeekBarChangeListener(listener)

        view.findViewById<Button>(R.id.btnResetSettings).setOnClickListener {
            MainActivity.vol = 50
            MainActivity.sil = 250
            MainActivity.sno = 1200
            MainActivity.tri = 12
            MainActivity.coo = 3
            
            // UI aktualisieren
            volBar.progress = MainActivity.vol; volTxt.text = "${MainActivity.vol}%"
            silBar.progress = MainActivity.sil; silTxt.text = "${MainActivity.sil}"
            snoBar.progress = MainActivity.sno; snoTxt.text = "${MainActivity.sno}"
            triBar.progress = MainActivity.tri; triTxt.text = "${MainActivity.tri}s"
            cooBar.progress = MainActivity.coo; cooTxt.text = "${MainActivity.coo}m"
            
            (activity as MainActivity).saveSettings()
        }

        return view
    }
}

class HistoryFragment : Fragment() {
    private lateinit var listView: android.widget.ListView
    private lateinit var emptyText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_history, container, false)
        listView = view.findViewById(R.id.historyListView)
        emptyText = view.findViewById(R.id.historyEmptyText)

        loadHistory()
        return view
    }

    private fun loadHistory() {
        val dir = context?.getExternalFilesDir(null)
        val files = dir?.listFiles { file -> file.name.startsWith("night_record_") && file.name.endsWith(".wav") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            listView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            listView.visibility = View.VISIBLE
            
            val fileNames = files.map { it.name }
            val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, fileNames)
            listView.adapter = adapter
            
            listView.setOnItemClickListener { _, _, position, _ ->
                val selectedFile = files[position]
                // Here we would parse a corresponding CSV or show a graph.
                android.widget.Toast.makeText(context, "Selected: ${selectedFile.name}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
