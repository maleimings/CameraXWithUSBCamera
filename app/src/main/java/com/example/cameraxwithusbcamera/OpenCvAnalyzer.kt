package com.example.cameraxwithusbcamera

import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.cameraxwithusbcamera.OpenCvAnalyzer.Companion.FRAME_INTERVAL_MS
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.util.Locale

/**
 * An [ImageAnalysis.Analyzer] that converts CameraX YUV_420_888 frames
 * to OpenCV [Mat] and runs Canny edge detection plus bottle motion tracking.
 *
 * Rate-limited to at most one analysis every [FRAME_INTERVAL_MS] to avoid
 * overwhelming the CPU and UI thread.
 *
 * Stride-safe: handles [ImageProxy.PlaneProxy.rowStride] > width and
 * [ImageProxy.PlaneProxy.pixelStride] > 1, which CameraX planes commonly have.
 */
class OpenCvAnalyzer(
    private val onAnalysisResult: (String, TrackingResult) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "OpenCvAnalyzer"
        private const val EDGE_THRESHOLD1 = 50.0
        private const val EDGE_THRESHOLD2 = 150.0
        private const val FRAME_INTERVAL_MS = 200L
    }

    private val bottleTracker = BottleTracker()

    private var initAttempted = false
    private var openCvInitialized = false
    private var lastAnalysisTime = 0L

    init {
        tryInitOpenCv()
    }

    private fun tryInitOpenCv() {
        if (initAttempted) return
        initAttempted = true
        try {
            openCvInitialized = OpenCVLoader.initLocal()
            if (!openCvInitialized) {
                Log.w(TAG, "OpenCV initLocal() returned false — native libs may not be bundled")
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "OpenCV native library failed to load", e)
        }
    }

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        // Rate limit
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTime < FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastAnalysisTime = now

        if (!openCvInitialized) {
            imageProxy.close()
            onAnalysisResult("OpenCV not loaded", TrackingResult(null, null, null, 0f, imageProxy.width, imageProxy.height))
            return
        }

        if (imageProxy.format != ImageFormat.YUV_420_888) {
            onAnalysisResult("Unexpected format: ${imageProxy.format}", TrackingResult(null, null, null, 0f, imageProxy.width, imageProxy.height))
            imageProxy.close()
            return
        }

        try {
            val rgba = yuv420888ToRgba(imageProxy)
            val (edgeText, trackingResult) = analyzeFrame(rgba)
            rgba.release()
            onAnalysisResult(edgeText, trackingResult)
        } catch (e: Exception) {
            Log.e(TAG, "Analysis error", e)
            onAnalysisResult("Error: ${e.message}", TrackingResult(null, null, null, 0f, imageProxy.width, imageProxy.height))
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Convert a YUV_420_888 [ImageProxy] to an RGBA OpenCV [Mat].
     *
     * Steps:
     *  1. Extract Y plane (handling row stride).
     *  2. Extract UV planes (handling row stride and pixel stride),
     *     interleaving as V-U pairs for NV21 format.
     *  3. Wrap in a single-channel [Mat] and use OpenCV's NV21→RGBA
     *     conversion.
     */
    private fun yuv420888ToRgba(imageProxy: ImageProxy): Mat {
        val width = imageProxy.width
        val height = imageProxy.height
        val planes = imageProxy.planes

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        // NV21: width*height for Y + width*height/2 for interleaved VU
        val nv21 = ByteArray(width * height * 3 / 2)

        // --- Copy Y plane ---
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
        } else {
            // Row stride > width: copy row by row
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        // --- Copy UV planes into NV21 VU interleaved layout ---
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val uvHeight = height / 2
        val uvWidth = width / 2
        val nv21Offset = width * height

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uIndex = row * uRowStride + col * uPixelStride
                val vIndex = row * vRowStride + col * vPixelStride
                // NV21: V then U
                val nv21Index = nv21Offset + row * width + col * 2
                nv21[nv21Index] = vBuffer.get(vIndex)
                nv21[nv21Index + 1] = uBuffer.get(uIndex)
            }
        }

        // --- NV21 byte array → Mat(NV21) → Mat(RGBA) ---
        val nv21Mat = Mat(height + height / 2, width, CvType.CV_8UC1)
        nv21Mat.put(0, 0, nv21)

        val rgbaMat = Mat(height, width, CvType.CV_8UC4)
        Imgproc.cvtColor(nv21Mat, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21)

        nv21Mat.release()
        return rgbaMat
    }

    /**
     * Run Canny edge detection and bottle tracking.
     *
     * Reports edge pixel percentage, average brightness, frame dimensions,
     * plus the bottle's movement direction.
     */
    private fun analyzeFrame(rgbaMat: Mat): Pair<String, TrackingResult> {
        val gray = Mat()
        Imgproc.cvtColor(rgbaMat, gray, Imgproc.COLOR_RGBA2GRAY)

        val edges = Mat()
        Imgproc.Canny(gray, edges, EDGE_THRESHOLD1, EDGE_THRESHOLD2)

        val totalPixels = edges.total().toDouble()
        val edgePixels = Core.countNonZero(edges).toDouble()
        val edgePercent = (edgePixels / totalPixels) * 100.0

        val brightness = Core.mean(gray).`val`[0]

        edges.release()
        gray.release()

        val text = String.format(
            Locale.US,
            "Edges: %.1f%% | Brightness: %.0f | %dx%d",
            edgePercent, brightness, rgbaMat.cols(), rgbaMat.rows()
        )

        val trackingResult = bottleTracker.processFrame(rgbaMat)
        return Pair(text, trackingResult)
    }
}
