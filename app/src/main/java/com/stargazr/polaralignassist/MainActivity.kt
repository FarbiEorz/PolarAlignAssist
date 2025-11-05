package com.stargazr.polaralignassist

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    private lateinit var sensorManager: SensorManager

    private var magnetometerSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var rotationVectorSensor: Sensor? = null

    private val accelerometerReading = FloatArray(3)
    private val magneticReading = FloatArray(3)
    private val rotationVectorReading = FloatArray(5)
    private lateinit var compassView: CompassView
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var declination = 0f
    private var currentLatitude = 0f
    private var fusedAzimuth = 0f
    private var devicePitch = 0.0f

    private val ALPHA_LOW = 0.85f
    private val ALPHA_HIGH = 0.98f

    private val THRESHOLD_ACCEL_VARIANCE = 0.1f

    private var dynamicAlpha = ALPHA_HIGH

    private var lastAccelMagnitude = 0f
    private var accelVarianceAccumulator = 0f
    private var varianceCount = 0

    private lateinit var locationManager: LocationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        compassView = findViewById(R.id.compassView)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else {
            initializeLocation()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun initializeLocation() {
        val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
        if (lastKnownLocation != null) {
            onLocationChanged(lastKnownLocation)
        }
        locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 5000L, 100f, this)
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, magnetometerSensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_UI)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 5000L, 100f, this)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerReading, 0, 3)
                updateDynamicAlpha()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magneticReading, 0, 3)
            Sensor.TYPE_ROTATION_VECTOR -> System.arraycopy(event.values, 0, rotationVectorReading, 0, 5)
        }

        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

            fuseOrientation()

            if (currentLatitude != 0.0f) {
                val azimuth = fusedAzimuth - declination
                compassView.updateData(azimuth, devicePitch, currentLatitude)
            }
        }
    }

    private fun fuseOrientation() {
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magneticReading)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            val magneticAzimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

            SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVectorReading)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            devicePitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat() + 43f

            val rvAzimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

            if (fusedAzimuth == 0f) {
                fusedAzimuth = magneticAzimuth
            } else {
                val driftCorrection = normalizeAngle(magneticAzimuth - rvAzimuth)
                val correctionAmount = driftCorrection * (1f - dynamicAlpha)
                Log.d("DALPHA", dynamicAlpha.toString())
                fusedAzimuth = normalizeAngle(rvAzimuth + correctionAmount)
                //fusedAzimuth = rvAzimuth
                //fusedAzimuth = normalizeAngle(magneticAzimuth)
            }



        }
    }

    private fun normalizeAngle(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a <= -180f) a += 360f
        return a
    }

    private fun updateDynamicAlpha() {
        // 現在の加速度の大きさを計算
        val currentMagnitude = calculateAccelerationMagnitude(accelerometerReading)

        if (lastAccelMagnitude != 0f) {
            // 加速度の変動量を蓄積
            val variance = abs(currentMagnitude - lastAccelMagnitude)
            accelVarianceAccumulator += variance
            //Log.d("VAR", variance.toString())
            varianceCount++

            // 例: 20フレームごとに平均変動をチェックし、αを更新
            if (varianceCount >= 20) {
                val avgVariance = accelVarianceAccumulator / varianceCount
                Log.d("VARAVE", avgVariance.toString())
                // 変動が大きい（デバイスが動いている） -> αを高く (RV優先)
                // 変動が小さい（デバイスが静止している） -> αを低く (磁気補正優先)

                if (avgVariance < THRESHOLD_ACCEL_VARIANCE) {
                    // 静止に近い: αを低くし、磁気センサーの影響を強める
                    dynamicAlpha = ALPHA_LOW
                } else {
                    // 運動中: αを高くし、RVの滑らかさを優先
                    dynamicAlpha = ALPHA_HIGH
                }

                // リセット
                accelVarianceAccumulator = 0f
                varianceCount = 0
            }
        }
        lastAccelMagnitude = currentMagnitude
    }

    private fun calculateAccelerationMagnitude(values: FloatArray): Float {
        // 加速度計は重力加速度 (9.8m/s^2) を含むため、単純なベクトルの大きさでOK
        return sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this example
    }

    override fun onLocationChanged(location: Location) {
        currentLatitude = location.latitude.toFloat()
        val geomagneticField = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis()
        )
        declination = geomagneticField.declination
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}