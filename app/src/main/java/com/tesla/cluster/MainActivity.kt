package com.example.teslacluster // 기존 프로젝트 패키지명으로 맞춰줘

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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.*

enum class ClusterTheme {
    CLASSIC,
    NEON_HIGHWAY
}

class MainActivity : ComponentActivity(), LocationListener {

    private var locationManager: LocationManager? = null
    private val gpsSpeedKmh = mutableFloatStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 화면 꺼짐 방지 및 몰입형 전체화면
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
    // 1. 테마 상태 (차량 클릭 시 토글)
    var currentTheme by remember { mutableStateOf(ClusterTheme.CLASSIC) }

    // 7. 아이오닉 스타일 주간/야간 모드 (시간대 자동 감지 + 수동 토글 가능)
    val initialNight = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hour < 6 || hour >= 19
    }
    var isNightMode by remember { mutableStateOf(initialNight) }

    // 테스트용 가속 시뮬레이션 상태 (배경 길게 누르기)
    var isSimulatingAccel by remember { mutableStateOf(false) }
    var simulatedSpeed by remember { mutableFloatStateOf(0f) }

    // 5. 트립(주행 거리 및 시간) 상태
    var tripDistanceKm by remember { mutableDoubleStateOf(0.0) }
    var tripSeconds by remember { mutableLongStateOf(0L) }

    // 60fps 기반 가속 시뮬레이션 및 트립 거리 누적 루프
    LaunchedEffect(isSimulatingAccel) {
        while (true) {
            delay(50L) // 20Hz 업데이트
            if (isSimulatingAccel) {
                simulatedSpeed = (simulatedSpeed + 2.8f).coerceAtMost(240f)
            } else if (simulatedSpeed > 0f) {
                simulatedSpeed = (simulatedSpeed - 2.2f).coerceAtLeast(0f)
            }
            val activeSpeed = max(gpsSpeed, simulatedSpeed)
            if (activeSpeed > 0.5f) {
                // 거리(km) = 속도(km/h) * 시간(h)
                tripDistanceKm += (activeSpeed.toDouble() * (0.05 / 3600.0))
            }
        }
    }

    // 주행 시간 카운터 (1초 단위)
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

    // 도로 차선 & 구름 애니메이션 상태
    var roadOffset by remember { mutableFloatStateOf(0f) }
    var cloudOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastTime = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - lastTime) / 1_000_000_000f).coerceAtMost(0.1f)
                lastTime = now
                // 속도에 비례해 차선 흐름
                val speedFactor = (animatedSpeed / 60f)
                roadOffset = (roadOffset + dt * (0.35f + speedFactor * 2.2f)) % 1f
                cloudOffset = (cloudOffset + dt * 0.015f) % 1f
            }
        }
    }

    // 아이오닉 스타일 주/야간 배경색 전환
    val bgTopColor by animateColorAsState(
        targetValue = when {
            currentTheme == ClusterTheme.NEON_HIGHWAY && isNightMode -> Color(0xFF020408)
            currentTheme == ClusterTheme.NEON_HIGHWAY && !isNightMode -> Color(0xFFEAF2FA)
            isNightMode -> Color(0xFF0D1117)
            else -> Color(0xFFF3F6FA) // 아이오닉 데이모드 화이트 톤
        },
        animationSpec = tween(500), label = "bg_top"
    )

    val bgBottomColor by animateColorAsState(
        targetValue = when {
            currentTheme == ClusterTheme.NEON_HIGHWAY && isNightMode -> Color(0xFF050A14)
            currentTheme == ClusterTheme.NEON_HIGHWAY && !isNightMode -> Color(0xFFFFFFFF)
            isNightMode -> Color(0xFF1A2029) // 야간 회색빛/검정
            else -> Color(0xFFFFFFFF)
        },
        animationSpec = tween(500), label = "bg_bottom"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgTopColor, bgBottomColor)))
            // 빈 공간 꾹 누르면 240km/h까지 가속 테스트
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
        // 1) 상단 하늘 + 6. 입체 뭉게구름
        VolumetricCumulusSky(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f),
            cloudOffset = cloudOffset,
            isNightMode = isNightMode,
            theme = currentTheme
        )

        // 2) 하단 원근 도로 (기존 테마 vs 네온 블루 미래형 고속도로 테마)
        HighwayRoadCanvas(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .align(Alignment.BottomCenter),
            roadOffset = roadOffset,
            isNightMode = isNightMode,
            theme = currentTheme
        )

        // 3) 중앙 테슬라 차량 (터치 시 테마 변경!)
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
                },
            isNightMode = isNightMode,
            theme = currentTheme
        )

        // 4) 좌측 240km/h 그라데이션 속도계 (2번째 첨부 이미지 스타일)
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

        // 5) 우측 트립 계기판 (주행 거리 / 시간 / 평균 속도)
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

