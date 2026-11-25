package com.tesla.cluster

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

enum class WeatherType(val label: String, val dayIcon: String, val nightIcon: String) {
    CLEAR("맑음", "☀️", "🌕"),
    CLOUDY("구름", "☁️", "☁️"),
    RAIN("비", "🌧️", "🌧️"),
    SNOW("눈", "❄️", "❄️")
}

enum class ClusterTheme {
    ORIGINAL,
    NEON_IONIQ,
    CHRISTMAS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            TeslaClusterScreen()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                    )
            }
        } catch (_: Exception) {
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun TeslaClusterScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tesla_cluster_prefs", Context.MODE_PRIVATE) }

    var currentTheme by remember {
        val savedName = prefs.getString("last_theme", ClusterTheme.ORIGINAL.name)
        val restoredTheme = try {
            ClusterTheme.valueOf(savedName ?: ClusterTheme.ORIGINAL.name)
        } catch (_: Exception) {
            ClusterTheme.ORIGINAL
        }
        mutableStateOf(restoredTheme)
    }
    val currentMaxSpeed = 240f

    var gpsSpeedKmh by remember { mutableFloatStateOf(0f) }
    var isTestDriving by remember { mutableStateOf(false) }
    var testSpeedKmh by remember { mutableFloatStateOf(0f) }
    var testAccelerating by remember { mutableStateOf(true) }

    var smoothSpeedKmh by remember { mutableFloatStateOf(0f) }
    var roadScrollPhase by remember { mutableFloatStateOf(0f) }
    var elapsedSec by remember { mutableFloatStateOf(0f) }

    var totalDistanceMeters by remember { mutableFloatStateOf(0f) }
    var lastLocation by remember { mutableStateOf<Location?>(null) }
    var currentAddress by remember { mutableStateOf("위치 수신 대기 중...") }
    var lastGeocodedLocation by remember { mutableStateOf<Location?>(null) }
    var currentTimeText by remember { mutableStateOf("") }
    var currentDateText by remember { mutableStateOf("") }
    var batteryPct by remember { mutableIntStateOf(88) }
    var isCharging by remember { mutableStateOf(true) }

    val initialIsDay = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hour in 6..18
    }
    var liveIsDay by remember { mutableStateOf(initialIsDay) }
    var manualIsDay by remember { mutableStateOf<Boolean?>(null) }

    var liveWeather by remember { mutableStateOf(WeatherType.CLEAR) }
    var manualWeather by remember { mutableStateOf<WeatherType?>(null) }
    var temperatureC by remember { mutableStateOf<Int?>(null) }
    var lastWeatherLocation by remember { mutableStateOf<Location?>(null) }
    var lastWeatherFetchMs by remember { mutableLongStateOf(0L) }

    val activeIsDay = manualIsDay ?: liveIsDay
    val activeWeather = manualWeather ?: liveWeather

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasLocationPermission = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        delay(300L)
        if (!hasLocationPermission) {
            try {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.KOREA)
        val dateFormat = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)
        while (true) {
            val now = Date()
            currentTimeText = timeFormat.format(now)
            currentDateText = dateFormat.format(now)
            delay(1000L)
        }
    }

    val latestMaxSpeed by rememberUpdatedState(currentMaxSpeed)
    LaunchedEffect(Unit) {
        var lastFrameNs = System.nanoTime()

        while (true) {
            val nowNs = System.nanoTime()
            val dtSec = ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
            lastFrameNs = nowNs
            elapsedSec += dtSec

            val maxSpd = latestMaxSpeed

            if (isTestDriving) {
                val rate = if (testAccelerating) 36f else 42f
                if (testAccelerating) {
                    testSpeedKmh = (testSpeedKmh + rate * dtSec).coerceAtMost(maxSpd)
                    if (testSpeedKmh >= maxSpd) testAccelerating = false
                } else {
                    testSpeedKmh = (testSpeedKmh - rate * dtSec).coerceAtLeast(0f)
                    if (testSpeedKmh <= 0f) testAccelerating = true
                }
            } else {
                testSpeedKmh = 0f
            }

            val rawTarget = if (isTestDriving) testSpeedKmh else gpsSpeedKmh
            val clampedTarget = rawTarget.coerceIn(0f, maxSpd)

            val alpha = (1f - exp(-12f * dtSec)).coerceIn(0f, 1f)
            smoothSpeedKmh += (clampedTarget - smoothSpeedKmh) * alpha

            if (smoothSpeedKmh > 0.5f) {
                val flowRate = (smoothSpeedKmh / 36f) * dtSec
                roadScrollPhase = (roadScrollPhase + flowRate) % 1f
                if (isTestDriving) {
                    totalDistanceMeters += (smoothSpeedKmh / 3.6f) * dtSec
                }
            }

            delay(16L)
        }
    }

    val displayedSpeedInt = smoothSpeedKmh.roundToInt().coerceIn(0, 240)

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        batteryPct = (level * 100) / scale
                    }
                    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                }
            }
        }
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose {}

        val fusedClient = try {
            LocationServices.getFusedLocationProviderClient(context)
        } catch (_: Exception) {
            null
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val speed = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
                gpsSpeedKmh = if (speed < 1.5f) 0f else speed

                lastLocation?.let { prev ->
                    if (loc.accuracy < 25f) {
                        val dist = prev.distanceTo(loc)
                        if (dist in 1.0f..150.0f) {
                            totalDistanceMeters += dist
                        }
                    }
                }
                lastLocation = loc
            }
        }

        if (fusedClient != null) {
            try {
                val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
                    .setMinUpdateIntervalMillis(250L)
                    .build()
                fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
            } catch (_: Exception) {
                currentAddress = "GPS 서비스 초기화 대기"
            }
        }

        onDispose {
            try {
                fusedClient?.removeLocationUpdates(callback)
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(lastLocation) {
        val loc = lastLocation ?: return@LaunchedEffect
        val prevGeo = lastGeocodedLocation
        if (prevGeo == null || prevGeo.distanceTo(loc) > 200f) {
            lastGeocodedLocation = loc
            withContext(Dispatchers.IO) {
                try {
                    val geocoder = Geocoder(context, Locale.KOREA)
                    @Suppress("DEPRECATION")
                    val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                    if (!list.isNullOrEmpty()) {
                        val addr = list[0]
                        val admin = addr.adminArea ?: ""
                        val locality = addr.locality ?: addr.subAdminArea ?: ""
                        val subLocality = addr.subLocality ?: ""
                        val thoroughfare = addr.thoroughfare ?: ""
                        currentAddress = listOf(admin, locality, subLocality, thoroughfare)
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString(" ")
                    }
                } catch (_: Exception) {
                    currentAddress = String.format(Locale.US, "%.4f, %.4f", loc.latitude, loc.longitude)
                }
            }
        }
    }

    LaunchedEffect(lastLocation) {
        val loc = lastLocation ?: return@LaunchedEffect
        val nowMs = System.currentTimeMillis()
        val prevLoc = lastWeatherLocation
        val shouldFetch = prevLoc == null ||
            (nowMs - lastWeatherFetchMs > 15 * 60 * 1000L) ||
            (prevLoc.distanceTo(loc) > 5000f)

        if (shouldFetch) {
            lastWeatherLocation = loc
            lastWeatherFetchMs = nowMs
            withContext(Dispatchers.IO) {
                try {
                    val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&current_weather=true"
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        requestMethod = "GET"
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val current = JSONObject(body).getJSONObject("current_weather")
                        val temp = current.getDouble("temperature").roundToInt()
                        val code = current.getInt("weathercode")
                        val isDayFlag = current.optInt("is_day", if (initialIsDay) 1 else 0)
                        temperatureC = temp
                        liveWeather = mapWeatherCode(code)
                        liveIsDay = (isDayFlag == 1)
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    val toggleTheme = {
        val nextTheme = when (currentTheme) {
            ClusterTheme.ORIGINAL -> ClusterTheme.NEON_IONIQ
            ClusterTheme.NEON_IONIQ -> ClusterTheme.CHRISTMAS
            ClusterTheme.CHRISTMAS -> ClusterTheme.ORIGINAL
        }
        currentTheme = nextTheme
        prefs.edit().putString("last_theme", nextTheme.name).apply()
    }

    val toggleTestDrive = {
        if (!isTestDriving) {
            testSpeedKmh = smoothSpeedKmh
            testAccelerating = true
            isTestDriving = true
        } else {
            isTestDriving = false
        }
    }

    val isIoniqDayLight = (currentTheme == ClusterTheme.NEON_IONIQ && activeIsDay)
    val bgHudBitmap = ImageBitmap.imageResource(id = R.drawable.bg_hud_road)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isIoniqDayLight) Color(0xFFF3F6FA) else Color(0xFF000000))
    ) {
        val screenW = maxWidth
        val screenH = maxHeight

        if (currentTheme == ClusterTheme.ORIGINAL) {
            val bgWidthRatio = 0.88f
            val roadCenterInBg = 0.66f
            val carCenterXRatio = bgWidthRatio * roadCenterInBg
            val carCenterYRatio = 0.66f
            val speedCenterXRatio = 0.175f
            val speedCenterYRatio = 0.54f

            WeatherSkyBackground(
                weather = activeWeather,
                isDay = activeIsDay,
                elapsedSec = elapsedSec,
                modifier = Modifier.fillMaxSize()
            )

            Canvas(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(bgWidthRatio)
            ) {
                drawImage(
                    image = bgHudBitmap,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    blendMode = BlendMode.Screen
                )
            }

            SpeedGaugeNeedleOverlay(
                speed = smoothSpeedKmh,
                maxSpeed = 240f,
                centerXRatio = 0.198f,
                centerYRatio = 0.54f,
                startAngleDeg = -135f,
                sweepAngleDeg = 270f,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(bgWidthRatio)
            )

            AlignedForwardRoadOverlay(
                speed = smoothSpeedKmh,
                scrollPhase = roadScrollPhase,
                roadCenterInBg = roadCenterInBg,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(bgWidthRatio)
            )

            val carWidth = screenW * 0.175f
            val carHeight = carWidth * 0.68f
            Box(
                modifier = Modifier
                    .offset(
                        x = screenW * carCenterXRatio - carWidth / 2,
                        y = screenH * carCenterYRatio - carHeight / 2
                    )
                    .size(width = carWidth, height = carHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { toggleTheme() },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawOval(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.88f),
                            radius = size.width * 0.52f
                        ),
                        topLeft = Offset(size.width * 0.05f, size.height * 0.72f),
                        size = Size(size.width * 0.9f, size.height * 0.28f)
                    )
                }
                Image(
                    painter = painterResource(id = R.drawable.car_model3),
                    contentDescription = "Tesla Model 3 Highland",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            val speedBoxSize = 140.dp
            Box(
                modifier = Modifier
                    .offset(
                        x = screenW * speedCenterXRatio - speedBoxSize / 2,
                        y = screenH * speedCenterYRatio - speedBoxSize / 2
                    )
                    .size(speedBoxSize)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { toggleTestDrive() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$displayedSpeedInt",
                        color = Color.White,
                        fontSize = 60.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-2).sp
                    )
                    Text(
                        text = if (isTestDriving) "TEST" else "km/h",
                        color = if (isTestDriving) Color(0xFF00E676) else Color(0xFF94E2F5),
                        fontSize = 14.sp,
                        fontWeight = if (isTestDriving) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        } else if (currentTheme == ClusterTheme.NEON_IONIQ) {
            val bgTopColor by animateColorAsState(
                targetValue = if (activeIsDay) Color(0xFFEAF2FA) else Color(0xFF020408),
                animationSpec = tween(450), label = "t2_top"
            )
            val bgBottomColor by animateColorAsState(
                targetValue = if (activeIsDay) Color(0xFFFFFFFF) else Color(0xFF050A14),
                animationSpec = tween(450), label = "t2_bottom"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(bgTopColor, bgBottomColor)))
            ) {
                Theme2SkyBackground(
                    weather = activeWeather,
                    isDay = activeIsDay,
                    elapsedSec = elapsedSec,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.62f)
                )

                NeonHighwayRoadCanvas(
                    roadOffset = roadScrollPhase,
                    isDay = activeIsDay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.45f)
                        .align(Alignment.BottomCenter)
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp)
                        .size(width = 200.dp, height = 112.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { toggleTheme() },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawOval(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.Black.copy(alpha = if (activeIsDay) 0.35f else 0.85f), Color.Transparent),
                                center = Offset(size.width / 2f, size.height * 0.88f),
                                radius = size.width * 0.52f
                            ),
                            topLeft = Offset(size.width * 0.05f, size.height * 0.72f),
                            size = Size(size.width * 0.9f, size.height * 0.28f)
                        )
                    }
                    Image(
                        painter = painterResource(id = R.drawable.car_model3),
                        contentDescription = "Tesla Model 3 Highland",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                GradientSpeedometer240(
                    speed = smoothSpeedKmh,
                    displayedSpeedInt = displayedSpeedInt,
                    maxSpeed = 240f,
                    isDay = activeIsDay,
                    isTestDriving = isTestDriving,
                    onClickSpeedometer = toggleTestDrive,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp, top = 16.dp)
                        .size(275.dp)
                )

                CircularTripGauge(
                    distanceKm = totalDistanceMeters / 1000f,
                    isDay = activeIsDay,
                    onResetTrip = { totalDistanceMeters = 0f },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 24.dp, top = 16.dp)
                        .size(245.dp)
                )
            }
        } else {
            // =========================================================
            // [테마 3] 크리스마스 밤 테마 + 도로/썰매 모션 연동
            // =========================================================
            val xmasSpeedCenterX = 0.21f
            val xmasSpeedCenterY = 0.49f
            val xmasTripCenterX = 0.88f
            val xmasTripCenterY = 0.53f
            val sleighCenterX = 0.56f
            val sleighCenterY = 0.74f

            val motionAmp = (smoothSpeedKmh / 100f).coerceIn(0f, 1.4f)
            val sleighBobY = sin(elapsedSec * (3.2f + motionAmp * 5.0f)) * (2.5f + motionAmp * 5.5f)
            val sleighSwayX = cos(elapsedSec * (2.1f + motionAmp * 3.5f)) * (2.0f + motionAmp * 4.5f)
            val sleighTiltDeg = sin(elapsedSec * (2.8f + motionAmp * 4.0f)) * (1.2f + motionAmp * 2.5f)

            Box(modifier = Modifier.fillMaxSize()) {
                // 1) 크리스마스 배경 이미지
                Image(
                    painter = painterResource(id = R.drawable.bg_xmas_road),
                    contentDescription = "Christmas Drive Background",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )

                // 2) 속도에 맞춰 앞으로 흐르는 눈길/차선 모션 라인
                ChristmasMotionRoadOverlay(
                    speed = smoothSpeedKmh,
                    scrollPhase = roadScrollPhase,
                    elapsedSec = elapsedSec,
                    modifier = Modifier.fillMaxSize()
                )

                // 3) 좌측 캔디 케인 속도계 회전 바늘 (0 ~ 240 km/h)
                SpeedGaugeNeedleOverlay(
                    speed = smoothSpeedKmh,
                    maxSpeed = 240f,
                    centerXRatio = xmasSpeedCenterX,
                    centerYRatio = xmasSpeedCenterY,
                    startAngleDeg = -135f,
                    sweepAngleDeg = 270f,
                    modifier = Modifier.fillMaxSize()
                )

                // 4) 좌측 속도계 중앙 디지털 숫자 (터치 시 테스트 주행)
                val xmasSpeedBox = 140.dp
                Box(
                    modifier = Modifier
                        .offset(
                            x = screenW * xmasSpeedCenterX - xmasSpeedBox / 2,
                            y = screenH * xmasSpeedCenterY - xmasSpeedBox / 2
                        )
                        .size(xmasSpeedBox)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { toggleTestDrive() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$displayedSpeedInt",
                            color = Color.White,
                            fontSize = 54.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-2).sp
                        )
                        Text(
                            text = if (isTestDriving) "TEST" else "km/h",
                            color = if (isTestDriving) Color(0xFF00E676) else Color(0xFFFDE047),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 5) 우측 크리스마스 리스 중앙 Trip A (터치 시 초기화)
                val xmasTripBox = 130.dp
                Box(
                    modifier = Modifier
                        .offset(
                            x = screenW * xmasTripCenterX - xmasTripBox / 2,
                            y = screenH * xmasTripCenterY - xmasTripBox / 2
                        )
                        .size(xmasTripBox)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { totalDistanceMeters = 0f },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "TRIP A",
                            color = Color(0xFFFDE047),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = String.format(Locale.US, "%.1f", totalDistanceMeters / 1000f),
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "km",
                            color = Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "(터치 초기화)",
                            color = Color(0xFFCBD5E1).copy(alpha = 0.85f),
                            fontSize = 9.sp
                        )
                    }
                }

                // 6) 중앙 눈길 위 산타 썰매 모션 (터치 시 테마 전환)
                val sleighW = screenW * 0.24f
                val sleighH = sleighW * 0.68f
                Box(
                    modifier = Modifier
                        .offset(
                            x = screenW * sleighCenterX - sleighW / 2 + sleighSwayX.dp,
                            y = screenH * sleighCenterY - sleighH / 2 + sleighBobY.dp
                        )
                        .size(width = sleighW, height = sleighH)
                        .graphicsLayer {
                            rotationZ = sleighTiltDeg
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { toggleTheme() },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.sleigh_santa),
                        contentDescription = "Santa Sleigh",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        val effectWeather = if (currentTheme == ClusterTheme.CHRISTMAS) WeatherType.SNOW else activeWeather
        if (effectWeather == WeatherType.RAIN || effectWeather == WeatherType.SNOW) {
            WeatherPrecipitationOverlay(
                weather = effectWeather,
                isDay = activeIsDay,
                speed = smoothSpeedKmh,
                elapsedSec = elapsedSec,
                modifier = Modifier.fillMaxSize()
            )
        }

        val topPrimaryText = if (isIoniqDayLight) Color(0xFF161A20) else Color.White
        val topAccentText = if (isIoniqDayLight) Color(0xFF0284C7) else Color(0xFF22D3EE)
        val topChipBg = if (isIoniqDayLight) Color(0xD9FFFFFF) else Color(0x990F172A)
        val topChipBorder = if (isIoniqDayLight) Color(0x550284C7) else Color(0x5538BDF8)
        val addressTextColor = if (isIoniqDayLight) Color(0xFF475569) else Color(0xFF94A3B8)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentDateText,
                        color = if (currentTheme == ClusterTheme.CHRISTMAS) Color(0xFFFDE047) else topAccentText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = currentTimeText,
                        color = if (currentTheme == ClusterTheme.CHRISTMAS) Color(0xFFFFFBEB) else topPrimaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    val batColor = when {
                        isCharging -> if (currentTheme == ClusterTheme.CHRISTMAS) Color(0xFF4ADE80) else topAccentText
                        batteryPct <= 20 -> Color(0xFFFF5252)
                        else -> if (isIoniqDayLight) Color(0xFF0369A1) else Color(0xFF38BDF8)
                    }
                    Text(
                        text = if (isCharging) "⚡ $batteryPct%" else "🔋 $batteryPct%",
                        color = batColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dayNightLabel = if (activeIsDay) "☀️ 낮" else "🌙 밤"
                    val dayNightTag = if (manualIsDay != null) "*" else ""
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(topChipBg, RoundedCornerShape(12.dp))
                            .border(1.dp, topChipBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                manualIsDay = when (manualIsDay) {
                                    null -> !liveIsDay
                                    true -> false
                                    false -> null
                                }
                            }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$dayNightLabel$dayNightTag",
                            color = topPrimaryText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    val weatherIcon = if (activeIsDay) activeWeather.dayIcon else activeWeather.nightIcon
                    val tempText = temperatureC?.let { " ${it}°C" } ?: ""
                    val weatherTag = if (manualWeather != null) "*" else ""
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(topChipBg, RoundedCornerShape(12.dp))
                            .border(1.dp, topChipBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                manualWeather = when (manualWeather) {
                                    null -> WeatherType.CLEAR
                                    WeatherType.CLEAR -> WeatherType.CLOUDY
                                    WeatherType.CLOUDY -> WeatherType.RAIN
                                    WeatherType.RAIN -> WeatherType.SNOW
                                    WeatherType.SNOW -> null
                                }
                            }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (currentTheme == ClusterTheme.CHRISTMAS) "❄️ 눈$tempText" else "$weatherIcon ${activeWeather.label}$tempText$weatherTag",
                            color = topPrimaryText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(
                                if (lastLocation != null) Color(0xFF00E676) else Color(0xFFF59E0B),
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (lastLocation != null) "GPS" else "탐색중",
                        color = if (currentTheme == ClusterTheme.CHRISTMAS) Color(0xFF4ADE80) else topAccentText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (currentTheme == ClusterTheme.ORIGINAL) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(175.dp)
                            .background(Color(0xD910151E), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFF263142), RoundedCornerShape(16.dp))
                            .clickable { totalDistanceMeters = 0f }
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                    ) {
                        Text(
                            text = "Trip A",
                            color = Color(0xFF22D3EE),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = String.format(Locale.US, "%.1f", totalDistanceMeters / 1000f),
                                color = Color.White,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "km",
                                color = Color(0xFFCBD5E1),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "(터치하여 초기화)",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasLocationPermission) "📍 $currentAddress" else "📍 위치 권한을 허용해주세요",
                    color = if (currentTheme == ClusterTheme.CHRISTMAS) Color(0xFFE2E8F0) else addressTextColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * [테마 3] 눈길 원근감에 맞춘 전진 흐름선 및 난간 반짝임 모션
 */
@Composable
fun ChristmasMotionRoadOverlay(
    speed: Float,
    scrollPhase: Float,
    elapsedSec: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 난간 조명 은은한 반짝임 (정차 중에도 살아있는 모션)
        val sparkleCount = 12
        for (i in 0 until sparkleCount) {
            val t = i.toFloat() / sparkleCount
            val lx = w * (0.50f - t * 0.25f)
            val ly = h * (0.54f + t * 0.28f)
            val rx = w * (0.53f + t * 0.28f)
            val ry = h * (0.54f + t * 0.28f)
            val pulse = 0.35f + 0.65f * ((sin(elapsedSec * 4.5f + i * 1.1f) + 1f) * 0.5f)
            val r = (1.5f + t * 3.5f).dp.toPx()

            drawCircle(
                color = Color(0xFFFDE047).copy(alpha = pulse * 0.75f),
                center = Offset(lx, ly),
                radius = r
            )
            drawCircle(
                color = Color(0xFFFDE047).copy(alpha = pulse * 0.75f),
                center = Offset(rx, ry),
                radius = r
            )
        }

        if (speed <= 0.5f) return@Canvas

        val intensity = (speed / 120f).coerceIn(0.15f, 1.4f)
        val centerX = w * 0.52f
        val topY = h * 0.55f
        val botY = h * 0.96f
        val topHalfW = w * 0.025f
        val botHalfW = w * 0.32f

        val laneRatios = listOf(-0.55f, 0.55f)
        val dashCount = 8

        for (i in 0 until dashCount) {
            val rawT = ((i.toFloat() / dashCount) + scrollPhase) % 1f
            val p1 = rawT * rawT
            val dashLen = (0.045f + 0.04f * intensity) * (0.35f + rawT)
            val p2 = (rawT + dashLen).coerceAtMost(1f).let { it * it }

            val y1 = topY + (botY - topY) * p1
            val y2 = topY + (botY - topY) * p2
            if (y2 <= y1) continue

            val halfW1 = topHalfW + (botHalfW - topHalfW) * p1
            val halfW2 = topHalfW + (botHalfW - topHalfW) * p2

            val strokeW = ((1.4f + 3.2f * p1) * intensity).dp.toPx()
            val alpha = ((0.25f + 0.60f * p1) * intensity).coerceIn(0f, 0.9f)

            for (ratio in laneRatios) {
                drawLine(
                    color = Color(0xFFE0F2FE).copy(alpha = alpha),
                    start = Offset(centerX + halfW1 * ratio, y1),
                    end = Offset(centerX + halfW2 * ratio, y2),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun mapWeatherCode(code: Int): WeatherType = when (code) {
    0, 1 -> WeatherType.CLEAR
    2, 3, 45, 48 -> WeatherType.CLOUDY
    71, 73, 75, 77, 85, 86 -> WeatherType.SNOW
    else -> WeatherType.RAIN
}

private fun DrawScope.drawVolumetricCumulus(
    baseX: Float,
    baseY: Float,
    scale: Float,
    shadowColor: Color,
    bodyColor: Color,
    highlightColor: Color
) {
    val puffs = listOf(
        Triple(-56f, 12f, 34f),
        Triple(-26f, -8f, 44f),
        Triple(10f, -18f, 52f),
        Triple(46f, -4f, 42f),
        Triple(72f, 14f, 30f),
        Triple(2f, 14f, 40f)
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

@Composable
fun WeatherSkyBackground(
    weather: WeatherType,
    isDay: Boolean,
    elapsedSec: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val horizonY = h * 0.48f

        val skyColors = if (isDay) {
            when (weather) {
                WeatherType.CLEAR -> listOf(Color(0xFF1565C0), Color(0xFF42A5F5), Color(0xFF0D2E54), Color(0xFF000000))
                WeatherType.CLOUDY -> listOf(Color(0xFF334155), Color(0xFF475569), Color(0xFF1E293B), Color(0xFF000000))
                WeatherType.RAIN -> listOf(Color(0xFF1E293B), Color(0xFF334155), Color(0xFF0F172A), Color(0xFF000000))
                WeatherType.SNOW -> listOf(Color(0xFF475569), Color(0xFF64748B), Color(0xFF1E293B), Color(0xFF000000))
            }
        } else {
            when (weather) {
                WeatherType.CLEAR -> listOf(Color(0xFF040A17), Color(0xFF0C1B36), Color(0xFF050A14), Color(0xFF000000))
                WeatherType.CLOUDY -> listOf(Color(0xFF0C121D), Color(0xFF1A2433), Color(0xFF070B12), Color(0xFF000000))
                WeatherType.RAIN -> listOf(Color(0xFF070B12), Color(0xFF121A26), Color(0xFF04060A), Color(0xFF000000))
                WeatherType.SNOW -> listOf(Color(0xFF0E1724), Color(0xFF1E2C3D), Color(0xFF080D14), Color(0xFF000000))
            }
        }

        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to skyColors[0],
                    0.26f to skyColors[1],
                    0.46f to skyColors[2],
                    0.62f to skyColors[3]
                ),
                startY = 0f,
                endY = h
            )
        )

        if (isDay) {
            when (weather) {
                WeatherType.CLEAR -> {
                    val sunCenter = Offset(w * 0.62f, h * 0.17f)
                    val pulse = 1f + sin(elapsedSec * 1.5f) * 0.04f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFFF9C4).copy(alpha = 0.98f),
                                Color(0xFFFFD54F).copy(alpha = 0.55f),
                                Color(0xFF64B5F6).copy(alpha = 0.18f),
                                Color.Transparent
                            ),
                            center = sunCenter,
                            radius = h * 0.26f * pulse
                        ),
                        center = sunCenter,
                        radius = h * 0.26f * pulse
                    )
                    drawCircle(color = Color(0xFFFFFDE7), center = sunCenter, radius = h * 0.044f)

                    val drift = (elapsedSec * 14f) % (w + 340f)
                    val whiteShadow = Color(0xFFCFDCEB).copy(alpha = 0.65f)
                    val whiteBody = Color(0xFFF8FAFC).copy(alpha = 0.90f)
                    val whiteHi = Color(0xFFFFFFFF).copy(alpha = 0.98f)
                    drawVolumetricCumulus(((drift) % (w + 340f)) - 170f, h * 0.20f, 0.85f, whiteShadow, whiteBody, whiteHi)
                    drawVolumetricCumulus(((drift + (w + 340f) * 0.48f) % (w + 340f)) - 170f, h * 0.15f, 0.72f, whiteShadow, whiteBody, whiteHi)
                }

                WeatherType.CLOUDY, WeatherType.RAIN, WeatherType.SNOW -> {
                    val (shadowC, bodyC, hiC) = when (weather) {
                        WeatherType.SNOW -> Triple(
                            Color(0xFF475569).copy(alpha = 0.80f),
                            Color(0xFF94A3B8).copy(alpha = 0.85f),
                            Color(0xFFCBD5E1).copy(alpha = 0.85f)
                        )
                        else -> Triple(
                            Color(0xFF1A232E).copy(alpha = 0.92f),
                            Color(0xFF334155).copy(alpha = 0.90f),
                            Color(0xFF64748B).copy(alpha = 0.72f)
                        )
                    }
                    val drift = (elapsedSec * 18f) % (w + 340f)
                    for (i in 0..3) {
                        val bx = ((i * w * 0.30f) + drift) % (w + 340f) - 170f
                        val by = h * (0.14f + (i % 2) * 0.08f)
                        drawVolumetricCumulus(bx, by, 1.05f + (i % 2) * 0.25f, shadowC, bodyC, hiC)
                    }
                }
            }
        } else {
            when (weather) {
                WeatherType.CLEAR -> {
                    val starCount = 34
                    for (i in 0 until starCount) {
                        val sx = ((i * 173.3f) % 0.94f + 0.03f) * w
                        val sy = ((i * 89.7f) % 0.38f + 0.03f) * h
                        val twinkle = (0.35f + 0.65f * ((sin(elapsedSec * (1.5f + (i % 4) * 0.7f) + i) + 1f) * 0.5f))
                        val radius = (0.9f + (i % 3) * 0.6f).dp.toPx()
                        drawCircle(color = Color(0xFFE2F1FF).copy(alpha = twinkle), center = Offset(sx, sy), radius = radius)
                    }

                    val moonCenter = Offset(w * 0.63f, h * 0.17f)
                    val moonR = h * 0.048f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFFFF8DC).copy(alpha = 0.55f), Color(0xFF93C5FD).copy(alpha = 0.22f), Color.Transparent),
                            center = moonCenter,
                            radius = moonR * 4.2f
                        ),
                        center = moonCenter,
                        radius = moonR * 4.2f
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFFFFBEB), Color(0xFFFEF08A), Color(0xFFE2E8F0)),
                            center = Offset(moonCenter.x - moonR * 0.2f, moonCenter.y - moonR * 0.2f),
                            radius = moonR * 1.15f
                        ),
                        center = moonCenter,
                        radius = moonR
                    )
                    drawCircle(color = Color(0xFFD6D3C4).copy(alpha = 0.35f), center = Offset(moonCenter.x + moonR * 0.28f, moonCenter.y - moonR * 0.12f), radius = moonR * 0.22f)
                    drawCircle(color = Color(0xFFD6D3C4).copy(alpha = 0.28f), center = Offset(moonCenter.x - moonR * 0.22f, moonCenter.y + moonR * 0.25f), radius = moonR * 0.16f)
                }

                WeatherType.CLOUDY, WeatherType.RAIN, WeatherType.SNOW -> {
                    val (shadowC, bodyC, hiC) = Triple(
                        Color(0xFF0F172A).copy(alpha = 0.88f),
                        Color(0xFF1E293B).copy(alpha = 0.85f),
                        Color(0xFF334155).copy(alpha = 0.60f)
                    )
                    val drift = (elapsedSec * 14f) % (w + 340f)
                    for (i in 0..3) {
                        val bx = ((i * w * 0.30f) + drift) % (w + 340f) - 170f
                        val by = h * (0.14f + (i % 2) * 0.08f)
                        drawVolumetricCumulus(bx, by, 1.05f + (i % 2) * 0.25f, shadowC, bodyC, hiC)
                    }
                }
            }
        }

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                startY = horizonY * 0.7f,
                endY = horizonY * 1.25f
            ),
            topLeft = Offset(0f, horizonY * 0.7f),
            size = Size(w, horizonY * 0.55f)
        )
    }
}

