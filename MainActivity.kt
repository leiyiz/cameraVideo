package com.example.videocamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView.SurfaceTextureListener
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.*
import kotlin.random.Random


class MainActivity : AppCompatActivity() {

    lateinit var cameraManager: CameraManager
    var cameraFacing: Int = -1
    var cameraId = ""
    val frameRate = 30
    val samplePixelSize = 1024
    val samplePixel: IntArray = IntArray(samplePixelSize)
    lateinit var previewSize: Size
    lateinit var stateCallback: CameraDevice.StateCallback
    lateinit var backgroundHandler: Handler
    lateinit var backgroundThread: HandlerThread
    lateinit var cameraCaptureSession: CameraCaptureSession
    lateinit var captureRequestBuilder: CaptureRequest.Builder

    var videoStarted = false
    var videoEnded = false
    val data = ArrayList<Int>()
    val frameCycle = 6
    val buffer = ArrayList<Int>()

    val readSize = 16
    val transformer = FFT(readSize)
    private val x = DoubleArray(readSize)
    private val y = DoubleArray(readSize)
    private val p = DoubleArray(readSize / 2)


    var intensityThreshold = 200

    var cameraDevice: CameraDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissions(arrayOf(Manifest.permission.CAMERA), 0)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraFacing = CameraCharacteristics.LENS_FACING_BACK

        stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(cameraDevice: CameraDevice) {
                this@MainActivity.cameraDevice = cameraDevice
                createPreviewSession()
            }

            override fun onDisconnected(cameraDevice: CameraDevice) {
                cameraDevice.close()
                this@MainActivity.cameraDevice = null
            }

            override fun onError(cameraDevice: CameraDevice, error: Int) {
                cameraDevice.close()
                this@MainActivity.cameraDevice = null
            }
        }

        val surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                try {
                    setUpCamera()
                    openCamera()
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                }

            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
//                Log.d("SurfaceTextureUpdated", "the Surface Texture is Updated")
                val frame = Bitmap.createBitmap(camera_feed.width / 16, camera_feed.height / 16, Bitmap.Config.ARGB_8888)
//                Log.d("SurfaceTextureUpdated", "the image width is ${frame.width} and the height is ${frame.height}")
                camera_feed.getBitmap(frame)

                var intensity = 0
                for (i in 0 until frame.width) {
                    for (j in 0 until frame.height) {
                        val pixel = frame.getPixel(i, j)
                        val red = Color.red(pixel)
                        val green = Color.green(pixel)
                        val blue = Color.blue(pixel)
                        intensity += red + green + blue
                    }
                }
                intensity /= frame.width * frame.height
                //TODO: FFT
//                Log.d("SurfaceTextureUpdated", "the color intensity is $intensity")

                if (intensityThreshold == -1)
                    intensityThreshold = intensity

                if (!videoStarted && intensity > intensityThreshold) {
                    videoStarted = true
                    buffer.clear()
                    data.clear()
                    Log.d("SurfaceTextureUpdated", "video has started")
                }

                if (videoStarted && intensity < intensityThreshold) {
                    videoStarted = false
                    videoEnded = true
                }

                if (videoStarted) {
                    buffer.add(intensity)
                }

                if (videoEnded) {
                    Log.d("SurfaceTextureUpdated", "video has ended")
                    Log.d("SurfaceTextureUpdated", "buffer size is ${buffer.size}")
                    videoEnded = false
                    dataCalculation()
                }
            }
        }

        openBackgroundThread()
        if (camera_feed.isAvailable) {
            setUpCamera()
            openCamera()
        } else {
            camera_feed.surfaceTextureListener = surfaceTextureListener
        }

    }

    private fun dataCalculation() {
        Log.d("dataCalculation", "starting to recover data from intensity")

        for (i in 0 until buffer.size / frameCycle) {
            val subBuffer = buffer.subList(i * frameCycle, (i + 1) * frameCycle)
            val avg = subBuffer.sum() / frameCycle
            for (j in 0 until subBuffer.size)
                subBuffer[j] = if (subBuffer[j] > avg) 1 else -1

//            Log.d("dataCalculation", "subbuffer size is ${subBuffer.size}")
            for (j in 0 until readSize) {
                x[j] = subBuffer[j % frameCycle].toDouble()
                y[j] = 0.0
            }
            transformer.transform(x, y)

            for (k in p.indices) p[k] = sqrt(x[k].pow(2) + y[k].pow(2))
            val pos = p.indexOf(p.max()!!)

            data.add(pos)
        }

        val stringBuilder = StringBuilder()

        for (bucket in data)
            stringBuilder.append(if (bucket < 3) '0' else '1')


        Log.d("dataResult", "$stringBuilder and the length is ${stringBuilder.length}")
    }

    override fun onStop() {
        super.onStop()
        closeCamera()
        closeBackgroundThread()
    }

    private fun closeCamera() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close()
