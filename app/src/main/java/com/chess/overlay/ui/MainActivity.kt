package com.chess.overlay.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chess.overlay.R
import com.chess.overlay.service.ChessOverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvCaptureStatus: TextView
    private lateinit var btnToggle: Button

    private var isServiceRunning = false

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatuses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvOverlayStatus = findViewById(R.id.tvOverlayPermissionStatus)
        tvCaptureStatus = findViewById(R.id.tvCapturePermissionStatus)
        btnToggle = findViewById(R.id.btnToggleService)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        tvCaptureStatus.text = "Mode Manual Mapping: 100% Akurat (Tanpa Scan Layar)"

        btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopOverlayService()
            } else {
                checkPermissionsAndStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
    }

    private fun updatePermissionStatuses() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        tvOverlayStatus.text = if (hasOverlay) {
            "Status Izin Overlay: Diizinkan ✓"
        } else {
            "Status Izin Overlay: Belum Diizinkan (Ketuk tombol di bawah)"
        }
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlaySettingsLauncher.launch(intent)
            Toast.makeText(this, "Berikan izin Tampilkan di Atas Aplikasi Lain", Toast.LENGTH_LONG).show()
            return
        }

        startOverlayService()
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, ChessOverlayService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        isServiceRunning = true
        btnToggle.text = getString(R.string.btn_stop_service)
        btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.threat_arrow))

        Toast.makeText(this, "Overlay aktif! Buka aplikasi catur Anda.", Toast.LENGTH_LONG).show()
    }

    private fun stopOverlayService() {
        val serviceIntent = Intent(this, ChessOverlayService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        btnToggle.text = getString(R.string.btn_start_service)
        btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.accent))
    }
}