/**
 * 6. 입체적인 3D 뭉게구름 렌더링
 */
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

        // 하늘 그라데이션
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

        // 뭉게구름 색상 팔레트 (입체감: 하단 그림자 / 중간 바디 / 상단 빛)
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

            // Layer 1: 하단 입체 그림자
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

            // Layer 2: 구름 메인 볼륨
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

            // Layer 3: 상단 햇빛(달빛) 하이라이트
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

        // 화면을 가로질러 천천히 흐르는 뭉게구름 4덩이
        val shift1 = ((cloudOffset * w * 1.3f) % (w + 320f)) - 160f
        val shift2 = (((cloudOffset + 0.35f) * w * 1.3f) % (w + 320f)) - 160f
        val shift3 = (((cloudOffset + 0.68f) * w * 1.3f) % (w + 320f)) - 160f

        drawPuffyCumulus(shift1, h * 0.24f, 1.15f)
        drawPuffyCumulus(shift2, h * 0.17f, 0.90f)
        drawPuffyCumulus(shift3, h * 0.30f, 1.25f)
    }
}

/**
 * 2. 원근 고속도로 (클래식 테마 vs 네온 블루 미래형 고속도로 테마)
 */
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

        // 도로 바닥 색상
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

        // 중앙 네온 블루 반사광 (2번 테마 전용)
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

        // 좌/우 가드레일 (네온 테마: 1번 이미지처럼 푸른 네온 가드레일 + LED 등)
        val railColor = when {
            isNeon && isNightMode -> Color(0xFF00C8FF)
            isNeon && !isNightMode -> Color(0xFF0077CC)
            isNightMode -> Color(0xFF5A6578)
            else -> Color(0xFF9AA5B4)
        }

        // 가드레일 상단/하단 라인
        drawLine(railColor, Offset(centerX - 18f, horizonY), Offset(0f, h * 0.34f), strokeWidth = 4.dp.toPx())
        drawLine(railColor.copy(alpha = 0.7f), Offset(centerX - 18f, horizonY + 8f), Offset(0f, h * 0.50f), strokeWidth = 2.5.dp.toPx())

        drawLine(railColor, Offset(centerX + 18f, horizonY), Offset(w, h * 0.34f), strokeWidth = 4.dp.toPx())
        drawLine(railColor.copy(alpha = 0.7f), Offset(centerX + 18f, horizonY + 8f), Offset(w, h * 0.50f), strokeWidth = 2.5.dp.toPx())

        // 네온 테마 가드레일 LED 라이트 애니메이션
        if (isNeon) {
            val lightCount = 9
            for (i in 0 until lightCount) {
                val tRaw = (i.toFloat() / lightCount + roadOffset) % 1f
                val t = tRaw * tRaw // 원근 가속
                val y = horizonY + t * h * 0.44f
                val leftX = (centerX - 18f) * (1f - t)
                val rightX = (centerX + 18f) + (w - (centerX + 18f)) * t
                val lampW = (3f + 16f * t).dp.toPx()
                val lampH = (1.5f + 4.5f * t).dp.toPx()

                val lampColor = if (isNightMode) Color(0xFFD6F7FF) else Color(0xFF0095FF)
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
        }

        // 주행 차선 점선 애니메이션
        val laneColor = when {
            isNeon && isNightMode -> Color(0xFF38D4FF)
            isNeon && !isNightMode -> Color(0xFF0084E8)
            isNightMode -> Color(0xFFB0BEC5)
            else -> Color(0xFF607D8B)
        }

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
            drawLine(laneColor, Offset(leftX1, y1), Offset(leftX2, y2), strokeWidth = strokeW, cap = StrokeCap.Round)
            drawLine(laneColor, Offset(rightX1, y1), Offset(rightX2, y2), strokeWidth = strokeW, cap = StrokeCap.Round)
        }
    }
}

/**
 * 4. 240km/h 그라데이션 속도계 (두 번째 첨부 이미지 디자인 완벽 반영)
 */
