package eu.sisik.backgroundcam

import android.os.Environment
import android.Manifest
import android.R
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import eu.sisik.backgroundcam.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var selectedCameraId: String? = null
    private val recordingViewModel: RecordingViewModel by viewModels()

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CamService.ACTION_STOPPED) {
                updateUI(false)
                val videoFilePath = intent?.getStringExtra("videoFilePath")
                if (videoFilePath != null) {
                    Toast.makeText(this@MainActivity, "Recording stopped. File saved at: $videoFilePath", Toast.LENGTH_LONG).show()
                    Log.d("MainActivity", "Recording stopped. File saved at: $videoFilePath")
                } else {
                    Toast.makeText(this@MainActivity, "Recording stopped but file path is null", Toast.LENGTH_LONG).show()
                    Log.d("MainActivity", "Recording stopped but file path is null")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCameraSelector()

        binding.butStart.setOnClickListener {
            if (hasPermissions()) {
                startCam()
            } else {
                requestPermissions()
            }
        }

        binding.butStartPreview.setOnClickListener {
            if (hasPermissions()) {
                startCamWithPreview()
            } else {
                requestPermissions()
            }
        }

        binding.butStop.setOnClickListener {
            stopCam()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, IntentFilter(CamService.ACTION_STOPPED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, IntentFilter(CamService.ACTION_STOPPED))
        }

        recordingViewModel.isRecording.observe(this) { isRecording ->
            updateUI(isRecording)
        }

    }

    fun openWebsite(view: View) {
        val url = "https://haikalrahman.netlify.app/" // Replace with your actual URL
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(url)
        startActivity(intent)
    }

    private fun setupCameraSelector() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cameraManager.cameraIdList
        val cameraList = cameraIds.map { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
            when (lensFacing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Front Camera"
                CameraCharacteristics.LENS_FACING_BACK -> "Back Camera"
                else -> "Unknown Camera"
            }
        }

        val adapter = ArrayAdapter(this, R.layout.simple_spinner_item, cameraList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCameraSelector.adapter = adapter

        binding.spinnerCameraSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedCameraId = cameraIds[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Handle case when nothing is selected, if needed
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stopReceiver)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION) {
            val permissionsMap = permissions.mapIndexed { index, permission ->
                permission to (grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED)
            }.toMap()

            permissionsMap.forEach { (permission, isGranted) ->
                if (isGranted) {
                    Log.d("MainActivity", "Permission Granted: $permission")
                    Toast.makeText(this, "Permission Granted: $permission", Toast.LENGTH_SHORT).show()
                } else {
                    Log.d("MainActivity", "Permission Denied: $permission")
                    Toast.makeText(this, "Permission Denied: $permission", Toast.LENGTH_SHORT).show()
                }
            }

            if (hasPermissions()) {
                startCam()
            } else {
                requestManageExternalStoragePermission()
            }
        }
    }

    private fun startCam() {
        val intent = Intent(this, CamService::class.java).apply {
            action = CamService.ACTION_START
            putExtra("CAMERA_ID", selectedCameraId)
        }
        recordingViewModel.setRecording(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startCamWithPreview() {
        if (hasPermissions()) {
            recordingViewModel.setRecording(true)
            val intent = Intent(this, CamService::class.java).apply {
                action = CamService.ACTION_START_WITH_PREVIEW
                putExtra("CAMERA_ID", selectedCameraId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Recording with preview started", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Recording with preview started")
        } else {
            requestPermissions()
        }
    }

    private fun stopCam() {
        recordingViewModel.setRecording(false)
        stopService(Intent(this, CamService::class.java))
        Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
        Log.d("MainActivity", "Recording stopped")
    }

    private fun hasPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestManageExternalStoragePermission()
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ), REQUEST_PERMISSION)
        }
    }

    private fun requestManageExternalStoragePermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.parse("package:${applicationContext.packageName}")
        startActivityForResult(intent, REQUEST_PERMISSION)
    }

    private fun updateUI(isRecording: Boolean) {
        binding.butStart.isEnabled = !isRecording
        binding.butStart.visibility = if (isRecording) View.GONE else View.VISIBLE
        binding.butStartPreview.isEnabled = !isRecording
        binding.butStartPreview.visibility = if (isRecording) View.GONE else View.VISIBLE
        binding.butStop.isEnabled = isRecording
        binding.butStop.visibility = if (isRecording) View.VISIBLE else View.GONE
    }

    companion object {
        private const val REQUEST_PERMISSION = 201
    }
}
