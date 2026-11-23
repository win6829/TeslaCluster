package com.example.teslacluster

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

enum class ClusterTheme {
    CLASSIC,
    NEON_HIGHWAY
}

class MainActivity : ComponentActivity(), LocationListener {

    private var locationManager: LocationManager? = null
    private val gpsSpeedKmh = mutableFloatStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        startGpsTracking()

        setContent {
            TeslaClusterApp(gpsSpeed = gpsSpeedKmh.floatValue)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGpsTracking() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val hasFine = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFine) {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0f,
                this
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
        }
    }

    override fun onLocationChanged(location: Location) {
        if (location.hasSpeed()) {
            val kmh = (location.speed * 3.6f).coerceIn(0f, 240f)
            gpsSpeedKmh.floatValue = if (kmh < 1.5f) 0f else kmh
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager?.removeUpdates(this)
    }
}

@Composable
fun TeslaClusterApp(gpsSpeed: Float) {
    var currentTheme by remember { mutableStateOf(ClusterTheme.CLASSIC) }

    val initialNight = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hour < 6 || hour >= 19
    }
    var isNightMode by remember { mutableStateOf(initialNight) }

    var isSimulatingAccel by remember { mutableStateOf(false) }
    var simulatedSpeed by remember { mutableFloatStateOf(0f) }

    var tripDistanceKm by remember { mutableDoubleStateOf(0.0) }
    var tripSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isSimulatingAccel) {
        while (true) {
            delay(50L)
            if (isSimulatingAccel) {
                simulatedSpeed = (simulatedSpeed + 2.8f).coerceAtMost(240f)
            } else if (simulatedSpeed > 0f) {
                simulatedSpeed = (simulatedSpeed - 2.2f).coerceAtLeast(0f)
            }
            val activeSpeed = max(gpsSpeed, simulatedSpeed)
            if (activeSpeed > 0.5f) {
                tripDistanceKm += (activeSpeed.toDouble() * (0.05 / 3600.0))
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            if (max(gpsSpeed, simulatedSpeed) > 1f) {
                tripSeconds++
            }
        }
    }

    val targetSpeed = max(gpsSpeed, simulatedSpeed).coerceIn(0f, 240f)
    val animatedSpeed by animateFloatAsState(
        targetValue = targetSpeed,
        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
        label = "speed_interp"
    )

    var roadOffset by remember { mutableFloatStateOf(0f) }
    var cloudOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastTime = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - lastTime) / 1_000_000_000f).coerceAtMost(0.1f)
                lastTime = now
                val speedFactor = animatedSpeed / 60f
                roadOffset = (roadOffset + dt * (0.35f + speedFactor * 2.2f)) % 1f
                cloudOffset = (cloudOffset + dt * 0.015f) % 1f
            }
        }
    }

    val bgTopColor by animateColorAsState(
        targetValue = when {
            currentTheme == ClusterTheme.NEON_HIGHWAY && isNightMode -> Color(0xFF020408)
            currentTheme == ClusterTheme.NEON_HIGHWAY && !isNightMode -> Color(0xFFEAF2FA)
            isNightMode -> Color(0xFF0D1117)
            else -> Color(0xFFF3F6FA)
        },
        animationSpec = tween(500), label = "bg_top"
    )

    val bgBottomColor by animateColorAsState(
        targetValue = when {
            currentTheme == ClusterTheme.NEON_HIGHWAY && isNightMode -> Color(0xFF050A14)
            currentTheme == ClusterTheme.NEON_HIGHWAY && !isNightMode -> Color(0xFFFFFFFF)
            isNightMode -> Color(0xFF1A2029)
            else -> Color(0xFFFFFFFF)
        },
        animationSpec = tween(500), label = "bg_bottom"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgTopColor, bgBottomColor)))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isSimulatingAccel = true
                        tryAwaitRelease()
                        isSimulatingAccel = false
                    }
                )
            }
    ) {
        VolumetricCumulusSky(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f),
            cloudOffset = cloudOffset,
            isNightMode = isNightMode,
            theme = currentTheme
        )

        HighwayRoadCanvas(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .align(Alignment.BottomCenter),
            roadOffset = roadOffset,
            isNightMode = isNightMode,
            theme = currentTheme
        )

        TeslaVehicleView(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp)
                .size(width = 210.dp, height = 115.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    currentTheme = if (currentTheme == ClusterTheme.CLASSIC) {
                        ClusterTheme.NEON_HIGHWAY
                    } else {
                        ClusterTheme.CLASSIC
                    }
                }
        )

        GradientSpeedometer(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 28.dp, bottom = 12.dp)
                .size(290.dp),
            speed = animatedSpeed,
            maxSpeed = 240f,
            isNightMode = isNightMode,
            theme = currentTheme,
            onToggleDayNight = { isNightMode = !isNightMode }
        )

        TripGaugePanel(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 28.dp, bottom = 12.dp)
                .size(260.dp),
            distanceKm = tripDistanceKm,
            elapsedSeconds = tripSeconds,
            isNightMode = isNightMode,
            theme = currentTheme,
            onResetTrip = {
                tripDistanceKm = 0.0
                tripSeconds = 0L
            }
        )
    }
}

