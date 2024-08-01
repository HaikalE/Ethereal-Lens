package eu.sisik.backgroundcam

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.media.CamcorderProfile
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.view.*
import androidx.core.app.NotificationCompat
import androidx.annotation.RequiresApi
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

class CamService : Service() {

    private var wm: WindowManager? = null
    private var rootView: View? = null
    private var textureView: TextureView? = null

    private var cameraManager: CameraManager? = null
    private var previewSize: Size? = null
    private var cameraDevice: CameraDevice? = null
    private var captureRequest: CaptureRequest? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingSurface: Surface? = null

    private var shouldShowPreview = true
    private var cameraId: String? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f


    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {}
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {}
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        @RequiresApi(Build.VERSION_CODES.N)
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "SurfaceTexture available, width: $width, height: $height")
            initCam(width, height)
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            Log.d(TAG, "SurfaceTexture size changed, width: $width, height: $height")
        }
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            Log.d(TAG, "SurfaceTexture destroyed")
            return true
        }
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            Log.d(TAG, "SurfaceTexture updated")
        }
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader?.acquireLatestImage()
        Log.d(TAG, "Got image: " + image?.width + " x " + image?.height)
        image?.close()
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        @RequiresApi(Build.VERSION_CODES.N)
        override fun onOpened(currentCameraDevice: CameraDevice) {
            cameraDevice = currentCameraDevice
            createCaptureSession()
        }
        override fun onDisconnected(currentCameraDevice: CameraDevice) {
            currentCameraDevice.close()
            cameraDevice = null
        }
        override fun onError(currentCameraDevice: CameraDevice, error: Int) {
            currentCameraDevice.close()
            cameraDevice = null
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                cameraId = intent.getStringExtra("CAMERA_ID")
                start()
            }
            ACTION_START_WITH_PREVIEW -> {
                cameraId = intent.getStringExtra("CAMERA_ID")
                startWithPreview()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }


    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate() {
        super.onCreate()
        startForeground()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        if (rootView != null) wm?.removeView(rootView)
        sendBroadcast(Intent(ACTION_STOPPED).apply {
            putExtra("videoFilePath", videoFilePath)
        })
        Log.d(TAG, "Recording stopped. File saved at: $videoFilePath")
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun start() {
        shouldShowPreview = false
        initCam(320, 200)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun startWithPreview() {
        shouldShowPreview = true
        initOverlay()
        if (textureView!!.isAvailable) initCam(textureView!!.width, textureView!!.height)
        else textureView!!.surfaceTextureListener = surfaceTextureListener
    }



    @RequiresApi(Build.VERSION_CODES.N)
    private fun initOverlay() {
        val li = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        rootView = li.inflate(R.layout.overlay, null)
        textureView = rootView!!.findViewById(R.id.texPreview)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        wm!!.addView(rootView, params)

        rootView!!.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    wm!!.updateViewLayout(rootView, params)
                    true
                }
                else -> false
            }
        }

        if (textureView!!.isAvailable) {
            initCam(textureView!!.width, textureView!!.height)
        } else {
            textureView!!.surfaceTextureListener = surfaceTextureListener
        }
        Log.d(TAG, "Overlay initialized")
    }


    @RequiresApi(Build.VERSION_CODES.N)
    private fun initCam(width: Int, height: Int) {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (cameraId != null) {
            val camCharacteristics = cameraManager!!.getCameraCharacteristics(cameraId!!)
            val map = camCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            previewSize = chooseOptimalSize(map!!.getOutputSizes(SurfaceTexture::class.java), width, height)

            mediaRecorder = MediaRecorder()
            val profile = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH)
            mediaRecorder!!.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder!!.setAudioEncodingBitRate(96000)
            mediaRecorder!!.setAudioSamplingRate(44100)
            mediaRecorder!!.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder!!.setVideoEncodingBitRate(profile.videoBitRate)
            mediaRecorder!!.setVideoFrameRate(profile.videoFrameRate)
            mediaRecorder!!.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
            mediaRecorder!!.setOutputFile(getFilePath())
            mediaRecorder!!.prepare()
            cameraManager!!.openCamera(cameraId!!, stateCallback, null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun createCaptureSession() {
        val texture = if (shouldShowPreview) textureView!!.surfaceTexture else SurfaceTexture(10)
        if (texture != null) {
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
        }
        val previewSurface = Surface(texture)
        recordingSurface = mediaRecorder!!.surface

        val targets = ArrayList<Surface>()
        if (shouldShowPreview) targets.add(previewSurface)
        targets.add(recordingSurface!!)

        val outputConfigs = targets.map { OutputConfiguration(it) }

        val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        for (surface in targets) captureRequestBuilder.addTarget(surface)
        captureRequest = captureRequestBuilder.build()

        cameraDevice!!.createCaptureSessionByOutputConfigurations(outputConfigs, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                try {
                    session.setRepeatingRequest(captureRequest!!, captureCallback, null)
                    mediaRecorder!!.start()
                    Log.d(TAG, "MediaRecorder started")
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Capture session configuration failed")
            }
        }, null)
    }

    private fun stopCamera() {
        captureSession?.close()
        cameraDevice?.close()
        mediaRecorder?.apply {
            stop()
            reset()
            release()
        }
        recordingSurface?.release()
    }

    private fun getFilePath(): String {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (!dir.exists()) dir.mkdirs()
        val path = dir.absolutePath + "/background_cam_${System.currentTimeMillis()}.mp4"
        return path
    }

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        val aspectRatio = width.toDouble() / height.toDouble()
        val bigEnough = choices.filter {
            it.height == it.width * aspectRatio.roundToInt() &&
                    it.width >= width && it.height >= height
        }
        return if (bigEnough.isNotEmpty()) Collections.min(bigEnough, CompareSizesByArea())
        else choices[0]
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun startForeground() {
        val channelId = "CamService"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                channelId,
                "Camera Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationChannel.enableLights(false)
            notificationChannel.enableVibration(false)
            notificationChannel.setShowBadge(false)
            notificationChannel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            notificationChannel.lightColor = Color.BLUE
            notificationChannel.setSound(null, null)

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(notificationChannel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Camera Service")
            .setContentText("Recording video in the background")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(Color.BLUE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    companion object {
        private const val TAG = "CamService"
        const val ACTION_START = "eu.sisik.backgroundcam.action.START"
        const val ACTION_START_WITH_PREVIEW = "eu.sisik.backgroundcam.action.START_WITH_PREVIEW"
        const val ACTION_STOPPED = "eu.sisik.backgroundcam.action.STOPPED"
        private var videoFilePath: String? = null
    }

    class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }
}
