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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

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

    var totalDistanceMeters by remember { mutableFloatStateOf(0f) }
    var lastLocation by remember { mutableStateOf<Location?>(null) }
    var currentAddress by remember { mutableStateOf("위치 수신 대기 중...") }
    var lastGeocodedLocation by remember { mutableStateOf<Location?>(null) }
    var currentTimeText by remember { mutableStateOf("") }
    var currentDateText by remember { mutableStateOf("") }
    var batteryPct by remember { mutableIntStateOf(88) }
    var isCharging by remember { mutableStateOf(true) }

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

    // 2. 60fps 루프: 테스트 주행 + 부드러운 속도 보간 + 1씩 순차 숫자 변경 + 전진 도로 흐름
    LaunchedEffect(Unit) {
        var lastFrameNs = System.nanoTime()
        var digitStepAccumSec = 0f

        while (true) {
            val nowNs = System.nanoTime()
            val dtSec = ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
            lastFrameNs = nowNs

            if (isTestDriving) {
                val rate = if (testAccelerating) 24f else 28f
                if (testAccelerating) {
                    testSpeedKmh = (testSpeedKmh + rate * dtSec).coerceAtMost(130f)
                    if (testSpeedKmh >= 130f) testAccelerating = false
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

    // 배경 도로 중심축과 차량 위치를 하나의 기준으로 동기화
    val bgWidthRatio = 0.88f
    val roadCenterInBg = 0.66f
    val carCenterXRatio = bgWidthRatio * roadCenterInBg
    val carCenterYRatio = 0.66f
    val speedCenterXRatio = 0.175f
    val speedCenterYRatio = 0.54f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        val screenW = maxWidth
        val screenH = maxHeight

        // 1) 네온 시안 속도계 + 도로 오버레이 이미지 (고정)
        Image(
            painter = painterResource(id = R.drawable.bg_hud_road),
            contentDescription = "HUD Road Background",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(bgWidthRatio)
        )

        // 2) 속도에 맞춰 회전하는 속도계 바늘 오버레이
        SpeedGaugeNeedleOverlay(
            speed = smoothSpeedKmh,
            maxSpeed = 160f,
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

                Row(verticalAlignment = Alignment.CenterVertically) {
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

            val rad = (angleDeg - 90f) * Math.PI / 180.0
            val tipX = cx + (cos(rad) * (radius * 0.96f)).toFloat()
            val tipYWorld = cy + (sin(rad) * (radius * 0.96f)).toFloat()
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color(0xFF67E8F9).copy(alpha = 0.6f), Color.Transparent),
                    center = Offset(tipX, tipYWorld),
                    radius = 12.dp.toPx()
                ),
                center = Offset(tipX, tipYWorld),
                radius = 12.dp.toPx()
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
            // 원근 가속: 멀리서 촘촘하게 출발해 차 가까이 올수록 길고 빠르게 통과
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
