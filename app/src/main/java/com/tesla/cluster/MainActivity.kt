package com.tesla.cluster

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

enum class WeatherType(val label: String, val icon: String) {
    CLEAR("맑음", "☀️"),
    CLOUDY("구름", "☁️"),
    RAIN("비", "🌧️"),
    SNOW("눈", "❄️")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱 실행 중 화면 꺼짐 방지
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

    var gpsSpeedKmh by remember { mutableFloatStateOf(0f) }
    var isTestDriving by remember { mutableStateOf(false) }
    var testSpeedKmh by remember { mutableFloatStateOf(0f) }
    var testAccelerating by remember { mutableStateOf(true) }

    var smoothSpeedKmh by remember { mutableFloatStateOf(0f) }
    var displayedSpeedInt by remember { mutableIntStateOf(0) }
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

    // 날씨 상태 (실시간 + 수동 테스트 오버라이드)
    var liveWeather by remember { mutableStateOf(WeatherType.CLEAR) }
    var manualWeather by remember { mutableStateOf<WeatherType?>(null) }
    var temperatureC by remember { mutableStateOf<Int?>(null) }
    var lastWeatherLocation by remember { mutableStateOf<Location?>(null) }
    var lastWeatherFetchMs by remember { mutableLongStateOf(0L) }

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

    // 1. 시계 (1초 갱신)
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

    // 2. 60fps 루프: 테스트 주행(0~200) + 부드러운 속도 보간 + 1씩 순차 숫자 변경 + 전진 도로/날씨 애니메이션
    LaunchedEffect(Unit) {
        var lastFrameNs = System.nanoTime()
        var digitStepAccumSec = 0f

        while (true) {
            val nowNs = System.nanoTime()
            val dtSec = ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
            lastFrameNs = nowNs
            elapsedSec += dtSec

            if (isTestDriving) {
                val rate = if (testAccelerating) 32f else 38f
                if (testAccelerating) {
                    testSpeedKmh = (testSpeedKmh + rate * dtSec).coerceAtMost(200f)
                    if (testSpeedKmh >= 200f) testAccelerating = false
                } else {
                    testSpeedKmh = (testSpeedKmh - rate * dtSec).coerceAtLeast(0f)
                    if (testSpeedKmh <= 0f) testAccelerating = true
                }
            } else {
                testSpeedKmh = 0f
            }

            val rawTarget = if (isTestDriving) testSpeedKmh else gpsSpeedKmh
            val clampedTarget = rawTarget.coerceIn(0f, 200f)

            val diff = clampedTarget - smoothSpeedKmh
            if (abs(diff) < 0.01f) {
                smoothSpeedKmh = clampedTarget
            } else {
                val alpha = (1f - exp(-6.5f * dtSec)).coerceIn(0f, 1f)
                smoothSpeedKmh += diff * alpha
            }

            val desiredInt = clampedTarget.toInt()
            if (displayedSpeedInt != desiredInt) {
                val stepInterval = (0.060f - (abs(desiredInt - displayedSpeedInt).coerceAtMost(40) * 0.0009f))
                    .coerceIn(0.022f, 0.060f)
                digitStepAccumSec += dtSec
                while (digitStepAccumSec >= stepInterval && displayedSpeedInt != desiredInt) {
                    digitStepAccumSec -= stepInterval
                    if (displayedSpeedInt < desiredInt) {
                        displayedSpeedInt += 1
                    } else {
                        displayedSpeedInt -= 1
                    }
                }
            } else {
                digitStepAccumSec = 0f
            }

            if (smoothSpeedKmh > 0.5f) {
                val flowRate = (smoothSpeedKmh / 36f) * dtSec
                roadScrollPhase = (roadScrollPhase + flowRate) % 1f
            }

            delay(16L)
        }
    }

    // 3. 배터리 잔량 수신
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

    // 4. GPS 속도 및 주행거리 계산
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

    // 5. 위치 주소 변환 (시/구/동·도로명까지 표시, 200m 이동 시 갱신)
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

    // 6. 실시간 날씨 조회 (Open-Meteo 무료 API, 15분 또는 5km 이동 시 갱신)
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
                        temperatureC = temp
                        liveWeather = mapWeatherCode(code)
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    // 배경 도로 중심축과 차량 위치를 하나의 기준으로 동기화
    val bgWidthRatio = 0.88f
    val roadCenterInBg = 0.66f
    val carCenterXRatio = bgWidthRatio * roadCenterInBg
    val carCenterYRatio = 0.66f
    val speedCenterXRatio = 0.175f
    val speedCenterYRatio = 0.54f

    val bgHudBitmap = ImageBitmap.imageResource(id = R.drawable.bg_hud_road)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        val screenW = maxWidth
        val screenH = maxHeight