@Composable
fun GradientSpeedometer(
    modifier: Modifier,
    speed: Float,
    maxSpeed: Float = 240f,
    isNightMode: Boolean,
    theme: ClusterTheme,
    onToggleDayNight: () -> Unit
) {
    val isNeon = theme == ClusterTheme.NEON_HIGHWAY
    val progress = (speed / maxSpeed).coerceIn(0f, 1f)
    val startAngle = 135f
    val maxSweep = 270f
    val currentSweep = progress * maxSweep

    val mainTextColor = if (isNightMode) Color.White else Color(0xFF161A20)
    val subTextColor = if (isNightMode) Color(0xFFAAB4C2) else Color(0xFF5D6673)

    // 그라데이션 색상 (야간: 아이스 실버/네온 블루, 주간: 아이오닉 클린 블루/실버)
    val glowPrimary = when {
        isNeon && isNightMode -> Color(0xFF00D4FF)
        isNeon && !isNightMode -> Color(0xFF007BFF)
        isNightMode -> Color(0xFFC8E0F8) // 2번 이미지의 은은한 실버-블루 광채
        else -> Color(0xFF3A82F6)
    }
    val ringColor = when {
        isNeon -> if (isNightMode) Color(0xFF2EE6FF) else Color(0xFF0077E6)
        isNightMode -> Color(0xFFE6EFFA)
        else -> Color(0xFF2563EB)
    }
    val trackColor = if (isNightMode) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
    val centerFadeColor = when {
        isNeon && isNightMode -> Color(0xFF030710)
        isNightMode -> Color(0xFF121720)
        else -> Color(0xFFF6F8FB)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 14.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            // 1) 배경 원형 트랙
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = maxSweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            )

            // 2) 속도 비례 그라데이션 채우기 (2번 이미지의 부채꼴 광채 효과)
            if (currentSweep > 0.5f) {
                val sweepFrac = (currentSweep / 360f).coerceIn(0.01f, 0.76f)
                rotate(degrees = startAngle, pivot = center) {
                    // 내부 광채 부채꼴
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

                    // 중앙 숫자 가독성을 위한 부드러운 원형 페이드
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

                    // 외곽 밝은 그라데이션 링
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

                    // 선단 발광 포인터 바 (2번 이미지 하단 흰색 빛줄기)
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
                        color = Color.White,
                        start = pStart,
                        end = pEnd,
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // 3) 0 ~ 240km/h 눈금 및 숫자 (네온 테마에서 선명하게 표시)
            val tickPaint = Paint().apply {
                color = if (isNeon) {
                    if (isNightMode) android.graphics.Color.parseColor("#B8F3FF")
                    else android.graphics.Color.parseColor("#0055AA")
                } else {
                    if (isNightMode) android.graphics.Color.parseColor("#8899AA")
                    else android.graphics.Color.parseColor("#667788")
                }
                textSize = 11.sp.toPx()
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }

            for (v in 0..240 step 10) {
                val isMajor = v % 20 == 0
                val angleDeg = startAngle + (v / maxSpeed) * maxSweep
                val rad = Math.toRadians(angleDeg.toDouble())

                val outerR = radius - 3.dp.toPx()
                val innerR = outerR - if (isMajor) 10.dp.toPx() else 5.dp.toPx()

                val tickCol = if (v <= speed) ringColor else trackColor.copy(alpha = 0.55f)
                drawLine(
                    color = tickCol,
                    start = Offset(center.x + (cos(rad) * innerR).toFloat(), center.y + (sin(rad) * innerR).toFloat()),
                    end = Offset(center.x + (cos(rad) * outerR).toFloat(), center.y + (sin(rad) * outerR).toFloat()),
                    strokeWidth = if (isMajor) 2.dp.toPx() else 1.2.dp.toPx()
                )

                if (isMajor && isNeon) {
                    val labelR = radius - 22.dp.toPx()
                    val lx = center.x + (cos(rad) * labelR).toFloat()
                    val ly = center.y + (sin(rad) * labelR).toFloat() + 4.dp.toPx()
                    drawContext.canvas.nativeCanvas.drawText(v.toString(), lx, ly, tickPaint)
                }
            }
        }

        // 중앙 텍스트 레이아웃 (2번째 첨부 이미지와 동일한 폰트·구성)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "D",
                color = mainTextColor,
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isNightMode) "Night Mode" else "Day Mode",
                color = subTextColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onToggleDayNight() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = speed.toInt().toString(),
                color = mainTextColor,
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = (-2).sp
            )
            Text(
                text = "km/h",
                color = subTextColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif
            )
        }
    }
}

