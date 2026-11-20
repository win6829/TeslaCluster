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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
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
    var elapsedSec by remember { mutableFloatStateOf(0f) }

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

    // 2. 60fps 루프: 테스트 주행 속도 생성 + 부드러운 속도 보간 + 1씩 순차 숫자 변경 + 전진 도로 흐름
    LaunchedEffect(Unit) {
        var lastFrameNs = System.nanoTime()
        var digitStepAccumSec = 0f

        while (true) {
            val nowNs = System.nanoTime()
            val dtSec = ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
            lastFrameNs = nowNs
            elapsedSec += dtSec

            // 속도계 터치 시 테스트 속도 0 -> 130 -> 0 왕복 시뮬레이션
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

            // 게이지/모션용 연속 보간 속도
            val diff = clampedTarget - smoothSpeedKmh
            if (abs(diff) < 0.01f) {
                smoothSpeedKmh = clampedTarget
            } else {
                val alpha = (1f - exp(-6.5f * dtSec)).coerceIn(0f, 1f)
                smoothSpeedKmh += diff * alpha
            }

            // 숫자가 멈칫하지 않고 1씩 일정 간격으로 착착 올라가거나 내려가도록 처리
            val desiredInt = clampedTarget.toInt()
            if (displayedSpeedInt != desiredInt) {
                // 속도가 빠르거나 차이가 클수록 1씩 넘어가는 간격을 살짝 단축 (최소 ~22ms, 기본 ~55ms)
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

            // 속도에 비례해 전진하는 도로 라인 흐름
            if (smoothSpeedKmh > 0.5f) {
                val flowRate = (smoothSpeedKmh / 42f) * dtSec
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

    // 위치 미세 조정용 비율
    val bgWidthRatio = 0.86f
    val speedCenterXRatio = 0.168f
    val speedCenterYRatio = 0.56f
    val carCenterXRatio = 0.555f
    val carCenterYRatio = 0.67f

    val speedFactor = (smoothSpeedKmh / 120f).coerceIn(0f, 1.4f)
    // 속도에 따른 미세 주행 진동 및 앞으로 치고 나가는 느낌의 스케일 변화
    val carBobY = if (smoothSpeedKmh > 1f) sin(elapsedSec * (14f + speedFactor * 18f)) * (1.2f * speedFactor) else 0f
    val carSurgeScale = 1f + (speedFactor * 0.025f) + if (smoothSpeedKmh > 1f) sin(elapsedSec * 6f) * (0.004f * speedFactor) else 0f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        val screenW = maxWidth
        val screenH = maxHeight

        // 1) 네온 속도계 + 도로 배경 이미지
        Image(
            painter = painterResource(id = R.drawable.bg_hud_road),
            contentDescription = "HUD Road Background",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(bgWidthRatio)
        )

        // 2) 속도에 비례해 앞으로 달리는 느낌을 주는 도로 모션 스트릭 오버레이
        ForwardMotionRoadOverlay(
            speed = smoothSpeedKmh,
            scrollPhase = roadScrollPhase,
            carCenterXRatio = carCenterXRatio,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(bgWidthRatio)
        )

        // 3) 차량 바닥 그림자 + 테슬라 모델 3 하이랜드 리어뷰 (전진 모션 적용)
        val carWidth = screenW * 0.175f
        val carHeight = carWidth * 0.68f
        Box(
            modifier = Modifier
                .offset(
                    x = screenW * carCenterXRatio - carWidth / 2,
                    y = screenH * carCenterYRatio - carHeight / 2 + carBobY.dp
                )
                .size(width = carWidth, height = carHeight)
                .graphicsLayer {
                    scaleX = carSurgeScale
                    scaleY = carSurgeScale
                },
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

        // 4) 좌측 속도계 링 중앙 실시간 숫자 (터치 시 테스트 주행 토글)
        val speedBoxSize = 154.dp
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
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-2).sp
                )
                Text(
                    text = if (isTestDriving) "TEST" else "km/h",
                    color = if (isTestDriving) Color(0xFF00E676) else Color(0xFF94E2F5),
                    fontSize = 15.sp,
                    fontWeight = if (isTestDriving) FontWeight.Bold else FontWeight.Medium
                )
            }
        }

        // 5) 상단 바 / 우측 Trip A / 하단 주소 UI 오버레이
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
fun ForwardMotionRoadOverlay(
    speed: Float,
    scrollPhase: Float,
    carCenterXRatio: Float,
    modifier: Modifier = Modifier
) {
    if (speed <= 0.5f) return

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val intensity = (speed / 120f).coerceIn(0.08f, 1.35f)

        // 도로 원근 구간의 대략적인 X 중심 정렬 (전체 화면 기준 carCenterXRatio 보정)
        val roadCenterX = w * (carCenterXRatio / 0.86f).coerceIn(0.45f, 0.72f)
        val topY = h * 0.24f
        val botY = h * 0.95f

        val streakCount = 8
        val laneOffsets = listOf(-0.22f, -0.07f, 0.07f, 0.22f)

        for (i in 0 until streakCount) {
            val rawT = ((i.toFloat() / streakCount) + scrollPhase) % 1f
            // 원근감: 아래쪽(가까운 쪽)으로 갈수록 빠르게 넓어지며 지나감
            val pStart = rawT * rawT
            val streakLen = (0.035f + 0.045f * intensity) * (0.4f + rawT)
            val pEnd = (rawT + streakLen).coerceAtMost(1f).let { it * it }

            val y1 = topY + (botY - topY) * pStart
            val y2 = topY + (botY - topY) * pEnd
            if (y2 <= y1) continue

            val spreadScale = 0.25f + 1.35f * pStart
            val alpha = ((0.18f + 0.42f * pStart) * intensity).coerceIn(0f, 0.75f)
            val strokeW = ((1.2f + 2.4f * pStart) * (0.7f + 0.4f * intensity)).dp.toPx()

            for (lane in laneOffsets) {
                val x1 = roadCenterX + w * lane * spreadScale
                val x2 = roadCenterX + w * lane * (0.25f + 1.35f * pEnd)
                drawLine(
                    color = Color(0xFF67E8F9).copy(alpha = alpha),
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