        // 0) 실시간 날씨 연동 하늘 배경 (맑음/구름/비/눈)
        WeatherSkyBackground(
            weather = activeWeather,
            elapsedSec = elapsedSec,
            modifier = Modifier.fillMaxSize()
        )

        // 1) 네온 시안 속도계 + 도로 오버레이 (BlendMode.Screen으로 검정 배경을 투명 합성)
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

        // 2) 속도에 맞춰 회전하는 속도계 바늘 오버레이 (최고 속도 200 km/h)
        SpeedGaugeNeedleOverlay(
            speed = smoothSpeedKmh,
            maxSpeed = 200f,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(bgWidthRatio)
        )

        // 3) 배경 도로 소실점·기울기 및 차량 중심에 정확히 정렬된 전진 점선
        AlignedForwardRoadOverlay(
            speed = smoothSpeedKmh,
            scrollPhase = roadScrollPhase,
            roadCenterInBg = roadCenterInBg,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(bgWidthRatio)
        )

        // 4) 차량 바닥 그림자 + 테슬라 모델 3 하이랜드 리어뷰 (도로 중심축에 고정)
        val carWidth = screenW * 0.175f
        val carHeight = carWidth * 0.68f
        Box(
            modifier = Modifier
                .offset(
                    x = screenW * carCenterXRatio - carWidth / 2,
                    y = screenH * carCenterYRatio - carHeight / 2
                )
                .size(width = carWidth, height = carHeight),
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

        // 4-1) 비/눈 전경 파티클 효과 (속도에 따라 빗줄기 각도 반응)
        if (activeWeather == WeatherType.RAIN || activeWeather == WeatherType.SNOW) {
            WeatherPrecipitationOverlay(
                weather = activeWeather,
                speed = smoothSpeedKmh,
                elapsedSec = elapsedSec,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 5) 좌측 속도계 링 중앙 실시간 숫자 (터치 시 테스트 주행 토글)
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
                ) {
                    if (!isTestDriving) {
                        testSpeedKmh = displayedSpeedInt.toFloat()
                        testAccelerating = true
                        isTestDriving = true
                    } else {
                        isTestDriving = false
                    }
                },
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

        // 6) 상단 바 / 우측 Trip A / 하단 주소 UI 오버레이
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 상단 바
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentDateText,
                        color = Color(0xFF22D3EE),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = currentTimeText,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    val batColor = when {
                        isCharging -> Color(0xFF22D3EE)
                        batteryPct <= 20 -> Color(0xFFFF5252)
                        else -> Color(0xFF38BDF8)
                    }
                    Text(
                        text = if (isCharging) "⚡ $batteryPct%" else "🔋 $batteryPct%",
                        color = batColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 우측 상단: 날씨 표시(터치로 맑음/구름/비/눈 테스트 가능) + GPS 상태
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tempText = temperatureC?.let { " ${it}°C" } ?: ""
                    val modeTag = if (manualWeather != null) " (TEST)" else ""
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0x990F172A), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0x5538BDF8), RoundedCornerShape(12.dp))
                            .clickable {
                                manualWeather = when (manualWeather) {
                                    null -> WeatherType.CLEAR
                                    WeatherType.CLEAR -> WeatherType.CLOUDY
                                    WeatherType.CLOUDY -> WeatherType.RAIN
                                    WeatherType.RAIN -> WeatherType.SNOW
                                    WeatherType.SNOW -> null
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${activeWeather.icon} ${activeWeather.label}$tempText$modeTag",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(
                                if (lastLocation != null) Color(0xFF00E676) else Color(0xFFF59E0B),
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    Text(
                        text = if (lastLocation != null) "GPS 연결됨" else "GPS 탐색중",
                        color = Color(0xFF22D3EE),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 우측 Trip A 카드
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

            // 하단 위치 표시
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasLocationPermission) "📍 $currentAddress" else "📍 위치 권한을 허용해주세요",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

@Composable
fun WeatherSkyBackground(
    weather: WeatherType,
    elapsedSec: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val horizonY = h * 0.48f

        val skyColors = when (weather) {
            WeatherType.CLEAR -> listOf(
                Color(0xFF0A3D7A),
                Color(0xFF1A6BB5),
                Color(0xFF071A30),
                Color(0xFF000000)
            )
            WeatherType.CLOUDY -> listOf(
                Color(0xFF2B3544),
                Color(0xFF3E4C5E),
                Color(0xFF141B24),
                Color(0xFF000000)
            )
            WeatherType.RAIN -> listOf(
                Color(0xFF141B26),
                Color(0xFF233042),
                Color(0xFF0C121A),
                Color(0xFF000000)
            )
            WeatherType.SNOW -> listOf(
                Color(0xFF283747),
                Color(0xFF425466),
                Color(0xFF131B24),
                Color(0xFF000000)
            )
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

        when (weather) {
            WeatherType.CLEAR -> {
                // 도로 지평선 위쪽 태양 + 은은한 후광
                val sunCenter = Offset(w * 0.62f, h * 0.17f)
                val pulse = 1f + sin(elapsedSec * 1.5f) * 0.04f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFF7CC).copy(alpha = 0.95f),
                            Color(0xFFFFD54F).copy(alpha = 0.50f),
                            Color(0xFF38BDF8).copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        center = sunCenter,
                        radius = h * 0.24f * pulse
                    ),
                    center = sunCenter,
                    radius = h * 0.24f * pulse
                )
                drawCircle(
                    color = Color(0xFFFFFBE6),
                    center = sunCenter,
                    radius = h * 0.042f
                )
            }

            WeatherType.CLOUDY, WeatherType.RAIN, WeatherType.SNOW -> {
                // 흘러가는 구름 레이어
                val cloudBaseColor = when (weather) {
                    WeatherType.RAIN -> Color(0xFF1E293B).copy(alpha = 0.85f)
                    WeatherType.SNOW -> Color(0xFFCBD5E1).copy(alpha = 0.35f)
                    else -> Color(0xFFE2E8F0).copy(alpha = 0.38f)
                }
                val drift = (elapsedSec * 14f) % (w * 1.4f)
                for (i in 0..4) {
                    val baseX = ((i * w * 0.28f) + drift) % (w * 1.4f) - w * 0.2f
                    val baseY = h * (0.10f + (i % 3) * 0.08f)
                    val cw = w * (0.18f + (i % 2) * 0.07f)
                    val ch = h * 0.09f
                    drawOval(
                        color = cloudBaseColor,
                        topLeft = Offset(baseX, baseY),
                        size = Size(cw, ch)
                    )
                    drawOval(
                        color = cloudBaseColor,
                        topLeft = Offset(baseX + cw * 0.25f, baseY - ch * 0.35f),
                        size = Size(cw * 0.65f, ch * 0.9f)
                    )
                }
            }
        }

        // 지평선 아래는 어둡게 정리해서 도로 네온 대비 유지
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                startY = horizonY * 0.7f,
                endY = horizonY * 1.25f
            ),
            topLeft = Offset(0f, horizonY * 0.7f),
            size = Size(w, horizonY * 0.55f)
        )
    }
}