@Composable
fun VolumetricCumulusSky(
    modifier: Modifier,
    cloudOffset: Float,
    isNightMode: Boolean,
    theme: ClusterTheme
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val skyColors = when {
            theme == ClusterTheme.NEON_HIGHWAY && isNightMode ->
                listOf(Color(0xFF000000), Color(0xFF040B1A))
            theme == ClusterTheme.NEON_HIGHWAY && !isNightMode ->
                listOf(Color(0xFFD6E9FF), Color(0xFFF5F9FF))
            isNightMode ->
                listOf(Color(0xFF0C121E), Color(0xFF1C2432))
            else ->
                listOf(Color(0xFFDCEBFF), Color(0xFFF6F9FC))
        }
        drawRect(brush = Brush.verticalGradient(skyColors))

        val shadowColor = if (isNightMode) Color(0xFF172030).copy(alpha = 0.72f) else Color(0xFFB5C7DC).copy(alpha = 0.75f)
        val bodyColor = if (isNightMode) Color(0xFF283448).copy(alpha = 0.78f) else Color(0xFFEAF1F8).copy(alpha = 0.92f)
        val highlightColor = if (isNightMode) Color(0xFF435570).copy(alpha = 0.65f) else Color(0xFFFFFFFF).copy(alpha = 0.98f)

        fun drawPuffyCumulus(baseX: Float, baseY: Float, scale: Float) {
            val puffs = listOf(
                Triple(-58f, 12f, 34f),
                Triple(-28f, -8f, 44f),
                Triple(8f, -18f, 52f),
                Triple(46f, -4f, 42f),
                Triple(74f, 14f, 30f),
                Triple(0f, 14f, 40f)
            )

            puffs.forEach { (dx, dy, r) ->
                val cx = baseX + dx * scale
                val cy = baseY + (dy + 10f) * scale
                val rad = r * scale * 1.05f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(shadowColor, Color.Transparent),
                        center = Offset(cx, cy),
                        radius = rad
                    ),
                    radius = rad,
                    center = Offset(cx, cy)
                )
            }

            puffs.forEach { (dx, dy, r) ->
                val cx = baseX + dx * scale
                val cy = baseY + dy * scale
                val rad = r * scale
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(bodyColor, bodyColor.copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(cx, cy - rad * 0.15f),
                        radius = rad
                    ),
                    radius = rad,
                    center = Offset(cx, cy)
                )
            }

            puffs.forEach { (dx, dy, r) ->
                val cx = baseX + (dx - 4f) * scale
                val cy = baseY + (dy - 10f) * scale
                val rad = r * scale * 0.72f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(highlightColor, Color.Transparent),
                        center = Offset(cx, cy),
                        radius = rad
                    ),
                    radius = rad,
                    center = Offset(cx, cy)
                )
            }
        }

        val shift1 = ((cloudOffset * w * 1.3f) % (w + 320f)) - 160f
        val shift2 = (((cloudOffset + 0.35f) * w * 1.3f) % (w + 320f)) - 160f
        val shift3 = (((cloudOffset + 0.68f) * w * 1.3f) % (w + 320f)) - 160f

        drawPuffyCumulus(shift1, h * 0.24f, 1.15f)
        drawPuffyCumulus(shift2, h * 0.17f, 0.90f)
        drawPuffyCumulus(shift3, h * 0.30f, 1.25f)
    }
}

@Composable
fun HighwayRoadCanvas(
    modifier: Modifier,
    roadOffset: Float,
    isNightMode: Boolean,
    theme: ClusterTheme
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val horizonY = 0f
        val centerX = w / 2f
        val isNeon = theme == ClusterTheme.NEON_HIGHWAY

        val roadTop = when {
            isNeon && isNightMode -> Color(0xFF040914)
            isNeon && !isNightMode -> Color(0xFFD5E3F2)
            isNightMode -> Color(0xFF181D26)
            else -> Color(0xFFDCE2EA)
        }
        val roadBottom = when {
            isNeon && isNightMode -> Color(0xFF071529)
            isNeon && !isNightMode -> Color(0xFFECF3FA)
            isNightMode -> Color(0xFF252C38)
            else -> Color(0xFFF1F4F8)
        }
        drawRect(brush = Brush.verticalGradient(listOf(roadTop, roadBottom)))

        if (isNeon) {
            val glowColor = if (isNightMode) Color(0xFF00B4FF).copy(alpha = 0.38f) else Color(0xFF0088FF).copy(alpha = 0.22f)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, glowColor, Color.Transparent),
                    startX = centerX - w * 0.16f,
                    endX = centerX + w * 0.16f
                )
            )
        }

        val railColor = when {
            isNeon && isNightMode -> Color(0xFF00C8FF)
            isNeon && !isNightMode -> Color(0xFF0077CC)
            isNightMode -> Color(0xFF5A6578)
            else -> Color(0xFF9AA5B4)
        }

        drawLine(railColor, Offset(centerX - 18f, horizonY), Offset(0f, h * 0.34f), strokeWidth = 4.dp.toPx())
        drawLine(railColor.copy(alpha = 0.7f), Offset(centerX - 18f, horizonY + 8f), Offset(0f, h * 0.50f), strokeWidth = 2.5.dp.toPx())
        drawLine