@Composable
fun Theme2SkyBackground(
    weather: WeatherType,
    isDay: Boolean,
    elapsedSec: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val skyColors = if (isDay) {
            when (weather) {
                WeatherType.CLEAR -> listOf(Color(0xFF64B5F6), Color(0xFFBBDEFB), Color(0xFFF5FAFF))
                WeatherType.CLOUDY -> listOf(Color(0xFF64748B), Color(0xFF94A3B8), Color(0xFFE2E8F0))
                WeatherType.RAIN -> listOf(Color(0xFF475569), Color(0xFF78909C), Color(0xFFCBD5E1))
                WeatherType.SNOW -> listOf(Color(0xFF94A3B8), Color(0xFFCBD5E1), Color(0xFFF8FAFC))
            }
        } else {
            when (weather) {
                WeatherType.CLEAR -> listOf(Color(0xFF000000), Color(0xFF040B1A))
                WeatherType.CLOUDY -> listOf(Color(0xFF090E17), Color(0xFF162032))
                WeatherType.RAIN -> listOf(Color(0xFF05080F), Color(0xFF0E1624))
                WeatherType.SNOW -> listOf(Color(0xFF0A111D), Color(0xFF1A2638))
            }
        }
        drawRect(brush = Brush.verticalGradient(skyColors))

        if (isDay) {
            if (weather == WeatherType.CLEAR) {
                val sunCenter = Offset(w * 0.65f, h * 0.22f)
                val pulse = 1f + sin(elapsedSec * 1.4f) * 0.03f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFF59D).copy(alpha = 0.90f),
                            Color(0xFFFFD54F).copy(alpha = 0.42f),
                            Color(0xFF90CAF9).copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        center = sunCenter,
                        radius = h * 0.36f * pulse
                    ),
                    center = sunCenter,
                    radius = h * 0.36f * pulse
                )
                drawCircle(
                    color = Color(0xFFFFFDE7),
                    center = sunCenter,
                    radius = h * 0.062f
                )

                val whiteShadow = Color(0xFFCADAE8).copy(alpha = 0.65f)
                val whiteBody = Color(0xFFF5F9FD).copy(alpha = 0.94f)
                val whiteHi = Color(0xFFFFFFFF).copy(alpha = 1.0f)

                val drift = (elapsedSec * 13f) % (w + 320f)
                val shift1 = drift - 160f
                val shift2 = ((drift + (w + 320f) * 0.45f) % (w + 320f)) - 160f

                drawVolumetricCumulus(shift1, h * 0.26f, 0.95f, whiteShadow, whiteBody, whiteHi)
                drawVolumetricCumulus(shift2, h * 0.18f, 0.80f, whiteShadow, whiteBody, whiteHi)
            } else {
                val (shadowC, bodyC, hiC) = when (weather) {
                    WeatherType.SNOW -> Triple(
                        Color(0xFF64748B).copy(alpha = 0.80f),
                        Color(0xFF94A3B8).copy(alpha = 0.88f),
                        Color(0xFFE2E8F0).copy(alpha = 0.90f)
                    )
                    WeatherType.RAIN -> Triple(
                        Color(0xFF1E293B).copy(alpha = 0.92f),
                        Color(0xFF334155).copy(alpha = 0.90f),
                        Color(0xFF64748B).copy(alpha = 0.78f)
                    )
                    else -> Triple(
                        Color(0xFF283546).copy(alpha = 0.90f),
                        Color(0xFF475569).copy(alpha = 0.88f),
                        Color(0xFF78909C).copy(alpha = 0.78f)
                    )
                }
                val drift = (elapsedSec * 16f) % (w + 320f)
                val shift1 = drift - 160f
                val shift2 = ((drift + (w + 320f) * 0.30f) % (w + 320f)) - 160f
                val shift3 = ((drift + (w + 320f) * 0.62f) % (w + 320f)) - 160f
                val shift4 = ((drift + (w + 320f) * 0.85f) % (w + 320f)) - 160f

                drawVolumetricCumulus(shift1, h * 0.24f, 1.20f, shadowC, bodyC, hiC)
                drawVolumetricCumulus(shift2, h * 0.16f, 1.05f, shadowC, bodyC, hiC)
                drawVolumetricCumulus(shift3, h * 0.29f, 1.30f, shadowC, bodyC, hiC)
                drawVolumetricCumulus(shift4, h * 0.20f, 1.10f, shadowC, bodyC, hiC)
            }
        } else {
            if (weather == WeatherType.CLEAR) {
                for (i in 0 until 26) {
                    val sx = ((i * 173.3f) % 0.94f + 0.03f) * w
                    val sy = ((i * 89.7f) % 0.45f + 0.04f) * h
                    val twinkle = (0.35f + 0.65f * ((sin(elapsedSec * (1.5f + (i % 4) * 0.7f) + i) + 1f) * 0.5f))
                    drawCircle(
                        color = Color(0xFFE2F1FF).copy(alpha = twinkle),
                        center = Offset(sx, sy),
                        radius = (0.9f + (i % 3) * 0.5f).dp.toPx()
                    )
                }
                val moonCenter = Offset(w * 0.65f, h * 0.22f)
                val moonR = h * 0.058f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFFF8DC).copy(alpha = 0.45f), Color.Transparent),
                        center = moonCenter,
                        radius = moonR * 3.5f
                    ),
                    center = moonCenter,
                    radius = moonR * 3.5f
                )
                drawCircle(color = Color(0xFFFFFBEB), center = moonCenter, radius = moonR)
            } else {
                val shadowC = Color(0xFF0B111C).copy(alpha = 0.88f)
                val bodyC = Color(0xFF1E293B).copy(alpha = 0.85f)
                val hiC = Color(0xFF334155).copy(alpha = 0.68f)
                val drift = (elapsedSec * 14f) % (w + 320f)
                val shift1 = drift - 160f
                val shift2 = ((drift + (w + 320f) * 0.35f) % (w + 320f)) - 160f
                val shift3 = ((drift + (w + 320f) * 0.68f) % (w + 320f)) - 160f

                drawVolumetricCumulus(shift1, h * 0.25f, 1.20f, shadowC, bodyC, hiC)
                drawVolumetricCumulus(shift2, h * 0.18f, 1.00f, shadowC, bodyC, hiC)
                drawVolumetricCumulus(shift3, h * 0.30f, 1.30f, shadowC, bodyC, hiC)
            }
        }
    }
}

