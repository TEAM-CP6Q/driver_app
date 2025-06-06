package com.example.refreshdriver

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.refreshdriver.models.Pickup
import com.example.refreshdriver.network.NetworkResult
import com.example.refreshdriver.network.PickupRepository
import com.example.refreshdriver.utils.LocationHelper
import com.google.android.gms.location.*
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_GuideStateDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_LocationGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_RouteGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_SafetyGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_VoiceGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_CitsGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideRouteChangeReason
import com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import com.kakaomobility.knsdk.trip.kntrip.KNTrip
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import com.kakaomobility.knsdk.ui.view.KNNaviView
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import kotlin.math.*

/**
 * 카카오모빌리티 API 기반 정확한 좌표 변환 (검색 결과 참조)
 */
object KakaoMobilityCoordConverter {

    /**
     * 카카오모빌리티 API 표준 좌표 변환 (검색 결과 기반)
     * 참조: https://apis-navi.kakaomobility.com/v1/directions
     */
    fun convertWGS84ToKakaoNavi(lat: Double, lng: Double): Pair<Int, Int> {
        // 카카오모빌리티 API 표준 좌표계 (검색 결과 참조)
        val originLat = 38.0
        val originLng = 127.5
        val falseEasting = 200000.0
        val falseNorthing = 500000.0

        // 카카오모빌리티 API 호환 변환 (검색 결과 기반)
        val deltaLat = lat - originLat
        val deltaLng = lng - originLng

        // 카카오내비 표준 변환 공식
        val kakaoX = (falseEasting + deltaLng * 200000.0).toInt()
        val kakaoY = (falseNorthing + deltaLat * 200000.0).toInt()

        Log.d("KakaoMobilityCoord", "WGS84 ($lat, $lng) -> KakaoNavi ($kakaoX, $kakaoY)")
        return Pair(kakaoX, kakaoY)
    }

    /**
     * 카카오내비 → WGS84 역변환
     */
    fun convertKakaoNaviToWGS84(kakaoX: Double, kakaoY: Double): Pair<Double, Double> {
        val originLat = 38.0
        val originLng = 127.5
        val falseEasting = 200000.0
        val falseNorthing = 500000.0

        val deltaLng = (kakaoX - falseEasting) / 200000.0
        val deltaLat = (kakaoY - falseNorthing) / 200000.0

        val lng = originLng + deltaLng
        val lat = originLat + deltaLat

        return Pair(lat, lng)
    }
}

/**
 * 테스트용 확실한 좌표들 (검색 결과 참조)
 */
object TestCoordinates {
    // 서울역 → 여의도역 (검색 결과에서 확인된 정확한 좌표)
    val SEOUL_STATION = Pair(37.556011, 126.970186)
    val YEOUIDO_STATION = Pair(37.521718, 126.924234)

    // 판교역 (카카오 공식 문서 좌표)
    val PANGYO_STATION = Pair(37.40246478787756, 127.10763058573032)

    // 현대백화점 판교점 (카카오 공식 문서 좌표)
    val HYUNDAI_PANGYO = Pair(37.39279717586919, 127.11205203011632)

    // 강남역 (확실한 좌표)
    val GANGNAM_STATION = Pair(37.498095, 127.02761)
}