//            cameraCaptureSession = null
        }
        if (cameraDevice != null) {
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    private fun closeBackgroundThread() {
        backgroundThread.quitSafely()
//        backgroundThread = null
//        backgroundHandler = null
    }

    private fun openBackgroundThread() {
        backgroundThread = HandlerThread("camera_background_thread")
        backgroundThread.start()
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun createPreviewSession() {
//        try {
        val surfaceTexture: SurfaceTexture = camera_feed.surfaceTexture
        surfaceTexture.setDefaultBufferSize(previewSize.width / 16, previewSize.height / 16)
        val previewSurface = Surface(surfaceTexture)
        captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequestBuilder.addTarget(previewSurface)
        val fps: Range<Int> = Range.create(frameRate, frameRate)
        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)

        cameraDevice!!.createCaptureSession(Collections.singletonList(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        if (cameraDevice == null) {
                            return
                        }
//                            try {
                        val captureRequest = captureRequestBuilder.build()
                        this@MainActivity.cameraCaptureSession = cameraCaptureSession
                        this@MainActivity.cameraCaptureSession.setRepeatingRequest(captureRequest,
                                null, backgroundHandler)
//                            } catch (e: CameraAccessException) {
//                                e.printStackTrace()
//                            }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {}
                }, backgroundHandler)
//        } catch (e: CameraAccessException) {
//            e.printStackTrace()
//        }
    }

    private fun setUpCamera() {
        for (cameraId in cameraManager.cameraIdList) {
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == cameraFacing) {
                val streamConfigurationMap = cameraCharacteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                previewSize = streamConfigurationMap!!.getOutputSizes(SurfaceTexture::class.java)[0]
                this.cameraId = cameraId
            }
        }
    }

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        }
    }

    /** Check if this device has a camera */
    private fun checkCameraHardware(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
    }
}

class FFT(private var size: Int) {
    private var power: Int = (ln(size.toDouble()) / ln(2.0)).toInt()

    private var cos: DoubleArray
    private var sin: DoubleArray
    private var window: DoubleArray

    fun transform(real: DoubleArray, imagine: DoubleArray) {
        var k: Int
        var n1: Int
        var c: Double
        var s: Double

        // Bit-reverse
        var j = 0
        val n2: Int = size / 2
        for (i in 1 until size - 1) {
            n1 = n2
            while (j >= n1) {
                j -= n1
                n1 /= 2
            }
            j += n1
            if (i < j) {
                var temp = real[i]
                real[i] = real[j]
                real[j] = temp
                temp = imagine[i]
                imagine[i] = imagine[j]
                imagine[j] = temp
            }
        }
        // FFT
        var index = 1

        for (i in 0 until power) {
            val ctr = index
            index += index
            var a = 0
            for (l in 0 until ctr) {
                c = cos[a]
                s = sin[a]
                a += 1 shl power - i - 1
                k = l
                while (k < size) {
                    val temp = c * real[k + ctr] - s * imagine[k + ctr]
                    val temp2 = s * real[k + ctr] + c * imagine[k + ctr]
                    real[k + ctr] = real[k] - temp
                    imagine[k + ctr] = imagine[k] - temp2
                    real[k] = real[k] + temp
                    imagine[k] = imagine[k] + temp2
                    k += index
                }
            }
        }
    }

    init {
        // Make sure n is a power of 2
        if (size != 1 shl power) throw RuntimeException("FFT length must be power of 2")
        // precompute tables
        cos = DoubleArray(size / 2)
        sin = DoubleArray(size / 2)
        for (i in 0 until size / 2) {
            cos[i] = cos(-2 * Math.PI * i / size)
            sin[i] = sin(-2 * Math.PI * i / size)
        }
        window = DoubleArray(size)
        for (i in window.indices)
            window[i] =
                (0.42 - 0.5 * cos(2 * Math.PI * i / (size - 1)) + 0.08 * cos(4 * Math.PI * i / (size - 1)))
    }
}