@Composable
fun NeonHighwayRoadCanvas(
    roadOffset: Float,
    isDay: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val horizonY = 0f
        val centerX = w / 2f

        val roadTop = if (isDay) Color(0xFFD5E3F2) else Color(0xFF040914)
        val roadBottom = if (isDay) Color(0xFFECF3FA) else Color(0xFF071529)
        drawRect(brush = Brush.verticalGradient(listOf(roadTop, roadBottom)))

        val glowColor = if (isDay) Color(0xFF0088FF).copy(alpha = 0.22f) else Color(0xFF00B4FF).copy(alpha = 0.38f)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, glowColor, Color.Transparent),
                startX = centerX - w * 0.16f,
                endX = centerX + w * 0.16f
            )
        )

        val railColor = if (isDay) Color(0xFF0077CC) else Color(0xFF00C8FF)
        drawLine(railColor, Offset(centerX - 18f, horizonY), Offset(0f, h * 0.34f), strokeWidth = 4.dp.toPx())
        drawLine(railColor.copy(alpha = 0.7f), Offset(centerX - 18f, horizonY + 8f), Offset(0f, h * 0.50f), strokeWidth = 2.5.dp.toPx())
        drawLine(railColor, Offset(centerX + 18f, horizonY), Offset(w, h * 0.34f), strokeWidth = 4.dp.toPx())
        drawLine(railColor.copy(alpha = 0.7f), Offset(centerX + 18f, horizonY + 8f), Offset(w, h * 0.50f), strokeWidth = 2.5.dp.toPx())

        val lightCount = 9
        for (i in 0 until lightCount) {
            val tRaw = (i.toFloat() / lightCount + roadOffset) % 1f
            val t = tRaw * tRaw
            val y = horizonY + t * h * 0.44f
            val leftX = (centerX - 18f) * (1f - t)
            val rightX = (centerX + 18f) + (w - (centerX + 18f)) * t
            val lampW = (3f + 16f * t).dp.toPx()
            val lampH = (1.5f + 4.5f * t).dp.toPx()

            val lampColor = if (isDay) Color(0xFF0095FF) else Color(0xFFD6F7FF)
            drawRoundRect(
                color = lampColor,
                topLeft = Offset(leftX - lampW / 2f, y),
                size = Size(lampW, lampH),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawRoundRect(
                color = lampColor,
                topLeft = Offset(rightX - lampW / 2f, y),
                size = Size(lampW, lampH),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }

        val laneColor = if (isDay) Color(0xFF0084E8) else Color(0xFF38D4FF)
        val dashCount = 7
        for (i in 0 until dashCount) {
            val tStartRaw = (i.toFloat() / dashCount + roadOffset) % 1f
            val tEndRaw = (tStartRaw + 0.07f).coerceAtMost(1f)
            val t1 = tStartRaw * tStartRaw
            val t2 = tEndRaw * tEndRaw

            val y1 = horizonY + t1 * h
            val y2 = horizonY + t2 * h

            val leftX1 = centerX - (12f + t1 * w * 0.24f)
            val leftX2 = centerX - (12f + t2 * w * 0.24f)
            val rightX1 = centerX + (12f + t1 * w * 0.24f)
            val rightX2 = centerX + (12f + t2 * w * 0.24f)

            val strokeW = (2f + t1 * 7f).dp.toPx()
            drawLine(color = laneColor, start = Offset(leftX1, y1), end = Offset(leftX2, y2), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(color = laneColor, start = Offset(rightX1, y1), end = Offset(rightX2, y2), strokeWidth = strokeW, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun GradientSpeedometer240(
    speed: Float,
    displayedSpeedInt: Int,
    maxSpeed: Float = 240f,
    isDay: Boolean,
    isTestDriving: Boolean,
    onClickSpeedometer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = (speed / maxSpeed).coerceIn(0f, 1f)
    val startAngle = 135f
    val maxSweep = 270f
    val currentSweep = progress * maxSweep

    val mainTextColor = if (isDay) Color(0xFF161A20) else Color.White
    val subTextColor = if (isDay) Color(0xFF5D6673) else Color(0xFFAAB4C2)
    val glowPrimary = if (isDay) Color(0xFF007BFF) else Color(0xFF00D4FF)
    val ringColor = if (isDay) Color(0xFF0077E6) else Color(0xFF2EE6FF)
    val trackColor = if (isDay) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.14f)
    val centerFadeColor = if (isDay) Color(0xFFF6F8FB) else Color(0xFF030710)

    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onClickSpeedometer() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 14.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = maxSweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            )

            if (currentSweep > 0.5f) {
                val sweepFrac = (currentSweep / 360f).coerceIn(0.01f, 0.76f)
                rotate(degrees = startAngle, pivot = center) {
                    val sweepBrush = Brush.sweepGradient(
                        0.0f to glowPrimary.copy(alpha = 0.06f),
                        (sweepFrac * 0.55f) to glowPrimary.copy(alpha = 0.34f),
                        sweepFrac to glowPrimary.copy(alpha = 0.78f),
                        (sweepFrac + 0.005f).coerceAtMost(1f) to Color.Transparent,
                        center = center
                    )
                    drawArc(
                        brush = sweepBrush,
                        startAngle = 0f,
                        sweepAngle = currentSweep,
                        useCenter = true,
                        topLeft = arcTopLeft,
                        size = arcSize
                    )

                    drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to centerFadeColor.copy(alpha = 0.92f),
                            0.46f to centerFadeColor.copy(alpha = 0.78f),
                            0.84f to Color.Transparent,
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )

                    drawArc(
                        brush = Brush.sweepGradient(
                            0.0f to ringColor.copy(alpha = 0.25f),
                            sweepFrac to ringColor,
                            (sweepFrac + 0.005f).coerceAtMost(1f) to Color.Transparent,
                            center = center
                        ),
                        startAngle = 0f,
                        sweepAngle = currentSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round)
                    )

                    val endRad = Math.toRadians(currentSweep.toDouble())
                    val innerR = radius * 0.62f
                    val pStart = Offset(
                        center.x + (cos(endRad) * innerR).toFloat(),
                        center.y + (sin(endRad) * innerR).toFloat()
                    )
                    val pEnd = Offset(
                        center.x + (cos(endRad) * radius).toFloat(),
                        center.y + (sin(endRad) * radius).toFloat()
                    )
                    drawLine(
                        color = if (isDay) Color(0xFF0055CC) else Color.White,
                        start = pStart,
                        end = pEnd,
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            val tickPaint = Paint().apply {
                color = if (isDay) android.graphics.Color.parseColor("#0055AA") else android.graphics.Color.parseColor("#B8F3FF")
                textSize = 10.sp.toPx()
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }

            for (v in 0..240 step 10) {
                val isMajor = v % 20 == 0
                val angleDeg = startAngle + (v / maxSpeed) * maxSweep
                val rad = Math.toRadians(angleDeg.toDouble())

                val outerR = radius - 3.dp.toPx()
                val innerR = outerR - if (isMajor) 9.dp.toPx() else 4.5.dp.toPx()

                val tickCol = if (v <= speed) ringColor else trackColor.copy(alpha = 0.55f)
                drawLine(
                    color = tickCol,
                    start = Offset(center.x + (cos(rad) * innerR).toFloat(), center.y + (sin(rad) * innerR).toFloat()),
                    end = Offset(center.x + (cos(rad) * outerR).toFloat(), center.y + (sin(rad) * outerR).toFloat()),
                    strokeWidth = if (isMajor) 2.dp.toPx() else 1.2.dp.toPx()
                )

                if (isMajor) {
                    val labelR = radius - 21.dp.toPx()
                    val lx = center.x + (cos(rad) * labelR).toFloat()
                    val ly = center.y + (sin(rad) * labelR).toFloat() + 3.5.dp.toPx()
                    drawContext.canvas.nativeCanvas.drawText(v.toString(), lx, ly, tickPaint)
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "D",
                color = mainTextColor,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isDay) "Day Mode" else "Night Mode",
                color = subTextColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$displayedSpeedInt",
                color = mainTextColor,
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = (-2).sp
            )
            Text(
                text = if (isTestDriving) "TEST" else "km/h",
                color = if (isTestDriving) Color(0xFF00C853) else subTextColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif
            )
        }
    }
}

@Composable
fun CircularTripGauge(
    distanceKm: Float,
    isDay: Boolean,
    onResetTrip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mainTextColor = if (isDay) Color(0xFF161A20) else Color.White
    val subTextColor = if (isDay) Color(0xFF5D6673) else Color(0xFFAAB4C2)
    val accentColor = if (isDay) Color(0xFF0077E6) else Color(0xFF00D4FF)
    val trackColor = if (isDay) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.14f)

    val tripProgress = ((distanceKm % 100f) / 100f).coerceIn(0f, 1f)

    Box(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onResetTrip() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 14.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            drawArc(
                color = trackColor,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            )

            drawArc(
                color = accentColor,
                startAngle = 135f,
                sweepAngle = 270f * tripProgress,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "TRIP A",
                color = accentColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = String.format(Locale.US, "%.1f", distanceKm),
                color = mainTextColor,
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
            Text(
                text = "km",
                color = subTextColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "(터치하여 초기화)",
                color = subTextColor.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun WeatherPrecipitationOverlay(
    weather: WeatherType,
    isDay: Boolean,
    speed: Float,
    elapsedSec: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val speedFactor = (speed / 120f).coerceIn(0f, 1.5f)

        if (weather == WeatherType.RAIN) {
            val dropCount = 58
            val rainColor = if (isDay) Color(0xFF0284C7) else Color(0xFF93C5FD)
            for (i in 0 until dropCount) {
                val seed = i * 97.31f
                val fallSpeed = 0.75f + (i % 5) * 0.14f + speedFactor * 0.35f
                val progress = ((elapsedSec * fallSpeed) + (seed % 1f)) % 1f
                val x = ((seed * 13.7f) % w) - progress * (18f + speedFactor * 45f)
                val y = progress * h
                val len = 14f + (i % 4) * 5f + speedFactor * 10f
                val slant = 4f + speedFactor * 10f

                drawLine(
                    color = rainColor.copy(alpha = 0.32f + (i % 3) * 0.12f),
                    start = Offset(x, y),
                    end = Offset(x - slant, y + len),
                    strokeWidth = 1.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        } else if (weather == WeatherType.SNOW) {
            val flakeCount = 45
            val snowColor = if (isDay) Color(0xFFCBD5E1) else Color.White
            for (i in 0 until flakeCount) {
                val seed = i * 61.17f
                val fallSpeed = 0.14f + (i % 4) * 0.04f + speedFactor * 0.22f
                val progress = ((elapsedSec * fallSpeed) + (seed % 1f)) % 1f
                val sway = sin(elapsedSec * 2f + i) * 18f
                val x = ((seed * 19.3f) % w) + sway
                val y = progress * h
                val r = (1.8f + (i % 3) * 1.1f).dp.toPx()

                drawCircle(
                    color = snowColor.copy(alpha = 0.55f + (i % 3) * 0.15f),
                    center = Offset(x, y),
                    radius = r
                )
            }
        }
    }
}

@Composable
fun SpeedGaugeNeedleOverlay(
    speed: Float,
    maxSpeed: Float,
    centerXRatio: Float = 0.198f,
    centerYRatio: Float = 0.54f,
    startAngleDeg: Float = -135f,
    sweepAngleDeg: Float = 270f,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val cx = w * centerXRatio
        val cy = h * centerYRatio
        val radius = minOf(w * 0.175f, h * 0.34f)

        val progress = (speed / maxSpeed).coerceIn(0f, 1f)
        val angleDeg = startAngleDeg + (sweepAngleDeg * progress)

        rotate(degrees = angleDeg, pivot = Offset(cx, cy)) {
            val glowStartY = cy - radius * 0.72f
            val tipY = cy - radius * 1.02f

            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF22D3EE).copy(alpha = 0.0f),
                        Color(0xFF22D3EE).copy(alpha = 0.55f),
                        Color(0xFFE0F2FE).copy(alpha = 0.9f)
                    ),
                    startY = glowStartY,
                    endY = tipY
                ),
                start = Offset(cx, glowStartY),
                end = Offset(cx, tipY),
                strokeWidth = 9.dp.toPx(),
                cap = StrokeCap.Round
            )

            val needlePath = Path().apply {
                moveTo(cx - 3.2.dp.toPx(), cy - radius * 0.74f)
                lineTo(cx + 3.2.dp.toPx(), cy - radius * 0.74f)
                lineTo(cx + 1.1.dp.toPx(), tipY)
                lineTo(cx - 1.1.dp.toPx(), tipY)
                close()
            }
            drawPath(
                path = needlePath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF38BDF8), Color.White),
                    startY = glowStartY,
                    endY = tipY
                )
            )
        }
    }
}