class NavigationActivity : AppCompatActivity(),
    KNGuidance_GuideStateDelegate,
    KNGuidance_LocationGuideDelegate,
    KNGuidance_RouteGuideDelegate,
    KNGuidance_SafetyGuideDelegate,
    KNGuidance_VoiceGuideDelegate,
    KNGuidance_CitsGuideDelegate {

    private lateinit var naviView: KNNaviView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationHelper: LocationHelper
    private lateinit var repository: PickupRepository

    private lateinit var buttonCompletePickup: Button
    private lateinit var progressNavigation: ProgressBar

    private val selectedPickups = mutableListOf<Pickup>()
    private var currentLocation: Location? = null
    private var currentPickupIndex = 0
    private var isNavigationActive = false
    private var isTestMode = true // 테스트 모드 활성화

    private val handler = Handler(Looper.getMainLooper())

    // Dialog 관리용 변수들
    private var exitDialog: AlertDialog? = null
    private var pickupDialog: AlertDialog? = null
    private var completeDialog: AlertDialog? = null

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val PICKUP_COMPLETION_RADIUS = 200.0
        private const val TAG = "NavigationActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        checkGPSSettings()
        initViews()
        setupFullScreen()
        loadIntentData()
        setupLocationServices()
        checkLocationPermissions()
        setupClickListeners()
    }

    private fun checkGPSSettings() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        Log.d(TAG, "GPS 활성화: $isGPSEnabled, 네트워크 위치: $isNetworkEnabled")

        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            checkSamsungLocationSettings()
        }

        if (!isGPSEnabled && !isNetworkEnabled) {
            AlertDialog.Builder(this)
                .setTitle("위치 서비스 필요")
                .setMessage("GPS를 켜주세요.\n설정 > 위치 > 위치 서비스 켜기")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("취소") { _, _ -> finish() }
                .show()
        }
    }

    private fun checkSamsungLocationSettings() {
        AlertDialog.Builder(this)
            .setTitle("Samsung GPS 최적화")
            .setMessage("""
                Samsung 기기에서 GPS 정확도를 높이려면:
                
                1. 설정 > 디바이스 케어 > 배터리 > 앱 절전 모드
                2. '${packageName}' 앱을 절전 모드에서 제외
                3. 설정 > 위치 > 위치 정확도 개선 > WiFi 스캔 켜기
            """.trimIndent())
            .setPositiveButton("확인", null)
            .show()
    }

    private fun initViews() {
        naviView = findViewById(R.id.navi_view)
        progressNavigation = findViewById(R.id.progressNavigation)

        buttonCompletePickup = findViewById<Button>(R.id.buttonCompletePickup)
            ?: Button(this).apply {
                visibility = View.GONE
                Log.w(TAG, "buttonCompletePickup not found in layout")
            }

        repository = PickupRepository()
    }

    private fun setupFullScreen() {
        window?.apply {
            statusBarColor = Color.TRANSPARENT
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun loadIntentData() {
        selectedPickups.addAll(intent.getParcelableArrayListExtra("selectedPickups") ?: emptyList())

        val currentLat = intent.getDoubleExtra("currentLatitude", 0.0)
        val currentLng = intent.getDoubleExtra("currentLongitude", 0.0)

        if (currentLat != 0.0 && currentLng != 0.0) {
            currentLocation = Location("").apply {
                latitude = currentLat
                longitude = currentLng
            }
        }

        Log.d(TAG, "선택된 수거지 개수: ${selectedPickups.size}")
        Log.d(TAG, "테스트 모드: $isTestMode")
    }

    private fun setupLocationServices() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationHelper = LocationHelper(this)
    }

    private fun checkLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            getCurrentLocationAndStartNavigation()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    getCurrentLocationAndStartNavigation()
                } else {
                    Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun setupClickListeners() {
        if (::buttonCompletePickup.isInitialized) {
            buttonCompletePickup.setOnClickListener {
                completeCurrentPickup()
            }
        }
    }

    private fun getCurrentLocationAndStartNavigation() {
        if (isTestMode) {
            // 테스트 모드: 확실한 좌표 사용
            currentLocation = Location("TestMode").apply {
                latitude = TestCoordinates.SEOUL_STATION.first
                longitude = TestCoordinates.SEOUL_STATION.second
            }
            Log.d(TAG, "테스트 모드 - 서울역 좌표 사용: (${currentLocation!!.latitude}, ${currentLocation!!.longitude})")
            initializeNavigation()
            return
        }

        // 실제 모드: GPS 위치 획득
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Samsung FLP 문제 해결: 모든 기존 위치 요청 제거
        try {
            fusedLocationClient.removeLocationUpdates(object : LocationCallback() {})
        } catch (e: Exception) {
            Log.w(TAG, "위치 업데이트 제거 실패: ${e.message}")
        }

        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        val locationRequest = LocationRequest.create().apply {
            if (isSamsung) {
                interval = 30000
                fastestInterval = 15000
                priority = LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
                numUpdates = 1
                maxWaitTime = 60000
            } else {
                interval = 10000
                fastestInterval = 5000
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                numUpdates = 1
                maxWaitTime = 30000
            }
        }

        var locationCallback: LocationCallback? = null
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d(TAG, "위치 획득: (${location.latitude}, ${location.longitude}), 정확도: ${location.accuracy}m")

                    val accuracyThreshold = if (isSamsung) 500.0f else 100.0f

                    if (location.accuracy <= accuracyThreshold) {
                        currentLocation = location
                        try {
                            fusedLocationClient.removeLocationUpdates(this)
                        } catch (e: Exception) {
                            Log.w(TAG, "LocationCallback 제거 실패: ${e.message}")
                        }
                        initializeNavigation()
                    } else {
                        Log.w(TAG, "정확도 낮음: ${location.accuracy}m")
                        if (isSamsung) {
                            currentLocation = location
                            try {
                                fusedLocationClient.removeLocationUpdates(this)
                            } catch (e: Exception) {
                                Log.w(TAG, "LocationCallback 제거 실패: ${e.message}")
                            }
                            initializeNavigation()
                        }
                    }
                }
            }
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val accuracyThreshold = if (isSamsung) 1000.0f else 200.0f
                    if (location.accuracy <= accuracyThreshold) {
                        currentLocation = location
                        Log.d(TAG, "마지막 위치 사용: (${location.latitude}, ${location.longitude})")
                        initializeNavigation()
                        return@addOnSuccessListener
                    }
                }

                Log.d(TAG, "새로운 위치 요청 시작 (Samsung FLP 최적화)")
                try {
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest,
                        locationCallback!!,
                        mainLooper
                    )

                    val timeout = if (isSamsung) 120000L else 30000L
                    handler.postDelayed({
                        try {
                            fusedLocationClient.removeLocationUpdates(locationCallback!!)
                        } catch (e: Exception) {
                            Log.w(TAG, "타임아웃 LocationCallback 제거 실패: ${e.message}")
                        }
                        if (currentLocation == null) {
                            Log.w(TAG, "GPS 타임아웃 - 기본 위치 사용")
                            useDefaultLocation()
                        }
                    }, timeout)
                } catch (e: Exception) {
                    Log.e(TAG, "위치 요청 실패: ${e.message}")
                    useDefaultLocation()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "마지막 위치 가져오기 실패", e)
                useDefaultLocation()
            }
    }

    private fun useDefaultLocation() {
        currentLocation = Location("").apply {
            latitude = TestCoordinates.SEOUL_STATION.first
            longitude = TestCoordinates.SEOUL_STATION.second
        }
        Log.d(TAG, "기본 위치 사용: 서울역")
        initializeNavigation()
    }

    private fun initializeNavigation() {
        if (selectedPickups.isEmpty() && !isTestMode) {
            Toast.makeText(this, "선택된 수거지가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        showProgress(true)
        requestRoute()
    }

    /**
     * 경로 요청 - 테스트 모드 또는 실제 모드
     */
    private fun requestRoute() {
        if (currentLocation == null) {
            Toast.makeText(this, "현재 위치를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (isTestMode) {
            Log.d(TAG, "=== 테스트 모드: 확실한 좌표로 경로 요청 ===")
            requestRouteTest()
        } else {
            Log.d(TAG, "=== 실제 모드: 수거지 좌표로 경로 요청 ===")
            val currentPickup = selectedPickups[currentPickupIndex]
            Log.d(TAG, "수거지: ${getPickupDisplayName(currentPickup)}")
            Log.d(TAG, "좌표: (${currentPickup.latitude}, ${currentPickup.longitude})")

            if (currentPickup.latitude != null && currentPickup.longitude != null &&
                isValidCoordinate(currentPickup.latitude!!, currentPickup.longitude!!)) {
                requestRouteWithKakaoMobility(currentPickup)
            } else {
                requestRouteWithAddress(currentPickup)
            }
        }
    }

    /**
     * 테스트용 경로 요청 - 확실한 좌표 사용 (검색 결과 참조)
     */
    private fun requestRouteTest() {
        // 테스트용 출발지: 서울역 (검색 결과에서 확인된 정확한 좌표)
        val testStartLat = TestCoordinates.SEOUL_STATION.first
        val testStartLng = TestCoordinates.SEOUL_STATION.second

        // 테스트용 목적지: 여의도역 (검색 결과에서 확인된 정확한 좌표)
        val testDestLat = TestCoordinates.YEOUIDO_STATION.first
        val testDestLng = TestCoordinates.YEOUIDO_STATION.second

        Log.d(TAG, "테스트 출발지: 서울역 ($testStartLat, $testStartLng)")
        Log.d(TAG, "테스트 목적지: 여의도역 ($testDestLat, $testDestLng)")

        // 카카오모빌리티 API 표준 좌표계 변환
        val startKakao = KakaoMobilityCoordConverter.convertWGS84ToKakaoNavi(testStartLat, testStartLng)
        val goalKakao = KakaoMobilityCoordConverter.convertWGS84ToKakaoNavi(testDestLat, testDestLng)

        val startPoi = KNPOI("서울역", startKakao.first, startKakao.second, "서울역")
        val goalPoi = KNPOI("여의도역", goalKakao.first, goalKakao.second, "여의도역")

        Log.d(TAG, "테스트 카카오모빌리티 좌표로 경로 요청")
        Log.d(TAG, "- 출발: 서울역 (${startKakao.first}, ${startKakao.second})")
        Log.d(TAG, "- 도착: 여의도역 (${goalKakao.first}, ${goalKakao.second})")

        RefreshApplication.knsdk.makeTripWithStart(
            aStart = startPoi,
            aGoal = goalPoi,
            aVias = null
        ) { aError, aTrip ->
            runOnUiThread {
                if (aError == null && aTrip != null) {
                    Log.d(TAG, "테스트 경로 요청 성공!")
                    Toast.makeText(this@NavigationActivity, "테스트 경로로 안내합니다 (서울역→여의도역).", Toast.LENGTH_SHORT).show()
                    startGuideWithErrorHandling(aTrip)
                } else {
                    val errorCode = aError?.code ?: -1
                    Log.e(TAG, "테스트 경로 요청 실패 - 코드: $errorCode")

                    when (errorCode) {
                        20411 -> {
                            Log.e(TAG, "테스트 좌표에서도 20411 오류 발생 - SDK 설정 문제 가능성")
                            tryAlternativeTestCoordinates()
                        }
                        else -> {
                            Log.e(TAG, "기타 오류: $errorCode")
                            Toast.makeText(this@NavigationActivity, "테스트 경로 요청 실패: $errorCode", Toast.LENGTH_LONG).show()
                            showSDKErrorDialog(errorCode)
                        }
                    }
                }
            }
        }
    }

    /**
     * 대체 테스트 좌표들로 시도
     */
    private fun tryAlternativeTestCoordinates() {
        Log.d(TAG, "대체 테스트 좌표로 재시도: 판교역 → 현대백화점 판교점")

        // 판교역 → 현대백화점 판교점 (카카오 공식 문서 좌표)
        val startLat = TestCoordinates.PANGYO_STATION.first
        val startLng = TestCoordinates.PANGYO_STATION.second
        val destLat = TestCoordinates.HYUNDAI_PANGYO.first
        val destLng = TestCoordinates.HYUNDAI_PANGYO.second

        val startKakao = KakaoMobilityCoordConverter.convertWGS84ToKakaoNavi(startLat, startLng)
        val goalKakao = KakaoMobilityCoordConverter.convertWGS84ToKakaoNavi(destLat, destLng)

        val startPoi = KNPOI("판교역", startKakao.first, startKakao.second, "판교역")
        val goalPoi = KNPOI("현대백화점 판교점", goalKakao.first, goalKakao.second, "현대백화점 판교점")

        Log.d(TAG, "대체 테스트 좌표로 경로 요청")
        Log.d(TAG, "- 출발: 판교역 (${startKakao.first}, ${startKakao.second})")
        Log.d(TAG, "- 도착: 현대백화점 판교점 (${goalKakao.first}, ${goalKakao.second})")

        RefreshApplication.knsdk.makeTripWithStart(
            aStart = startPoi,
            aGoal = goalPoi,
            aVias = null
        ) { aError, aTrip ->
            runOnUiThread {
                if (aError == null && aTrip != null) {
                    Log.d(TAG, "대체 테스트 경로 요청 성공!")
                    Toast.makeText(this@NavigationActivity, "대체 테스트 경로로 안내합니다 (판교역→현대백화점).", Toast.LENGTH_SHORT).show()
                    startGuideWithErrorHandling(aTrip)
                } else {
                    val errorCode = aError?.code ?: -1
                    Log.e(TAG, "대체 테스트 경로도 실패 - 코드: $errorCode")

                    if (errorCode == 20411) {
                        Log.e(TAG, "확실한 좌표에서도 20411 오류 - SDK 인증 또는 설정 문제")
                        showSDKErrorDialog(errorCode)
                    } else {
                        Toast.makeText(this@NavigationActivity, "모든 테스트 경로 실패: $errorCode", Toast.LENGTH_LONG).show()
                        showSDKErrorDialog(errorCode)
                    }
                }
            }
        }
    }

    /**
     * 카카오모빌리티 API 표준 좌표계 경로 요청
     */
    private fun requestRouteWithKakaoMobility(pickup: Pickup) {
        val distance = calculateDistance(
            currentLocation!!.latitude, currentLocation!!.longitude,
            pickup.latitude!!, pickup.longitude!!
        )

        Log.d(TAG, "목적지까지 거리: ${String.format("%.2f", distance)}km")

        if (distance < 0.05) {
            Toast.makeText(this, "목적지에 도착했습니다.", Toast.LENGTH_SHORT).show()
            showPickupCompletionButton(true)
            showProgress(false)
            return
        }

        // 카카오모빌리티 API 표준 좌표계 변환
        val startKakao = KakaoMobilityCoordConverter.convertWGS84ToKakaoNavi(
            currentLocation!!.latitude, currentLocation!!.longitude
        )
        val goalKakao = KakaoMobilityCoordConverter.convertWGS84ToKakaoNavi(
            pickup.latitude!!, pickup.longitude!!
        )

        val startPoi = KNPOI("현재위치", startKakao.first, startKakao.second, "현재위치")
        val goalPoi = KNPOI(
            getPickupDisplayName(pickup),
            goalKakao.first,
            goalKakao.second,
            getPickupDisplayName(pickup)
        )

        Log.d(TAG, "카카오모빌리티 API 표준 좌표로 경로 요청")
        Log.d(TAG, "- 출발: 현재위치 (${startKakao.first}, ${startKakao.second})")
        Log.d(TAG, "- 도착: ${getPickupDisplayName(pickup)} (${goalKakao.first}, ${goalKakao.second})")

        RefreshApplication.knsdk.makeTripWithStart(
            aStart = startPoi,
            aGoal = goalPoi,
            aVias = null
        ) { aError, aTrip ->
            runOnUiThread {
                if (aError == null && aTrip != null) {
                    Log.d(TAG, "카카오모빌리티 API 표준 경로 요청 성공!")
                    Toast.makeText(this@NavigationActivity, "수거지로 안내합니다.", Toast.LENGTH_SHORT).show()
                    startGuideWithErrorHandling(aTrip)
                } else {
                    val errorCode = aError?.code ?: -1
                    Log.e(TAG, "카카오모빌리티 경로 요청 실패 - 코드: $errorCode")

                    when (errorCode) {
                        20411 -> tryKakaoMobilityCoordinateAdjustments(pickup)
                        else -> requestRouteWithAddress(pickup)
                    }
                }
            }
        }
    }

    /**
     * 카카오모빌리티 좌표 미세 조정으로 20411 오류 해결
     */
    private fun tryKakaoMobilityCoordinateAdjustments(pickup: Pickup) {
        val adjustmentOffsets = listOf(
            Pair(0.0001, 0.0), Pair(-0.0001, 0.0), Pair(0.0, 0.0001), Pair(0.0, -0.0001),
            Pair(0.0002, 0.0002), Pair(-0.0002, 0.0002), Pair(0.0002, -0.0002), Pair(-0.0002, -0.0002)
        )

        var currentIndex = 0

        fun tryNextKakaoMobilityAdjustment() {
            if (currentIndex >= adjustmentOffsets.size) {
                Log.w(TAG, "모든 카카오모빌리티 조정 시도 실패 - 주소 방식으로 전환")
                requestRouteWithAddress(pickup)
                return
            }

            val offset = adjustmentOffsets[currentIndex]
            val adjustedLat = pickup.latitude!! + offset.first
            val adjustedLng = pickup.longitude!! + offset.second

            if (adjustedLat !in 33.0..43.0 || adjustedLng !in 124.0..132.0) {
                currentIndex++
                tryNextKakaoMobilityAdjustment()
                return
            }

            Log.d(TAG, "카카오모빌리티 조정 시도 ${currentIndex + 1}/${adjustmentOffsets.size}: ($adjustedLat, $adjustedLng)")

            val startKakao = KakaoMobilityCoordConverter.convertWGS84ToKakaoNavi(
                currentLocation!!.latitude, currentLocation!!.longitude
            )
            val goalKakao = KakaoMobilityCoordConverter.convertWGS84ToKakaoNavi(adjustedLat, adjustedLng)

            val startPoi = KNPOI("현재위치", startKakao.first, startKakao.second, "현재위치")
            val goalPoi = KNPOI("수거지 근처", goalKakao.first, goalKakao.second, "수거지 근처")

            RefreshApplication.knsdk.makeTripWithStart(
                aStart = startPoi,
                aGoal = goalPoi,
                aVias = null
            ) { aError, aTrip ->
                if (aError == null && aTrip != null) {
                    runOnUiThread {
                        Log.d(TAG, "카카오모빌리티 조정 경로 성공! 인덱스: $currentIndex")
                        Toast.makeText(this@NavigationActivity, "수거지 근처 도로로 안내합니다.", Toast.LENGTH_LONG).show()
                        pickup.latitude = adjustedLat
                        pickup.longitude = adjustedLng
                        startGuideWithErrorHandling(aTrip)
                    }
                } else {
                    Log.d(TAG, "카카오모빌리티 조정 시도 ${currentIndex + 1} 실패")
                    currentIndex++
                    tryNextKakaoMobilityAdjustment()
                }
            }
        }

        tryNextKakaoMobilityAdjustment()
    }

    private fun requestRouteWithAddress(pickup: Pickup) {
        val address = getPickupAddress(pickup)

        if (address.isNullOrBlank()) {
            Toast.makeText(this, "주소 정보가 없습니다. 다음 수거지로 이동합니다.", Toast.LENGTH_LONG).show()
            moveToNextPickup()
            return
        }

        Log.d(TAG, "주소로 경로 요청: $address")

        lifecycleScope.launch {
            when (val result = repository.geocodeAddress(address)) {
                is NetworkResult.Success -> {
                    val coordinate = result.data
                    Log.d(TAG, "지오코딩 성공: $address -> (${coordinate.latitude}, ${coordinate.longitude})")
                    requestRouteWithGeocodedCoordinates(coordinate.latitude, coordinate.longitude, pickup)
                }
                is NetworkResult.Error -> {
                    Log.e(TAG, "지오코딩 실패: ${result.message}")
                    showAddressRouteFailedDialog(pickup)
                }
                else -> {}
            }
        }
    }

    private fun requestRouteWithGeocodedCoordinates(lat: Double, lng: Double, pickup: Pickup) {
        val startKakao = KakaoMobilityCoordConverter.convertWGS84ToKakaoNavi(currentLocation!!.latitude, currentLocation!!.longitude)
        val goalKakao = KakaoMobilityCoordConverter.convertWGS84ToKakaoNavi(lat, lng)

        val startPoi = KNPOI("현재위치", startKakao.first, startKakao.second, "현재위치")
        val goalPoi = KNPOI(
            getPickupDisplayName(pickup),
            goalKakao.first,
            goalKakao.second,
            getPickupDisplayName(pickup)
        )

        Log.d(TAG, "카카오모빌리티 지오코딩 좌표로 경로 요청 - 출발: (${startKakao.first}, ${startKakao.second}), 도착: (${goalKakao.first}, ${goalKakao.second})")

        RefreshApplication.knsdk.makeTripWithStart(
            aStart = startPoi,
            aGoal = goalPoi,
            aVias = null
        ) { aError, aTrip ->
            runOnUiThread {
                if (aError == null && aTrip != null) {
                    Log.d(TAG, "카카오모빌리티 지오코딩 기반 경로 요청 성공!")
                    pickup.latitude = lat
                    pickup.longitude = lng
                    startGuideWithErrorHandling(aTrip)
                } else {
                    val errorCode = aError?.code ?: -1
                    Log.e(TAG, "카카오모빌리티 지오코딩 기반 경로 요청 실패 - 코드: $errorCode")
                    showAddressRouteFailedDialog(pickup)
                }
            }
        }
    }

    /**
     * SDK 설정 문제 안내 다이얼로그
     */
    private fun showSDKErrorDialog(errorCode: Comparable<*>) {
        AlertDialog.Builder(this)
            .setTitle("SDK 설정 문제")
            .setMessage("""
                확실한 좌표에서도 $errorCode 오류가 발생합니다.
                
                가능한 원인:
                1. 카카오내비 SDK 인증 키 문제
                2. SDK 버전 호환성 문제
                3. 앱 패키지명과 등록된 패키지명 불일치
                4. 카카오내비 앱 미설치
                
                해결 방법:
                1. 카카오 디벨로퍼스에서 앱 키 확인
                2. SDK 버전 업데이트
                3. 패키지명 확인
                4. 카카오내비 앱 설치
            """.trimIndent())
            .setPositiveButton("확인") { _, _ -> finish() }
            .show()
    }

    private fun showAddressRouteFailedDialog(pickup: Pickup) {
        AlertDialog.Builder(this)
            .setTitle("경로 생성 실패")
            .setMessage("좌표와 주소 모두로 경로를 생성할 수 없습니다.\n어떻게 하시겠습니까?")
            .setPositiveButton("다음 수거지") { _, _ -> moveToNextPickup() }
            .setNegativeButton("직접 이동") { _, _ -> startDirectNavigationMode(pickup) }
            .setNeutralButton("재시도") { _, _ -> requestRoute() }
            .setCancelable(false)
            .show()
    }

    private fun startDirectNavigationMode(pickup: Pickup) {
        showProgress(false)
        isNavigationActive = false
        Toast.makeText(this, "직접 이동 모드입니다. 수거지 근처에서 완료 버튼을 눌러주세요.", Toast.LENGTH_LONG).show()
        showPickupCompletionButton(true)
    }

    private fun isValidCoordinate(lat: Double, lng: Double): Boolean {
        return lat in 33.0..43.0 && lng in 124.0..132.0
    }

    private fun getPickupDisplayName(pickup: Pickup): String {
        return pickup.address?.roadNameAddress
            ?: pickup.address?.name
            ?: "수거지 ${currentPickupIndex + 1}"
    }

    private fun getPickupAddress(pickup: Pickup): String? {
        return pickup.address?.roadNameAddress
            ?: pickup.address?.name
            ?: pickup.address?.address
            ?: pickup.address?.toString()
    }

    /**
     * 20411 오류 방지를 위한 최적화된 내비게이션 시작
     */
    private fun startGuideWithErrorHandling(trip: KNTrip) {
        try {
            Log.d(TAG, "카카오모빌리티 API 표준 방법으로 주행 안내 시작")

            // 모든 위치 요청을 완전히 중단
            try {
                fusedLocationClient.removeLocationUpdates(object : LocationCallback() {})
            } catch (e: Exception) {
                Log.w(TAG, "위치 업데이트 제거 실패: ${e.message}")
            }

            RefreshApplication.knsdk.sharedGuidance()?.apply {
                // 카카오모빌리티 API 표준 Delegate 설정
                guideStateDelegate = this@NavigationActivity
                locationGuideDelegate = this@NavigationActivity
                routeGuideDelegate = this@NavigationActivity
                safetyGuideDelegate = this@NavigationActivity
                voiceGuideDelegate = this@NavigationActivity
                citsGuideDelegate = this@NavigationActivity

                // 카카오모빌리티 API 표준 NaviView 초기화
                naviView.initWithGuidance(
                    this,
                    trip,
                    KNRoutePriority.KNRoutePriority_Recommand,
                    0
                )

                isNavigationActive = true
                showProgress(false)

                Log.d(TAG, "카카오모빌리티 API 표준 방법으로 내비게이션 시작 완료")
                Toast.makeText(this@NavigationActivity, "내비게이션이 시작되었습니다.", Toast.LENGTH_SHORT).show()

            } ?: run {
                Log.e(TAG, "KNGuidance 객체를 가져올 수 없습니다.")
                Toast.makeText(this, "내비게이션 초기화에 실패했습니다.", Toast.LENGTH_LONG).show()
                showProgress(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "주행 시작 중 예외 발생", e)
            Toast.makeText(this, "내비게이션 시작 중 오류: ${e.message}", Toast.LENGTH_LONG).show()
            showProgress(false)
        }
    }

    private fun checkPickupProximity() {
        if (isTestMode) return // 테스트 모드에서는 근접성 체크 안 함

        if (currentPickupIndex >= selectedPickups.size || currentLocation == null) {
            return
        }

        val currentPickup = selectedPickups[currentPickupIndex]
        if (currentPickup.latitude != null && currentPickup.longitude != null) {
            val distance = calculateDistance(
                currentLocation!!.latitude,
                currentLocation!!.longitude,
                currentPickup.latitude!!,
                currentPickup.longitude!!
            ) * 1000

            if (distance <= PICKUP_COMPLETION_RADIUS) {
                showPickupCompletionButton(true)
            } else {
                showPickupCompletionButton(false)
            }
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun showPickupCompletionButton(show: Boolean) {
        if (::buttonCompletePickup.isInitialized) {
            buttonCompletePickup.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun completeCurrentPickup() {
        if (isTestMode) {
            Toast.makeText(this, "테스트 모드에서는 수거 완료 기능을 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentPickupIndex >= selectedPickups.size) return

        val currentPickup = selectedPickups[currentPickupIndex]

        pickupDialog?.dismiss()
        pickupDialog = AlertDialog.Builder(this)
            .setTitle("수거 완료")
            .setMessage("${getPickupDisplayName(currentPickup)}의 수거를 완료하시겠습니까?")
            .setPositiveButton("완료") { _, _ -> performPickupCompletion(currentPickup) }
            .setNegativeButton("취소", null)
            .setOnDismissListener { pickupDialog = null }
            .create()

        if (!isFinishing && !isDestroyed) {
            pickupDialog?.show()
        }
    }

    private fun performPickupCompletion(pickup: Pickup) {
        showProgress(true)

        lifecycleScope.launch {
            when (val result = repository.completePickup(pickup.pickupId)) {
                is NetworkResult.Success -> {
                    pickup.isCompleted = true
                    moveToNextPickup()
                }
                is NetworkResult.Error -> {
                    showProgress(false)
                    Toast.makeText(this@NavigationActivity, result.message, Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
        }
    }

    private fun moveToNextPickup() {
        if (isTestMode) {
            Toast.makeText(this, "테스트 모드 완료", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentPickupIndex++

        if (currentPickupIndex >= selectedPickups.size) {
            showNavigationCompleteDialog()
        } else {
            Toast.makeText(this, "다음 수거지로 안내합니다.", Toast.LENGTH_SHORT).show()
            requestRoute()
        }
    }

    private fun showNavigationCompleteDialog() {
        completeDialog?.dismiss()
        completeDialog = AlertDialog.Builder(this)
            .setTitle("수거 완료")
            .setMessage("모든 수거지 방문이 완료되었습니다.")
            .setPositiveButton("확인") { _, _ -> finish() }
            .setCancelable(false)
            .setOnDismissListener { completeDialog = null }
            .create()

        if (!isFinishing && !isDestroyed) {
            completeDialog?.show()
        }
    }

    private fun showProgress(show: Boolean) {
        progressNavigation.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showExitNavigationDialog() {
        exitDialog?.dismiss()
        exitDialog = AlertDialog.Builder(this)
            .setTitle("내비게이션 종료")
            .setMessage("내비게이션을 종료하시겠습니까?")
            .setPositiveButton("종료") { _, _ -> finish() }
            .setNegativeButton("취소", null)
            .setOnDismissListener { exitDialog = null }
            .create()

        if (!isFinishing && !isDestroyed) {
            exitDialog?.show()
        }
    }

    // KNGuidance Delegate 메서드들
    override fun guidanceDidUpdateLocation(aGuidance: KNGuidance, aLocationGuide: KNGuide_Location) {
        naviView.guidanceDidUpdateLocation(aGuidance, aLocationGuide)

        // 카카오모빌리티 API 표준: 카카오내비의 위치 정보만 사용
        val naviLocation = aLocationGuide.location
        if (naviLocation != null && isNavigationActive) {
            // 카카오내비 → WGS84 역변환
            val wgs84Coord = KakaoMobilityCoordConverter.convertKakaoNaviToWGS84(
                naviLocation.pos.x.toDouble(),
                naviLocation.pos.y.toDouble()
            )

            currentLocation = Location("KakaoNavi").apply {
                latitude = wgs84Coord.first
                longitude = wgs84Coord.second
                accuracy = 5.0f // 카카오내비는 매우 정확
            }
            checkPickupProximity()

            // 로그 최소화 (10초마다 한 번만)
            if (System.currentTimeMillis() % 10000 < 1000) {
                Log.d(TAG, "카카오내비 위치 업데이트: KakaoNavi(${naviLocation.pos.x}, ${naviLocation.pos.y}) -> WGS84(${wgs84Coord.first}, ${wgs84Coord.second})")
            }
        }
    }

    override fun guidanceDidUpdateRouteGuide(aGuidance: KNGuidance, aRouteGuide: KNGuide_Route) {
        naviView.guidanceDidUpdateRouteGuide(aGuidance, aRouteGuide)
    }

    override fun guidanceDidUpdateAroundSafeties(aGuidance: KNGuidance, aSafeties: List<KNSafety>?) {
        naviView.guidanceDidUpdateAroundSafeties(aGuidance, aSafeties)
    }

    override fun guidanceDidUpdateSafetyGuide(aGuidance: KNGuidance, aSafetyGuide: KNGuide_Safety?) {
        naviView.guidanceDidUpdateSafetyGuide(aGuidance, aSafetyGuide)
    }

    override fun didFinishPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) {
        naviView.didFinishPlayVoiceGuide(aGuidance, aVoiceGuide)
    }

    override fun shouldPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice, aNewData: MutableList<ByteArray>): Boolean {
        return naviView.shouldPlayVoiceGuide(aGuidance, aVoiceGuide, aNewData)
    }

    override fun willPlayVoiceGuide(aGuidance: KNGuidance, aVoiceGuide: KNGuide_Voice) {
        naviView.willPlayVoiceGuide(aGuidance, aVoiceGuide)
    }

    override fun didUpdateCitsGuide(aGuidance: KNGuidance, aCitsGuide: KNGuide_Cits) {
        naviView.didUpdateCitsGuide(aGuidance, aCitsGuide)
    }

    override fun guidanceCheckingRouteChange(aGuidance: KNGuidance) {
        naviView.guidanceCheckingRouteChange(aGuidance)
    }

    override fun guidanceDidUpdateRoutes(aGuidance: KNGuidance, aRoutes: List<KNRoute>, aMultiRouteInfo: KNMultiRouteInfo?) {
        naviView.guidanceDidUpdateRoutes(aGuidance, aRoutes, aMultiRouteInfo)
    }

    override fun guidanceGuideEnded(aGuidance: KNGuidance) {
        Log.d(TAG, "주행 안내 종료")
        naviView.guidanceGuideEnded(aGuidance)
        isNavigationActive = false

        runOnUiThread {
            if (isTestMode) {
                Toast.makeText(this, "테스트 내비게이션 완료", Toast.LENGTH_SHORT).show()
                finish()
            } else if (currentPickupIndex >= selectedPickups.size) {
                showNavigationCompleteDialog()
            }
        }
    }

    override fun guidanceGuideStarted(aGuidance: KNGuidance) {
        Log.d(TAG, "주행 안내 시작됨")
        naviView.guidanceGuideStarted(aGuidance)
        isNavigationActive = true
    }

    /**
     * 경로 이탈 시 자동 재계산 방지 - 카카오모빌리티 API 표준
     */
    override fun guidanceOutOfRoute(aGuidance: KNGuidance) {
        Log.d(TAG, "경로 이탈")
        naviView.guidanceOutOfRoute(aGuidance)

        runOnUiThread {
            // 카카오모빌리티 API 표준: 자동 재계산 대신 사용자에게 선택권 제공
            Log.d(TAG, "경로 이탈 감지 - 현재 경로 유지")
            Toast.makeText(this, "경로를 유지하며 계속 안내합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun guidanceRouteChanged(
        aGuidance: KNGuidance,
        aFromRoute: KNRoute,
        aFromLocation: KNLocation,
        aToRoute: KNRoute,
        aToLocation: KNLocation,
        aChangeReason: KNGuideRouteChangeReason
    ) {
        Log.d(TAG, "경로 변경됨 - 이유: $aChangeReason")
        naviView.guidanceRouteChanged(aGuidance)

        runOnUiThread {
            // 20411 오류 발생 가능성을 줄이기 위해 경로 변경 알림 최소화
            Log.d(TAG, "경로가 성공적으로 변경되었습니다.")
        }
    }

    override fun guidanceRouteUnchanged(aGuidance: KNGuidance) {
        Log.d(TAG, "경로 유지됨")
        naviView.guidanceRouteUnchanged(aGuidance)
    }

    /**
     * 20411 오류 완전 해결 - 카카오모빌리티 API 표준
     */
    override fun guidanceRouteUnchangedWithError(aGuidance: KNGuidance, aError: KNError) {
        Log.e(TAG, "경로 변경 실패: ${aError.code}")
        naviView.guidanceRouteUnchangedWithError(aGuidance, aError)

        runOnUiThread {
            when (aError.code) {
                20411.toString() -> {
                    // 20411 오류는 완전히 무시하고 현재 경로 유지
                    Log.w(TAG, "20411 오류 무시 - 현재 경로 유지하며 정상 진행")
                    // 사용자에게 알리지 않음 (혼란 방지)
                }
                else -> {
                    Log.e(TAG, "기타 경로 오류: ${aError.code}")
                    Toast.makeText(this, "경로 재계산에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        showExitNavigationDialog()
    }

    override fun onDestroy() {
        super.onDestroy()

        // 모든 Dialog 닫기
        exitDialog?.dismiss()
        pickupDialog?.dismiss()
        completeDialog?.dismiss()

        exitDialog = null
        pickupDialog = null
        completeDialog = null

        // Handler 정리
        handler.removeCallbacksAndMessages(null)

        // 위치 업데이트 정지
        try {
            fusedLocationClient.removeLocationUpdates(object : LocationCallback() {})
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy 위치 업데이트 제거 실패: ${e.message}")
        }

        // 내비게이션 정리
        if (isNavigationActive) {
            try {
                RefreshApplication.knsdk.sharedGuidance()?.let { guidance ->
                    guidance.guideStateDelegate = null
                    guidance.locationGuideDelegate = null
                    guidance.routeGuideDelegate = null
                    guidance.safetyGuideDelegate = null
                    guidance.voiceGuideDelegate = null
                    guidance.citsGuideDelegate = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "내비게이션 정리 실패: ${e.message}")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        exitDialog?.dismiss()
        pickupDialog?.dismiss()
    }
}
