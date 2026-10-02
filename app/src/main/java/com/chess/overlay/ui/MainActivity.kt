package com.chess.overlay.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.chess.overlay.R
import com.chess.overlay.service.ChessOverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvCaptureStatus: TextView
    private lateinit var btnToggle: Button

    private var isServiceRunning = false

    // Activity Result Launcher untuk Media Projection (Screen Capture)
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            tvCaptureStatus.text = "Status Screen Capture: Diizinkan ✓"
            startOverlayService(result.resultCode, result.data)
        } else {
            Toast.makeText(this, "Izin perekaman layar ditolak", Toast.LENGTH_SHORT).show()
            tvCaptureStatus.text = "Status Screen Capture: Ditolak"
        }
    }

    // Activity Result Launcher untuk Overlay Permission
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
            "Status Overlay: Diizinkan ✓"
        } else {
            "Status Overlay: Belum Diizinkan (Ketuk tombol untuk beri izin)"
        }
    }

    private fun checkPermissionsAndStart() {
        // 1. Periksa izin SYSTEM_ALERT_WINDOW
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlaySettingsLauncher.launch(intent)
            Toast.makeText(this, "Berikan izin Draw Over Other Apps terlebih dahulu", Toast.LENGTH_LONG).show()
            return
        }

        // 2. Minta izin Screen Capture via MediaProjection
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startOverlayService(resultCode: Int, data: Intent?) {
        val serviceIntent = Intent(this, ChessOverlayService::class.java).apply {
            putExtra(ChessOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ChessOverlayService.EXTRA_RESULT_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        isServiceRunning = true
        btnToggle.text = getString(R.string.btn_stop_service)
        btnToggle.setBackgroundColor(getColor(R.color.threat_arrow))

        Toast.makeText(this, "Overlay aktif! Buka aplikasi catur dan klik bubble SCAN", Toast.LENGTH_LONG).show()
        // Minimize app to allow user to open chess app
        moveTaskToBack(true)
    }

    private fun stopOverlayService() {
        val serviceIntent = Intent(this, ChessOverlayService::class.java)
        stopService(serviceIntent)
        isServiceRunning = false
        btnToggle.text = getString(R.string.btn_start_service)
        btnToggle.setBackgroundColor(getColor(R.color.accent))
    }
}