@Composable
fun AlignedForwardRoadOverlay(
    speed: Float,
    scrollPhase: Float,
    roadCenterInBg: Float,
    modifier: Modifier = Modifier
) {
    if (speed <= 0.5f) return

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val intensity = (speed / 120f).coerceIn(0.12f, 1.35f)

        val centerX = w * roadCenterInBg
        val topY = h * 0.335f
        val botY = h * 0.77f
        val topHalfW = w * 0.032f
        val botHalfW = w * 0.34f

        val innerLaneRatios = listOf(-0.46f, 0.46f)
        val outerRailRatios = listOf(-0.96f, 0.96f)
        val dashCount = 7

        for (i in 0 until dashCount) {
            val rawT = ((i.toFloat() / dashCount) + scrollPhase) % 1f
            val p1 = rawT * rawT
            val dashLen = (0.045f + 0.045f * intensity) * (0.35f + rawT)
            val p2 = (rawT + dashLen).coerceAtMost(1f).let { it * it }

            val y1 = topY + (botY - topY) * p1
            val y2 = topY + (botY - topY) * p2
            if (y2 <= y1) continue

            val halfW1 = topHalfW + (botHalfW - topHalfW) * p1
            val halfW2 = topHalfW + (botHalfW - topHalfW) * p2

            val innerAlpha = ((0.22f + 0.55f * p1) * intensity).coerceIn(0f, 0.85f)
            val innerStroke = ((1.2f + 2.4f * p1) * (0.8f + 0.3f * intensity)).dp.toPx()
            for (ratio in innerLaneRatios) {
                drawLine(
                    color = Color(0xFF67E8F9).copy(alpha = innerAlpha),
                    start = Offset(centerX + halfW1 * ratio, y1),
                    end = Offset(centerX + halfW2 * ratio, y2),
                    strokeWidth = innerStroke,
                    cap = StrokeCap.Round
                )
            }

            val outerAlpha = ((0.14f + 0.36f * p1) * intensity).coerceIn(0f, 0.6f)
            val outerStroke = ((1.0f + 1.8f * p1) * (0.75f + 0.25f * intensity)).dp.toPx()
            for (ratio in outerRailRatios) {
                drawLine(
                    color = Color(0xFFBAE6FD).copy(alpha = outerAlpha),
                    start = Offset(centerX + halfW1 * ratio, y1),
                    end = Offset(centerX + halfW2 * ratio, y2),
                    strokeWidth = outerStroke,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
