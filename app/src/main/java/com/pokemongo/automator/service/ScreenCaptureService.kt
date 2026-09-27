package com.pokemongo.automator.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.pokemongo.automator.MainActivity
import com.pokemongo.automator.R
import com.pokemongo.automator.catch.CatchRunner
import com.pokemongo.automator.catch.RunEvent
import com.pokemongo.automator.vision.EncounterOcr
import com.pokemongo.automator.vision.MapScanner
import com.pokemongo.automator.vision.PokemonModel
import com.pokemongo.automator.vision.ScreenSignature
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val overlay by lazy { OverlayController(this) }
    private val ocr = EncounterOcr()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0

    @Volatile
    private var stopped = false

    private var caughtCount = 0
    private var runLog: RunLog? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground("Starting")
        if (AutomatorState.running.value && mediaProjection != null) {
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            AutomatorState.status.value = "Screen capture was not allowed"
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startProjection(resultCode, resultData)
            overlay.show()
        } catch (error: RuntimeException) {
            AutomatorState.status.value = "Stopped: ${error.message ?: "could not capture"}"
            stopSelf()
            return START_NOT_STICKY
        }

        AutomatorState.running.value = true
        AutomatorState.status.value = "Scanning"
        val scanner = MapScanner(assets.open(PokemonModel.ASSET).reader().use(PokemonModel::read))
        val log = RunLog(File(getExternalFilesDir(null), "runs"), scope)
        runLog = log
        Log.i(TAG, "run log ${log.dir}")
        scope.launch {
            try {
                CatchRunner(
                    gestures = liveGestures,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    capture = { captureBitmap() },
                    readText = { bitmap ->
                        val text = ocr.read(bitmap)
                        Log.i(TAG, "ocr=${text.replace('\n', ' ').take(200)}")
                        text
                    },
                    findPokemon = { bitmap ->
                        val started = System.nanoTime()
                        val scan = scanner.scan(pixelsOf(bitmap), bitmap.width, bitmap.height)
                        Log.i(TAG, "targets=${scan.targets.take(4).map { "${it.x},${it.y}@${"%.2f".format(it.score)}" }} in ${(System.nanoTime() - started) / 1_000_000}ms")
                        scan
                    },
                    isMap = { bitmap -> ScreenSignature.isMap(pixelsOf(bitmap), bitmap.width, bitmap.height) },
                    mightBeMap = { bitmap -> ScreenSignature.mightBeMap(pixelsOf(bitmap), bitmap.width, bitmap.height) },
                    pokemonGoInFront = {
                        AutomatorAccessibilityService.instance?.isPokemonGoInFront() == true
                    },
                    onStatus = ::publish,
                    onEvent = { event ->
                        log.record(event)
                        if (event is RunEvent.Caught) onCaught(log.caught)
                    },
                ).run { !stopped }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                Log.e(TAG, "catch loop failed", error)
                AutomatorState.status.value = "Stopped: ${error.message ?: "catch loop failed"}"
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        val metrics = displayMetrics()
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        val projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("Screen capture was not allowed")
        mediaProjection = projection
        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            },
            Handler(Looper.getMainLooper()),
        )
        virtualDisplay = projection.createVirtualDisplay(
            "poke-capture",
            screenWidth,
            screenHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
    }

    private suspend fun captureBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        repeat(20) {
            val image = reader.acquireLatestImage()
            if (image != null) {
                return try {
                    imageToBitmap(image)
                } finally {
                    image.close()
                }
            }
            delay(16)
        }
        return null
    }

    /** One pixel buffer per frame, shared by the map check and the scanner. */
    private var cachedFor: Bitmap? = null
    private var cachedPixels = IntArray(0)

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        if (cachedFor === bitmap) return cachedPixels
        val size = bitmap.width * bitmap.height
        if (cachedPixels.size != size) cachedPixels = IntArray(size)
        bitmap.getPixels(cachedPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        cachedFor = bitmap
        return cachedPixels
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + if (pixelStride == 0) 0 else rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun displayMetrics(): DisplayMetrics {
        val windowManager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            DisplayMetrics().apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = resources.displayMetrics.densityDpi
            }
        } else {
            resources.displayMetrics
        }
    }

    private fun onCaught(count: Int) {
        caughtCount = count
        Log.i(TAG, "verified catch $count/$TARGET_CATCHES")
        publish("Caught $count/$TARGET_CATCHES")
        if (count >= TARGET_CATCHES) {
            AutomatorState.status.value = "Done: caught $count"
            stopSelf()
        }
    }

    private fun publish(text: String) {
        val line = if (caughtCount > 0 && !text.startsWith("Caught")) "$text · $caughtCount/$TARGET_CATCHES" else text
        Log.i(TAG, line)
        AutomatorState.status.value = line
        overlay.update(line)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(line))
    }

    private fun startInForeground(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Catch loop",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Catch loop")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_catch)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_catch),
                    "Stop",
                    stopIntent,
                ).build(),
            )
            .build()
    }

    private fun shutdown() {
        if (stopped) return
        stopped = true
        runLog?.let { Log.i(TAG, "run finished ${it.summary()}") }
        scope.cancel()
        overlay.remove()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        ocr.close()
        AutomatorState.running.value = false
        val status = AutomatorState.status.value
        if (!status.startsWith("Stopped") && !status.startsWith("Done") && status != "Screen capture was not allowed") {
            AutomatorState.status.value = "Idle"
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private val liveGestures = object : com.pokemongo.automator.catch.CatchGestures {
        override suspend fun tap(x: Int, y: Int) {
            AutomatorAccessibilityService.instance?.tap(x, y)
        }

        override suspend fun straightThrow(screenWidth: Int, screenHeight: Int) {
            AutomatorAccessibilityService.instance?.straightThrow(screenWidth, screenHeight)
        }

        override suspend fun flee(screenWidth: Int, screenHeight: Int) {
            AutomatorAccessibilityService.instance?.flee(screenWidth, screenHeight)
        }

        override suspend fun back() {
            AutomatorAccessibilityService.instance?.back()
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.pokemongo.automator.STOP"

        private const val TAG = "CatchLoop"
        private const val CHANNEL_ID = "catch_loop"
        private const val NOTIFICATION_ID = 42
        private const val TARGET_CATCHES = 100

        fun stopIntent(context: android.content.Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        }
    }
}

fun android.content.Context.startCatchLoop(resultCode: Int, resultData: Intent) {
    val intent = Intent(this, ScreenCaptureService::class.java).apply {
        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
        putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
    }
    ContextCompat.startForegroundService(this, intent)
}
