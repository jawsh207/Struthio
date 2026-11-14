package app.struthio.nostr.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.atomic.AtomicBoolean

private val Scrim = Color(0xB3000000)

/** Camera2 feeding frames to the QR reader. No Play Services and no CameraX. */
private class QrCamera(
    private val context: Context,
    private val texture: SurfaceTexture,
    private val onCode: (String) -> Unit,
    private val onError: () -> Unit,
) {
    private val thread = HandlerThread("qr").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2)
    private val qr = QRCodeReader()
    private val finished = AtomicBoolean(false)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    private fun decode() {
        val image = reader.acquireLatestImage() ?: return
        try {
            if (finished.get()) return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val stride = plane.rowStride
            val width = image.width
            val height = image.height
            val data = ByteArray(width * height)
            for (row in 0 until height) {
                buffer.position(row * stride)
                buffer.get(data, row * width, minOf(width, buffer.remaining()))
            }
            val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            val text = qr.decode(BinaryBitmap(HybridBinarizer(source))).text
            if (finished.compareAndSet(false, true)) onCode(text)
        } catch (_: ReaderException) {
            qr.reset()
        } finally {
            image.close()
        }
    }

    private fun backCamera(manager: CameraManager): String? = manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    } ?: manager.cameraIdList.firstOrNull()

    @SuppressLint("MissingPermission")
    fun start() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val id = backCamera(manager) ?: return onError()
            texture.setDefaultBufferSize(WIDTH, HEIGHT)
            reader.setOnImageAvailableListener({ decode() }, handler)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    begin(camera)
                }

                override fun onDisconnected(camera: CameraDevice) = camera.close()

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    onError()
                }
            }, handler)
        } catch (_: Exception) {
            onError()
        }
    }

    @Suppress("DEPRECATION")
    private fun begin(camera: CameraDevice) {
        val preview = Surface(texture)
        try {
            camera.createCaptureSession(listOf(preview, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(capture: CameraCaptureSession) {
                    session = capture
                    runCatching {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        capture.setRepeatingRequest(request.build(), null, handler)
                    }
                }

                override fun onConfigureFailed(capture: CameraCaptureSession) = onError()
            }, handler)
        } catch (_: Exception) {
            onError()
        }
    }

    fun stop() {
        finished.set(true)
        handler.post {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val camera = remember { mutableStateOf<QrCamera?>(null) }
    DisposableEffect(Unit) {
        onDispose { camera.value?.stop() }
    }
    AndroidView(
        factory = {
            TextureView(it).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        camera.value = QrCamera(context, surface, onCode, onError).also(QrCamera::start)
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f),
    )
}

/** Full screen scanner. Asks for the camera only when someone opens it. */
@Composable
fun QrScanner(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var failed by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                failed -> Text("The camera could not be opened.", color = Color.White)
                granted -> CameraPreview(onResult) { failed = true }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Zino needs the camera to scan codes.", color = Color.White)
                    Button({ ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            }
            FilledIconButton(
                onClose,
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Scrim, contentColor = Color.White),
            ) {
                Icon(Icons.Rounded.Close, "Close")
            }
            if (granted && !failed) {
                Text(
                    "Point at a QR code",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(32.dp)
                        .background(Scrim, CircleShape)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
fun ScanButton(onResult: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton({ open = true }) { Icon(Icons.Rounded.QrCodeScanner, "Scan QR code") }
    if (open) {
        QrScanner({
            open = false
            onResult(it)
        }) { open = false }
    }
}

@Composable
fun QrImage(text: String, size: Dp = 260.dp) {
    val matrix: BitMatrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    }
    Canvas(Modifier.size(size).clip(RoundedCornerShape(20.dp)).background(Color.White)) {
        val cell = this.size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
