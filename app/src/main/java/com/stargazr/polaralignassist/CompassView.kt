package com.stargazr.polaralignassist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class CompassView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var azimuth: Float = 0f
    private var devicePitch: Float = 0f
    private var currentLatitude: Float? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.RED
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        color = Color.RED
    }
    private val polarisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }

    private val errorTextPaintAz = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 40f
        color = Color.RED
        textAlign = Paint.Align.CENTER
    }
    private val errorTextPaintAlt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 40f
        color = Color.RED
        textAlign = Paint.Align.CENTER
    }

    fun updateData(azimuth: Float, devicePitch: Float, currentLatitude: Float) {
        this.azimuth = azimuth
        // The pitch from orientation sensor is negative when tilting up, so we invert it.
        this.devicePitch = -devicePitch
        this.currentLatitude = currentLatitude
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Set background to black
        canvas.drawColor(Color.BLACK)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2
        val centerY = height / 2
        val radius = min(centerX, centerY) - 80 // Adjusted radius to make space for text

        // Draw compass card
        canvas.save()
        canvas.rotate(-azimuth, centerX, centerY)
        canvas.drawCircle(centerX, centerY, radius, paint)
        for (i in 0 until 360 step 30) {
            val angle = Math.toRadians(i.toDouble())
            val startX = centerX + (radius - 20) * sin(angle).toFloat()
            val startY = centerY - (radius - 20) * cos(angle).toFloat()
            val stopX = centerX + radius * sin(angle).toFloat()
            val stopY = centerY - radius * cos(angle).toFloat()
            canvas.drawLine(startX, startY, stopX, stopY, paint)
        }
        canvas.drawText("N", centerX - 15, centerY - radius - 10, textPaint)
        canvas.restore()

        // Draw crosshairs
        canvas.drawLine(centerX - 30, centerY, centerX + 30, centerY, paint)
        canvas.drawLine(centerX, centerY - 30, centerX, centerY + 30, paint)

        currentLatitude?.let { lat ->
            val pixelsPerDegree = radius / 90f
            val zoomThreshold = 15f // Degrees from center to apply zoom
            val zoomFactor = 4f

            var deltaAzimuth = -azimuth
            if (deltaAzimuth > 180) deltaAzimuth -= 360f
            if (deltaAzimuth <= -180) deltaAzimuth += 360f

            val deltaAltitude = lat - devicePitch
Log.d("delta", deltaAzimuth.toString() + " " + deltaAltitude.toString())
            var displayAzimuth = deltaAzimuth
            var displayAltitude = deltaAltitude

            if (abs(deltaAzimuth) < 1.0f && abs(deltaAltitude) < 1.0f) {
                polarisPaint.color = Color.GREEN
            } else {
                polarisPaint.color = Color.RED
            }
            if (abs(deltaAzimuth) < 1.0f) errorTextPaintAz.color = Color.GREEN else errorTextPaintAz.color = Color.RED
            if (abs(deltaAltitude) < 1.0f) errorTextPaintAlt.color = Color.GREEN else errorTextPaintAlt.color = Color.RED

            // If we are close to the target, magnify the deltas for sensitivity
            if (abs(deltaAzimuth) < zoomThreshold && abs(deltaAltitude) < zoomThreshold) {
                displayAzimuth *= zoomFactor
                displayAltitude *= zoomFactor
                canvas.drawCircle(centerX, centerY, pixelsPerDegree * zoomFactor, paint)
            }

            // Convert degrees to pixel offsets
            var polarisXOffset = pixelsPerDegree * displayAzimuth
            var polarisYOffset = pixelsPerDegree * displayAltitude

            // Clamp the offset to the radius of the compass to prevent it from going off-screen
            polarisXOffset = max(-radius, min(radius, polarisXOffset))
            polarisYOffset = max(-radius, min(radius, polarisYOffset))

            val polarisX = centerX + polarisXOffset
            val polarisY = centerY - polarisYOffset // Screen Y is inverted

            // Draw Polaris indicator
            canvas.drawCircle(polarisX, polarisY, 15f, polarisPaint)

            // Draw error text
            val azimuthErrorText = String.format("Azimuth Error: %.2f°", deltaAzimuth)
            val altitudeErrorText = String.format("Altitude Error: %.2f°", deltaAltitude)
            val textYPosition = centerY + radius + 60f
            canvas.drawText(azimuthErrorText, centerX, textYPosition, errorTextPaintAz)
            canvas.drawText(altitudeErrorText, centerX, textYPosition + 50f, errorTextPaintAlt)
        }
    }
}