/**
 * 5. 우측 트립 계기판 (주행 거리, 주행 시간, 평균 속도 / 길게 눌러서 리셋)
 */
@Composable
fun TripGaugePanel(
    modifier: Modifier,
    distanceKm: Double,
    elapsedSeconds: Long,
    isNightMode: Boolean,
    theme: ClusterTheme,
    onResetTrip: () -> Unit
) {
    val isNeon = theme == ClusterTheme.NEON_HIGHWAY
    val mainTextColor = if (isNightMode) Color.White else Color(0xFF161A20)
    val subTextColor = if (isNightMode) Color(0xFFAAB4C2) else Color(0xFF5D6673)
    val accentColor = when {
        isNeon && isNightMode -> Color(0xFF00D4FF)
        isNeon && !isNightMode -> Color(0xFF0077E6)
        isNightMode -> Color(0xFFC8E0F8)
        else -> Color(0xFF2563EB)
    }
    val trackColor = if (isNightMode) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)

    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60
    val timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)

    val avgSpeed = if (elapsedSeconds > 0) {
        (distanceKm / (elapsedSeconds / 3600.0)).coerceIn(0.0, 240.0)
    } else 0.0

    // 100km 단위로 우측 링이 채워지는 시각 효과
    val tripProgress = ((distanceKm % 100.0) / 100.0).toFloat()

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(onLongPress = { onResetTrip() })
        },
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
                text = "TRIP",
                color = accentColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = String.format("%.1f", distanceKm),
                color = mainTextColor,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif
            )
            Text(
                text = "km",
                color = subTextColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "$timeStr  |  ${avgSpeed.toInt()} km/h",
                color = subTextColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "길게 눌러 리셋",
                color = subTextColor.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }
    }
}

/**
 * 3. 테슬라 차량 뷰 (res/drawable/tesla_car.png가 있으면 자동 로드, 없으면 깔끔한 벡터 대체)
 */
@Composable
fun TeslaVehicleView(
    modifier: Modifier,
    isNightMode: Boolean,
    theme: ClusterTheme
) {
    val context = LocalContext.current
    val carResId = remember {
        context.resources.getIdentifier("tesla_car", "drawable", context.packageName)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (carResId != 0) {
            Image(
                painter = painterResource(id = carResId),
                contentDescription = "Tesla Model 3",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // drawable 파일명이 다를 경우에도 빌드 에러 없이 동작하는 테슬라 후면 실루엣
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val bodyColor = if (isNightMode) Color(0xFFD8DEE8) else Color(0xFF2B313C)
                val glassColor = Color(0xFF141922)
                val tailLightColor = if (theme == ClusterTheme.NEON_HIGHWAY) Color(0xFFFF2A4B) else Color(0xFFE53935)

                // 하부 그림자
                drawOval(
                    color = Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(w * 0.12f, h * 0.78f),
                    size = Size(w * 0.76f, h * 0.18f)
                )
                // 타이어
                drawRoundRect(Color(0xFF111111), Offset(w * 0.17f, h * 0.62f), Size(w * 0.13f, h * 0.25f), CornerRadius(10f, 10f))
                drawRoundRect(Color(0xFF111111), Offset(w * 0.70f, h * 0.62f), Size(w * 0.13f, h * 0.25f), CornerRadius(10f, 10f))
                // 차체 하부
                drawRoundRect(bodyColor, Offset(w * 0.15f, h * 0.38f), Size(w * 0.70f, h * 0.36f), CornerRadius(24f, 24f))
                // 캐빈 & 리어 글래스
                drawRoundRect(bodyColor, Offset(w * 0.25f, h * 0.16f), Size(w * 0.50f, h * 0.28f), CornerRadius(28f, 28f))
                drawRoundRect(glassColor, Offset(w * 0.29f, h * 0.20f), Size(w * 0.42f, h * 0.18f), CornerRadius(16f, 16f))
                // 테일램프 (하이랜드 ㄷ자 느낌)
                drawRoundRect(tailLightColor, Offset(w * 0.18f, h * 0.46f), Size(w * 0.14f, h * 0.08f), CornerRadius(8f, 8f))
                drawRoundRect(tailLightColor, Offset(w * 0.68f, h * 0.46f), Size(w * 0.14f, h * 0.08f), CornerRadius(8f, 8f))
            }
        }
    }
}
