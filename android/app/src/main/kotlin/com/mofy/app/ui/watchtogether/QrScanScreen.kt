package com.mofy.app.ui.watchtogether

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.PlanarYUVLuminanceSource
import com.mofy.app.ui.theme.MofyAccent
import com.mofy.app.ui.theme.MofyBg
import com.mofy.app.ui.theme.MofyText
import com.mofy.app.ui.theme.MofyTextDim
import com.mofy.app.watchtogether.QrDecoder
import com.mofy.app.watchtogether.RoomCode

/**
 * C3b: camera scan of the host's QR (same `mofy://wt/{roomKey}` payload as
 * [RoomCode.toDeepLink]). Only decodes and hands the parsed result back via
 * [onScanned] - joining is [JoinSessionSheet]'s job, not this screen's.
 */
@Composable
fun QrScanScreen(
    onScanned: (RoomCode.Parsed) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) requestPermission.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MofyBg)
            .padding(20.dp),
    ) {
        Text("Scan host's QR code", style = MaterialTheme.typography.titleLarge, color = MofyText)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (hasCameraPermission) {
                CameraPreview(onScanned = onScanned)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .aspectRatio(1f)
                        .border(2.dp, MofyAccent, MaterialTheme.shapes.large),
                )
            } else {
                Text(
                    "Camera permission is needed to scan a QR code",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MofyTextDim,
                )
            }
        }
        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.buttonColors(containerColor = MofyBg),
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun CameraPreview(onScanned: (RoomCode.Parsed) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    var handled by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                    if (handled) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    // Y-plane luminance is all ZXing needs; rowStride padding is
                    // cropped away by PlanarYUVLuminanceSource's dataWidth/crop.
                    val plane = imageProxy.planes.firstOrNull()
                    if (plane == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val buffer = plane.buffer
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    val source = PlanarYUVLuminanceSource(
                        data,
                        plane.rowStride,
                        imageProxy.height,
                        0, 0,
                        imageProxy.width,
                        imageProxy.height,
                        false,
                    )
                    val raw = QrDecoder.decode(source)
                    if (raw != null) {
                        val parsed = RoomCode.parseDeepLink(raw)
                        if (parsed != null && !handled) {
                            handled = true
                            currentOnScanned(parsed)
                        }
                    }
                    imageProxy.close()
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}
