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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    var currentSpeedKmh by remember { mutableFloatStateOf(0f) }
    var totalDistanceMeters by remember { mutableFloatStateOf(0f) }
    var lastLocation by remember { mutableStateOf<Location?>(null) }
    var currentAddress by remember { mutableStateOf("위치 수신 대기 중...") }
    var lastGeocodedLocation by remember { mutableStateOf<Location?>(null) }
    var currentTimeText by remember { mutableStateOf("") }
    var currentDateText by remember { mutableStateOf("") }
    var batteryPct by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }
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

    // 2. 속도에 연동된 3D 도로 차선 흐르는 애니메이션
    LaunchedEffect(currentSpeedKmh) {
        while (true) {
            if (currentSpeedKmh > 1.5f) {
                val step = (currentSpeedKmh / 2400f).coerceIn(0.004f, 0.06f)
                roadPhase = (roadPhase + step) % 1f
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
                currentSpeedKmh = if (speed < 1.5f) 0f else speed

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
                    .setMinUpdateIntervalMillis(300L)
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

    val animatedSpeed by animateFloatAsState(
        targetValue = currentSpeedKmh.coerceIn(0f, 200f),
        animationSpec = tween(durationMillis = 300),
        label = "speedAnim"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        Model3DrivingCanvas(
            speed = animatedSpeed,
            roadPhase = roadPhase,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xCC0D131D), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = currentDateText,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentTimeText,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(12.dp)
                            .background(Color(0xFF334155))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    val batColor = when {
                        isCharging -> Color(0xFF00E676)
                        batteryPct <= 20 -> Color(0xFFFF5252)
                        else -> Color(0xFF38BDF8)
                    }
                    Text(
                        text = if (isCharging) "⚡$batteryPct%" else "🔋$batteryPct%",
                        color = batColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xCC0D131D), RoundedCornerShape(20.dp))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                if (lastLocation != null) Color(0xFF00E676) else Color(0xFFF59E0B),
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (lastLocation != null) "GPS 연결됨" else "GPS 탐색중",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier.size(210.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CompactSpeedArc(
                        speed = animatedSpeed,
                        maxSpeed = 200f,
                        modifier = Modifier.fillMaxSize()
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${animatedSpeed.toInt()}",
                            color = Color.White,
                            fontSize = 68.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-2).sp
                        )
                        Text(
                            text = "km/h",
                            color = Color(0xFF38BDF8),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier
                        .width(165.dp)
                        .background(Color(0xCC0D131D), RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                        .clickable { totalDistanceMeters = 0f }
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Trip A",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(Locale.US, "%.1f", totalDistanceMeters / 1000f),
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "km",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "(터치하여 초기화)",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasLocationPermission) "📍 $currentAddress" else "📍 위치 권한을 허용해주세요",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun CompactSpeedArc(
    speed: Float,
    maxSpeed: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 12.dp.toPx()
        val startAngle = 140f
        val sweepTotal = 260f
        val diameter = size.minDimension - strokeWidth
        val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
        val arcSize = Size(diameter, diameter)

        drawArc(
            color = Color(0xFF1E293B),
            startAngle = startAngle,
            sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        val progress = (speed / maxSpeed).coerceIn(0f, 1f)
        val activeColor = when {
            speed > 130f -> Color(0xFFEF4444)
            speed > 100f -> Color(0xFFF59E0B)
            else -> Color(0xFF38BDF8)
        }

        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(Color(0xFF0284C7), activeColor, activeColor)
            ),
            startAngle = startAngle,
            sweepAngle = sweepTotal * progress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
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
        val cx = w * 0.51f
        val horizonY = h * 0.24f
        val bottomY = h * 0.94f

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFF0C2340).copy(alpha = 0.45f),
                    Color(0xFF050A12)
                ),
                startY = horizonY * 0.5f,
                endY = bottomY
            ),
            topLeft = Offset(0f, 0f),
            size = size
        )

        val roadTopHalfW = w * 0.055f
        val roadBottomHalfW = w * 0.26f
        val roadPath = Path().apply {
            moveTo(cx - roadTopHalfW, horizonY)
            lineTo(cx + roadTopHalfW, horizonY)
            lineTo(cx + roadBottomHalfW, bottomY)
            lineTo(cx - roadBottomHalfW, bottomY)
            close()
        }
        drawPath(
            path = roadPath,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF0B1320), Color(0xFF141E30)),
                startY = horizonY,
                endY = bottomY
            )
        )

        drawLine(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, Color(0xFF38BDF8).copy(alpha = 0.75f)),
                startY = horizonY,
                endY = bottomY
            ),
            start = Offset(cx - roadTopHalfW, horizonY),
            end = Offset(cx - roadBottomHalfW, bottomY),
            strokeWidth = 3.dp.toPx()
        )
        drawLine(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, Color(0xFF38BDF8).copy(alpha = 0.75f)),
                startY = horizonY,
                endY = bottomY
            ),
            start = Offset(cx + roadTopHalfW, horizonY),
            end = Offset(cx + roadBottomHalfW, bottomY),
            strokeWidth = 3.dp.toPx()
        )

        val segmentCount = 6
        for (i in 0 until segmentCount) {
            val tStart = ((i + roadPhase) / segmentCount).coerceIn(0f, 1f)
            val tEnd = (tStart + 0.07f).coerceAtMost(1f)
            val p1 = tStart * tStart
            val p2 = tEnd * tEnd
            val y1 = horizonY + (bottomY - horizonY) * p1
            val y2 = horizonY + (bottomY - horizonY) * p2
            if (y2 > y1) {
                drawLine(
                    color = Color(0xFF38BDF8).copy(alpha = 0.2f + 0.5f * p1),
                    start = Offset(cx, y1),
                    end = Offset(cx, y2),
                    strokeWidth = (1.5f + 2.5f * p1).dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        drawModel3Perspective(cx = cx, baseY = h * 0.79f, scale = (h / 420f).coerceIn(0.75f, 1.3f), speed = speed)
    }
}

private fun DrawScope.drawModel3Perspective(
    cx: Float,
    baseY: Float,
    scale: Float,
    speed: Float
) {
    val carW = 148f * scale
    val carH = 84f * scale

    val beamPath = Path().apply {
        moveTo(cx - carW * 0.36f, baseY - carH * 0.65f)
        lineTo(cx - carW * 0.75f, baseY - carH * 2.1f)
        lineTo(cx + carW * 0.75f, baseY - carH * 2.1f)
        lineTo(cx + carW * 0.36f, baseY - carH * 0.65f)
        close()
    }
    drawPath(
        path = beamPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color(0xFFBAE6FD).copy(alpha = if (speed > 2f) 0.16f else 0.08f)
            ),
            startY = baseY - carH * 2.1f,
            endY = baseY - carH * 0.65f
        )
    )

    drawOval(
        color = Color.Black.copy(alpha = 0.85f),
        topLeft = Offset(cx - carW * 0.56f, baseY - 10f * scale),
        size = Size(carW * 1.12f, 22f * scale)
    )

    val tireW = 24f * scale
    val tireH = 26f * scale
    drawRoundRect(
        color = Color(0xFF0F172A),
        topLeft = Offset(cx - carW * 0.47f, baseY - tireH * 0.72f),
        size = Size(tireW, tireH),
        cornerRadius = CornerRadius(6f * scale, 6f * scale)
    )
    drawRoundRect(
        color = Color(0xFF0F172A),
        topLeft = Offset(cx + carW * 0.47f - tireW, baseY - tireH * 0.72f),
        size = Size(tireW, tireH),
        cornerRadius = CornerRadius(6f * scale, 6f * scale)
    )

    val diffuserPath = Path().apply {
        moveTo(cx - carW * 0.42f, baseY)
        lineTo(cx + carW * 0.42f, baseY)
        lineTo(cx + carW * 0.36f, baseY - carH * 0.16f)
        lineTo(cx - carW * 0.36f, baseY - carH * 0.16f)
        close()
    }
    drawPath(path = diffuserPath, color = Color(0xFF1E293B))

    val bodyPath = Path().apply {
        moveTo(cx - carW * 0.48f, baseY - carH * 0.12f)
        quadraticBezierTo(cx - carW * 0.51f, baseY - carH * 0.48f, cx - carW * 0.38f, baseY - carH * 0.58f)
        lineTo(cx + carW * 0.38f, baseY - carH * 0.58f)
        quadraticBezierTo(cx + carW * 0.51f, baseY - carH * 0.48f, cx + carW * 0.48f, baseY - carH * 0.12f)
        close()
    }
    drawPath(
        path = bodyPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xFFCBD5E1), Color(0xFF94A3B8)),
            startY = baseY - carH * 0.58f,
            endY = baseY - carH * 0.12f
        )
    )

    drawRoundRect(
        color = Color(0xFFE2E8F0),
        topLeft = Offset(cx - carW * 0.52f, baseY - carH * 0.66f),
        size = Size(16f * scale, 9f * scale),
        cornerRadius = CornerRadius(4f * scale, 4f * scale)
    )
    drawRoundRect(
        color = Color(0xFFE2E8F0),
        topLeft = Offset(cx + carW * 0.52f - 16f * scale, baseY - carH * 0.66f),
        size = Size(16f * scale, 9f * scale),
        cornerRadius = CornerRadius(4f * scale, 4f * scale)
    )

    val cabinPath = Path().apply {
        moveTo(cx - carW * 0.37f, baseY - carH * 0.56f)
        lineTo(cx - carW * 0.25f, baseY - carH * 0.96f)
        quadraticBezierTo(cx, baseY - carH * 1.03f, cx + carW * 0.25f, baseY - carH * 0.96f)
        lineTo(cx + carW * 0.37f, baseY - carH * 0.56f)
        close()
    }
    drawPath(
        path = cabinPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFFF8FAFC), Color(0xFFCBD5E1)),
            startY = baseY - carH * 1.0f,
            endY = baseY - carH * 0.56f
        )
    )

    val rearGlassPath = Path().apply {
        moveTo(cx - carW * 0.31f, baseY - carH * 0.58f)
        lineTo(cx - carW * 0.21f, baseY - carH * 0.91f)
        quadraticBezierTo(cx, baseY - carH * 0.96f, cx + carW * 0.21f, baseY - carH * 0.91f)
        lineTo(cx + carW * 0.31f, baseY - carH * 0.58f)
        close()
    }
    drawPath(
        path = rearGlassPath,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B)),
            startY = baseY - carH * 0.95f,
            endY = baseY - carH * 0.58f
        )
    )

    val tailColor = if (speed <= 1.5f) Color(0xFFFF1744) else Color(0xFFEF4444)
    val leftTail = Path().apply {
        moveTo(cx - carW * 0.43f, baseY - carH * 0.44f)
        lineTo(cx - carW * 0.25f, baseY - carH * 0.44f)
        lineTo(cx - carW * 0.25f, baseY - carH * 0.39f)
        lineTo(cx - carW * 0.40f, baseY - carH * 0.39f)
        lineTo(cx - carW * 0.40f, baseY - carH * 0.33f)
        lineTo(cx - carW * 0.27f, baseY - carH * 0.33f)
        lineTo(cx - carW * 0.27f, baseY - carH * 0.29f)
        lineTo(cx - carW * 0.43f, baseY - carH * 0.29f)
        close()
    }
    val rightTail = Path().apply {
        moveTo(cx + carW * 0.43f, baseY - carH * 0.44f)
        lineTo(cx + carW * 0.25f, baseY - carH * 0.44f)
        lineTo(cx + carW * 0.25f, baseY - carH * 0.39f)
        lineTo(cx + carW * 0.40f, baseY - carH * 0.39f)
        lineTo(cx + carW * 0.40f, baseY - carH * 0.33f)
        lineTo(cx + carW * 0.27f, baseY - carH * 0.33f)
        lineTo(cx + carW * 0.27f, baseY - carH * 0.29f)
        lineTo(cx + carW * 0.43f, baseY - carH * 0.29f)
        close()
    }
    drawPath(path = leftTail, color = tailColor)
    drawPath(path = rightTail, color = tailColor)

    drawRoundRect(
        color = Color(0xFFE2E8F0),
        topLeft = Offset(cx - 18f * scale, baseY - carH * 0.34f),
        size = Size(36f * scale, 11f * scale),
        cornerRadius = CornerRadius(2f * scale, 2f * scale)
    )
}
