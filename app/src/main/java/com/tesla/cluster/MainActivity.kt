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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 앱 실행 중 화면이 꺼지지 않도록 유지
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

    var targetSpeedKmh by remember { mutableFloatStateOf(0f) }
    var smoothSpeedKmh by remember { mutableFloatStateOf(0f) }
    var displayedSpeedInt by remember { mutableIntStateOf(0) }
    var totalDistanceMeters by remember { mutableFloatStateOf(0f) }
    var lastLocation by remember { mutableStateOf<Location?>(null) }
    var currentAddress by remember { mutableStateOf("위치 수신 대기 중...") }
    var lastGeocodedLocation by remember { mutableStateOf<Location?>(null) }
    var currentTimeText by remember { mutableStateOf("") }
    var currentDateText by remember { mutableStateOf("") }
    var batteryPct by remember { mutableIntStateOf(88) }
    var isCharging by remember { mutableStateOf(true) }
    var roadPhase by remember { mutableFloatStateOf(0f) }

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

    // 2. 60fps 프레임 루프: 속도계 보간, 1씩 부드러운 숫자 변경, 도로 차선 애니메이션
    LaunchedEffect(Unit) {
        var lastFrameNs = System.nanoTime()
        while (true) {
            val nowNs = System.nanoTime()
            val dtSec = ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
            lastFrameNs = nowNs

            val clampedTarget = targetSpeedKmh.coerceIn(0f, 200f)
            val diff = clampedTarget - smoothSpeedKmh

            if (abs(diff) < 0.02f) {
                smoothSpeedKmh = clampedTarget
            } else {
                // 지수 보간으로 GPS 수신 사이 공백(500ms)에도 끊김 없이 연속 이동
                val alpha = (1f - kotlin.math.exp(-7.5f * dtSec)).coerceIn(0f, 1f)
                smoothSpeedKmh += diff * alpha
            }

            // 숫자가 2 이상 차이날 때 1씩 단계적으로 증가/감소하여 자연스럽게 변경
            val roundedTarget = smoothSpeedKmh.roundToInt()
            when {
                displayedSpeedInt < roundedTarget -> {
                    val step = if (roundedTarget - displayedSpeedInt > 15) 2 else 1
                    displayedSpeedInt = (displayedSpeedInt + step).coerceAtMost(roundedTarget)
                }
                displayedSpeedInt > roundedTarget -> {
                    val step = if (displayedSpeedInt - roundedTarget > 15) 2 else 1
                    displayedSpeedInt = (displayedSpeedInt - step).coerceAtLeast(roundedTarget)
                }
            }

            if (smoothSpeedKmh > 1.2f) {
                val laneSpeed = (smoothSpeedKmh / 36f) * dtSec
                roadPhase = (roadPhase + laneSpeed) % 1f
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
                targetSpeedKmh = if (speed < 1.5f) 0f else speed

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

    // 5. 위치 주소 변환 (200m 이동 시 갱신)
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
                        val thoroughfare = addr.subLocality ?: addr.thoroughfare ?: ""
                        currentAddress = listOf(admin, locality, thoroughfare)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        Model3DrivingCanvas(
            speed = smoothSpeedKmh,
            roadPhase = roadPhase,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 상단 바: 좌측 날짜/시간/배터리, 우측 GPS 상태
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentDateText,
                        color = Color(0xFF22D3EE),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = currentTimeText,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    val batColor = when {
                        isCharging -> Color(0xFF22D3EE)
                        batteryPct <= 20 -> Color(0xFFFF5252)
                        else -> Color(0xFF38BDF8)
                    }
                    Text(
                        text = if (isCharging) "⚡ $batteryPct%" else "🔋 $batteryPct%",
                        color = batColor,
                        fontSize = 17.sp,
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (lastLocation != null) "GPS 연결됨" else "GPS 탐색중",
                        color = Color(0xFF22D3EE),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 중앙 영역: 좌측 세그먼트 속도계, 우측 Trip A 카드
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier.size(230.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SegmentedSpeedGauge(
                        speed = smoothSpeedKmh,
                        maxSpeed = 160f,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$displayedSpeedInt",
                            color = Color.White,
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp
                        )
                        Text(
                            text = "km/h",
                            color = Color(0xFFCBD5E1),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(190.dp)
                        .background(Color(0xE6141820), RoundedCornerShape(18.dp))
                        .border(1.dp, Color(0xFF262D3B), RoundedCornerShape(18.dp))
                        .clickable { totalDistanceMeters = 0f }
                        .padding(horizontal = 18.dp, vertical = 18.dp)
                ) {
                    Text(
                        text = "Trip A",
                        color = Color(0xFF22D3EE),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.US, "%.1f", totalDistanceMeters / 1000f),
                            color = Color.White,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "km",
                            color = Color(0xFFCBD5E1),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "(터치하여 초기화)",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun SegmentedSpeedGauge(
    speed: Float,
    maxSpeed: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outerR = size.minDimension * 0.46f
        val innerR = outerR - 14.dp.toPx()

        val startDeg = 135f
        val totalSweep = 270f
        val tickCount = 36
        val progress = (speed / maxSpeed).coerceIn(0f, 1f)
        val activeTicks = (tickCount * progress).roundToInt()

        for (i in 0..tickCount) {
            val t = i.toFloat() / tickCount
            val deg = startDeg + totalSweep * t
            val rad = deg * PI / 180.0
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val tickStartR = if (i % 6 == 0) innerR - 4.dp.toPx() else innerR
            val p1 = Offset(cx + cosA * tickStartR, cy + sinA * tickStartR)
            val p2 = Offset(cx + cosA * outerR, cy + sinA * outerR)

            val isLit = i <= activeTicks
            val tickColor = when {
                !isLit -> Color(0xFF1E293B).copy(alpha = 0.65f)
                t > 0.78f -> Color(0xFFE0F2FE)
                t > 0.45f -> Color(0xFF38BDF8)
                else -> Color(0xFF06B6D4)
            }

            drawLine(
                color = tickColor,
                start = p1,
                end = p2,
                strokeWidth = if (i % 6 == 0) 3.5.dp.toPx() else 2.2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // 외곽 글로우 링
        drawArc(
            brush = Brush.sweepGradient(
                0.0f to Color(0xFF0284C7).copy(alpha = 0.2f),
                0.35f to Color(0xFF22D3EE),
                0.7f to Color(0xFFBAE6FD),
                1.0f to Color(0xFF0284C7).copy(alpha = 0.2f)
            ),
            startAngle = startDeg,
            sweepAngle = totalSweep * progress.coerceAtLeast(0.08f),
            useCenter = false,
            topLeft = Offset(cx - outerR - 5.dp.toPx(), cy - outerR - 5.dp.toPx()),
            size = Size((outerR + 5.dp.toPx()) * 2f, (outerR + 5.dp.toPx()) * 2f),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // 활성 끝점 스파클 하이라이트
        val tipDeg = startDeg + totalSweep * progress
        val tipRad = tipDeg * PI / 180.0
        val tipX = cx + cos(tipRad).toFloat() * ((innerR + outerR) / 2f)
        val tipY = cy + sin(tipRad).toFloat() * ((innerR + outerR) / 2f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color(0xFF67E8F9).copy(alpha = 0.5f), Color.Transparent),
                center = Offset(tipX, tipY),
                radius = 22.dp.toPx()
            ),
            center = Offset(tipX, tipY),
            radius = 22.dp.toPx()
        )

        rotate(degrees = 45f, pivot = Offset(tipX, tipY)) {
            drawLine(
                color = Color.White,
                start = Offset(tipX - 11.dp.toPx(), tipY),
                end = Offset(tipX + 11.dp.toPx(), tipY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = Offset(tipX, tipY - 11.dp.toPx()),
                end = Offset(tipX, tipY + 11.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
fun Model3DrivingCanvas(
    speed: Float,
    roadPhase: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val horizonY = h * 0.14f
        val bottomY = h * 0.96f

        // 중앙 시안 조명 글로우
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF0E7490).copy(alpha = 0.34f),
                    Color(0xFF082F49).copy(alpha = 0.14f),
                    Color.Transparent
                ),
                center = Offset(cx, h * 0.52f),
                radius = w * 0.34f
            ),
            center = Offset(cx, h * 0.52f),
            radius = w * 0.34f
        )

        val outerTopW = w * 0.045f
        val outerBotW = w * 0.25f

        val roadPath = Path().apply {
            moveTo(cx - outerTopW, horizonY)
            lineTo(cx + outerTopW, horizonY)
            lineTo(cx + outerBotW, bottomY)
            lineTo(cx - outerBotW, bottomY)
            close()
        }
        drawPath(
            path = roadPath,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF06131F), Color(0xFF0C2235), Color(0xFF071320)),
                startY = horizonY,
                endY = bottomY
            )
        )

        // 3차선 구획선 (외곽 2줄 + 내부 2줄)
        val laneRatios = listOf(-1.0f, -0.33f, 0.33f, 1.0f)
        for (ratio in laneRatios) {
            val topX = cx + outerTopW * ratio
            val botX = cx + outerBotW * ratio
            val isOuter = abs(ratio) > 0.9f

            drawLine(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF22D3EE).copy(alpha = if (isOuter) 0.95f else 0.72f),
                        Color(0xFF67E8F9).copy(alpha = if (isOuter) 1.0f else 0.82f)
                    ),
                    startY = horizonY,
                    endY = bottomY
                ),
                start = Offset(topX, horizonY),
                end = Offset(botX, bottomY),
                strokeWidth = if (isOuter) 4.dp.toPx() else 2.2.dp.toPx()
            )
        }

        // 속도 연동 이동 마커
        val markerCount = 5
        for (i in 0 until markerCount) {
            val tStart = ((i + roadPhase) / markerCount).coerceIn(0f, 1f)
            val tEnd = (tStart + 0.06f).coerceAtMost(1f)
            val p1 = tStart * tStart
            val p2 = tEnd * tEnd
            val y1 = horizonY + (bottomY - horizonY) * p1
            val y2 = horizonY + (bottomY - horizonY) * p2
            if (y2 > y1) {
                drawLine(
                    color = Color(0xFF67E8F9).copy(alpha = 0.15f + 0.45f * p1),
                    start = Offset(cx, y1),
                    end = Offset(cx, y2),
                    strokeWidth = (1.5f + 2f * p1).dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        drawModel3RearPerspective(
            cx = cx,
            baseY = h * 0.74f,
            scale = (h / 420f).coerceIn(0.8f, 1.35f),
            speed = speed
        )
    }
}

private fun DrawScope.drawModel3RearPerspective(
    cx: Float,
    baseY: Float,
    scale: Float,
    speed: Float
) {
    val carW = 138f * scale
    val carH = 82f * scale

    // 전방 헤드라이트 빛 퍼짐
    val leftBeam = Path().apply {
        moveTo(cx - carW * 0.32f, baseY - carH * 0.62f)
        lineTo(cx - carW * 0.95f, baseY - carH * 1.45f)
        lineTo(cx - carW * 0.25f, baseY - carH * 1.45f)
        close()
    }
    val rightBeam = Path().apply {
        moveTo(cx + carW * 0.32f, baseY - carH * 0.62f)
        lineTo(cx + carW * 0.95f, baseY - carH * 1.45f)
        lineTo(cx + carW * 0.25f, baseY - carH * 1.45f)
        close()
    }
    drawPath(
        path = leftBeam,
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFE0F2FE).copy(alpha = 0.34f), Color.Transparent),
            center = Offset(cx - carW * 0.55f, baseY - carH * 1.0f),
            radius = carW * 0.75f
        )
    )
    drawPath(
        path = rightBeam,
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFE0F2FE).copy(alpha = 0.34f), Color.Transparent),
            center = Offset(cx + carW * 0.55f, baseY - carH * 1.0f),
            radius = carW * 0.75f
        )
    )

    // 바닥 그림자
    drawOval(
        color = Color.Black.copy(alpha = 0.85f),
        topLeft = Offset(cx - carW * 0.58f, baseY - 8f * scale),
        size = Size(carW * 1.16f, 22f * scale)
    )

    // 타이어
    val tireW = 22f * scale
    val tireH = 22f * scale
    drawRoundRect(
        color = Color(0xFF0F172A),
        topLeft = Offset(cx - carW * 0.45f, baseY - tireH * 0.65f),
        size = Size(tireW, tireH),
        cornerRadius = CornerRadius(5f * scale, 5f * scale)
    )
    drawRoundRect(
        color = Color(0xFF0F172A),
        topLeft = Offset(cx + carW * 0.45f - tireW, baseY - tireH * 0.65f),
        size = Size(tireW, tireH),
        cornerRadius = CornerRadius(5f * scale, 5f * scale)
    )

    // 하단 디퓨저
    val diffuserPath = Path().apply {
        moveTo(cx - carW * 0.40f, baseY - 2f * scale)
        lineTo(cx + carW * 0.40f, baseY - 2f * scale)
        lineTo(cx + carW * 0.35f, baseY - carH * 0.15f)
        lineTo(cx - carW * 0.35f, baseY - carH * 0.15f)
        close()
    }
    drawPath(path = diffuserPath, color = Color(0xFF1E293B))

    // 화이트 차체 후면
    val bodyPath = Path().apply {
        moveTo(cx - carW * 0.47f, baseY - carH * 0.12f)
        quadraticBezierTo(cx - carW * 0.50f, baseY - carH * 0.46f, cx - carW * 0.37f, baseY - carH * 0.55f)
        lineTo(cx + carW * 0.37f, baseY - carH * 0.55f)
        quadraticBezierTo(cx + carW * 0.50f, baseY - carH * 0.46f, cx + carW * 0.47f, baseY - carH * 0.12f)
        close()
    }
    drawPath(
        path = bodyPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xFFE2E8F0), Color(0xFF94A3B8)),
            startY = baseY - carH * 0.55f,
            endY = baseY - carH * 0.12f
        )
    )

    // 사이드미러
    drawRoundRect(
        color = Color(0xFFE2E8F0),
        topLeft = Offset(cx - carW * 0.51f, baseY - carH * 0.62f),
        size = Size(14f * scale, 8f * scale),
        cornerRadius = CornerRadius(3f * scale, 3f * scale)
    )
    drawRoundRect(
        color = Color(0xFFE2E8F0),
        topLeft = Offset(cx + carW * 0.51f - 14f * scale, baseY - carH * 0.62f),
        size = Size(14f * scale, 8f * scale),
        cornerRadius = CornerRadius(3f * scale, 3f * scale)
    )

    // 루프 및 글라스 루프
    val cabinPath = Path().apply {
        moveTo(cx - carW * 0.36f, baseY - carH * 0.54f)
        lineTo(cx - carW * 0.24f, baseY - carH * 0.95f)
        quadraticBezierTo(cx, baseY - carH * 1.02f, cx + carW * 0.24f, baseY - carH * 0.95f)
        lineTo(cx + carW * 0.36f, baseY - carH * 0.54f)
        close()
    }
    drawPath(
        path = cabinPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFF1F5F9), Color(0xFFCBD5E1)),
            startY = baseY - carH * 1.0f,
            endY = baseY - carH * 0.54f
        )
    )

    val rearGlassPath = Path().apply {
        moveTo(cx - carW * 0.30f, baseY - carH * 0.56f)
        lineTo(cx - carW * 0.20f, baseY - carH * 0.90f)
        quadraticBezierTo(cx, baseY - carH * 0.95f, cx + carW * 0.20f, baseY - carH * 0.90f)
        lineTo(cx + carW * 0.30f, baseY - carH * 0.56f)
        close()
    }
    drawPath(
        path = rearGlassPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B)),
            startY = baseY - carH * 0.95f,
            endY = baseY - carH * 0.56f
        )
    )

    // C자형 테일램프 (하이랜드 스타일)
    val tailColor = if (speed <= 1.5f) Color(0xFFFF1744) else Color(0xFFEF4444)
    val leftTail = Path().apply {
        moveTo(cx - carW * 0.42f, baseY - carH * 0.43f)
        lineTo(cx - carW * 0.24f, baseY - carH * 0.43f)
        lineTo(cx - carW * 0.24f, baseY - carH * 0.38f)
        lineTo(cx - carW * 0.39f, baseY - carH * 0.38f)
        lineTo(cx - carW * 0.39f, baseY - carH * 0.32f)
        lineTo(cx - carW * 0.26f, baseY - carH * 0.32f)
        lineTo(cx - carW * 0.26f, baseY - carH * 0.28f)
        lineTo(cx - carW * 0.42f, baseY - carH * 0.28f)
        close()
    }
    val rightTail = Path().apply {
        moveTo(cx + carW * 0.42f, baseY - carH * 0.43f)
        lineTo(cx + carW * 0.24f, baseY - carH * 0.43f)
        lineTo(cx + carW * 0.24f, baseY - carH * 0.38f)
        lineTo(cx + carW * 0.39f, baseY - carH * 0.38f)
        lineTo(cx + carW * 0.39f, baseY - carH * 0.32f)
        lineTo(cx + carW * 0.26f, baseY - carH * 0.32f)
        lineTo(cx + carW * 0.26f, baseY - carH * 0.28f)
        lineTo(cx + carW * 0.42f, baseY - carH * 0.28f)
        close()
    }
    drawPath(path = leftTail, color = tailColor)
    drawPath(path = rightTail, color = tailColor)

    // 후방 번호판 영역
    drawRoundRect(
        color = Color(0xFFE2E8F0),
        topLeft = Offset(cx - 17f * scale, baseY - carH * 0.33f),
        size = Size(34f * scale, 10f * scale),
        cornerRadius = CornerRadius(2f * scale, 2f * scale)
    )
}