@Composable
fun WeatherPrecipitationOverlay(
    weather: WeatherType,
    speed: Float,
    elapsedSec: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val speedFactor = (speed / 120f).coerceIn(0f, 1.5f)

        if (weather == WeatherType.RAIN) {
            val dropCount = 55
            for (i in 0 until dropCount) {
                val seed = i * 97.31f
                val fallSpeed = 0.75f + (i % 5) * 0.14f + speedFactor * 0.35f
                val progress = ((elapsedSec * fallSpeed) + (seed % 1f)) % 1f
                val x = ((seed * 13.7f) % w) - progress * (18f + speedFactor * 45f)
                val y = progress * h
                val len = 14f + (i % 4) * 5f + speedFactor * 10f
                val slant = 4f + speedFactor * 10f

                drawLine(
                    color = Color(0xFFBAE6FD).copy(alpha = 0.35f + (i % 3) * 0.12f),
                    start = Offset(x, y),
                    end = Offset(x - slant, y + len),
                    strokeWidth = 1.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        } else if (weather == WeatherType.SNOW) {
            val flakeCount = 45
            for (i in 0 until flakeCount) {
                val seed = i * 61.17f
                val fallSpeed = 0.14f + (i % 4) * 0.04f
                val progress = ((elapsedSec * fallSpeed) + (seed % 1f)) % 1f
                val sway = sin(elapsedSec * 2f + i) * 18f
                val x = ((seed * 19.3f) % w) + sway
                val y = progress * h
                val r = (1.8f + (i % 3) * 1.1f).dp.toPx()

                drawCircle(
                    color = Color.White.copy(alpha = 0.45f + (i % 3) * 0.15f),
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
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val cx = w * 0.198f
        val cy = h * 0.54f
        val radius = minOf(w * 0.175f, h * 0.34f)

        val progress = (speed / maxSpeed).coerceIn(0f, 1f)
        val angleDeg = -135f + (270f * progress)

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

        // bg_hud_road.png 실제 도로 상단(소실선) 및 하단 폭에 맞춘 원근 직선
        val centerX = w * roadCenterInBg
        val topY = h * 0.335f
        val botY = h * 0.77f
        val topHalfW = w * 0.032f
        val botHalfW = w * 0.34f

        // 차량 양옆을 감싸는 주행 차선 점선(±0.46) + 도로 외곽 레일 빛줄기(±0.96)
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

            // 1) 차량 양옆 차선 점선
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

            // 2) 배경 도로 외곽 라인을 따라 흐르는 은은한 글로우 점선
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
