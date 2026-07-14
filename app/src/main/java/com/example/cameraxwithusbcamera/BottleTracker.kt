package com.example.cameraxwithusbcamera

import android.graphics.PointF
import android.graphics.RectF
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

enum class Direction {
    IN, OUT
}

data class TrackingResult(
    val direction: Direction?,
    val centroid: PointF?,
    val boundingRect: RectF?,
    val confidence: Float,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

class BottleTracker {

    companion object {
        private const val DIFF_THRESHOLD = 30.0
        private const val MIN_CONTOUR_AREA_FACTOR = 0.02
        private const val DIRECTION_DEAD_ZONE_PX = 10f
        private const val MAX_IDLE_FRAMES = 3
    }

    private var previousGray: Mat? = null
    private var previousCentroid: PointF? = null
    private var idleCounter = 0
    private var frameWidth = 0
    private var frameHeight = 0
    private var lastDirection: Direction? = null

    fun processFrame(rgbaMat: Mat): TrackingResult {
        frameWidth = rgbaMat.cols()
        frameHeight = rgbaMat.rows()

        val gray = Mat()
        Imgproc.cvtColor(rgbaMat, gray, Imgproc.COLOR_RGBA2GRAY)

        val prev = previousGray
        if (prev == null) {
            previousGray = gray
            return TrackingResult(null, null, null, 0f, frameWidth, frameHeight)
        }

        val diff = Mat()
        Core.absdiff(gray, prev, diff)
        prev.release()
        previousGray = gray

        val threshold = Mat()
        Imgproc.threshold(diff, threshold, DIFF_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
        diff.release()

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        Imgproc.morphologyEx(threshold, threshold, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(threshold, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        threshold.release()

        val minArea = frameWidth * frameHeight * MIN_CONTOUR_AREA_FACTOR
        var bestContour: MatOfPoint? = null
        var bestArea = 0.0

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > minArea && area > bestArea) {
                bestArea = area
                bestContour = contour
            }
        }

        if (bestContour == null) {
            idleCounter++
            if (idleCounter > MAX_IDLE_FRAMES) {
                previousCentroid = null
                lastDirection = null
            }
            contours.forEach { it.release() }
            return TrackingResult(null, null, null, 0f, frameWidth, frameHeight)
        }

        idleCounter = 0
        val moments = Imgproc.moments(bestContour)
        val cx = moments.m10 / moments.m00
        val cy = moments.m01 / moments.m00

        val rect = Imgproc.boundingRect(bestContour)
        val boundingRect = RectF(
            rect.x.toFloat(), rect.y.toFloat(),
            (rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat()
        )

        bestContour.release()
        contours.forEach { it.release() }

        val centroid = PointF(cx.toFloat(), cy.toFloat())
        val direction = computeDirection(centroid)

        previousCentroid = centroid
        lastDirection = direction

        return TrackingResult(
            direction = direction,
            centroid = centroid,
            boundingRect = boundingRect,
            confidence = if (direction != null) 1f else 0f,
            frameWidth = frameWidth,
            frameHeight = frameHeight
        )
    }

    private fun computeDirection(current: PointF): Direction? {
        val prev = previousCentroid ?: return null
        val deltaX = current.x - prev.x
        if (abs(deltaX) < DIRECTION_DEAD_ZONE_PX) return lastDirection

        val centerX = frameWidth / 2f
        return if (deltaX > 0) {
            if (current.x > centerX) Direction.OUT else Direction.IN
        } else {
            if (current.x < centerX) Direction.OUT else Direction.IN
        }
    }

    fun reset() {
        previousGray?.release()
        previousGray = null
        previousCentroid = null
        lastDirection = null
        idleCounter = 0
    }
}
