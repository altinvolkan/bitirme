package com.example.screenmirror

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var hostField: EditText
    private lateinit var button: Button
    private var port = Protocol.DEFAULT_PORT
    private lateinit var discovery: TvDiscovery

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            val host = hostField.text.toString().trim()
            val svc = Intent(this, MirrorService::class.java)
                .putExtra(MirrorService.EXTRA_RESULT_CODE, r.resultCode)
                .putExtra(MirrorService.EXTRA_DATA, r.data)
                .putExtra(MirrorService.EXTRA_HOST, host)
                .putExtra(MirrorService.EXTRA_PORT, port)
            startForegroundService(svc)
            status.text = "Yansıtılıyor → $host:$port"
        } else {
            status.text = "Ekran yakalama izni verilmedi"
        }
        refreshButton()
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }
        status = TextView(this).apply {
            textSize = 16f
            text = "Apple TV aranıyor…"
        }
        hostField = EditText(this).apply {
            hint = "Apple TV IP (otomatik dolar, gerekirse elle gir)"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        button = Button(this).apply {
            setOnClickListener { onButton() }
        }
        root.addView(status)
        root.addView(hostField)
        root.addView(button)
        setContentView(root)

        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        discovery = TvDiscovery(this) { name, host, p ->
            port = p
            if (host != null) {
                hostField.setText(host)
                status.text = "Bulundu: $name ($host:$p)"
            } else {
                status.text = "Bulundu: $name ama IPv4 çözülemedi; TV ekranındaki IP'yi elle gir"
            }
        }
        refreshButton()
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshButton()
    }

    private fun onButton() {
        if (MirrorService.running) {
            startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
            status.text = "Durduruldu"
            button.postDelayed({ refreshButton() }, 300)
            return
        }
        if (hostField.text.isNullOrBlank()) {
            status.text = "Önce Apple TV IP'sini gir veya bulunmasını bekle"
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capture.launch(mpm.createScreenCaptureIntent())
    }

    private fun refreshButton() {
        button.text = if (MirrorService.running) "Durdur" else "Yansıtmayı başlat"
    }
}
