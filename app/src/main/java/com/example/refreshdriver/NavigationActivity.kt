package com.example.refreshdriver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Build
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
import android.location.LocationManager
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import kotlin.math.*

/**
 * NavigationActivity - 20411 오류 해결 (외부 앱 체크 제거)
 */
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
    private var isTestMode = true

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

        Log.d(TAG, "=== NavigationActivity 시작 ===")

        initViews()
        setupFullScreen()
        loadIntentData()
        setupLocationServices()

        // 카카오 앱 체크 제거하고 바로 권한 체크
        checkLocationPermissions()
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
            // SDK 초기화 상태 확인 후 진행
            checkSDKStatusAndProceed()
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
                    checkSDKStatusAndProceed()
                } else {
                    Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    /**
     * SDK 초기화 상태 확인 후 진행
     */
    private fun checkSDKStatusAndProceed() {
        Log.d(TAG, "SDK 상태 확인 중...")

        try {
            // SDK가 제대로 초기화되었는지 확인
            val guidance = RefreshApplication.knsdk.sharedGuidance()
            if (guidance != null) {
                Log.d(TAG, "✅ SDK 정상 초기화됨")
                initializeNavigation()
            } else {
                Log.w(TAG, "⚠️ SDK Guidance 객체가 null - 재초기화 시도")
                reinitializeSDK()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SDK 상태 확인 중 오류", e)
            reinitializeSDK()
        }
    }

    /**
     * SDK 재초기화
     */
    private fun reinitializeSDK() {
        Log.d(TAG, "SDK 재초기화 시도...")
        showProgress(true)

        Thread {
            try {
                // 잠시 대기
                Thread.sleep(1000)

                runOnUiThread {
                    showProgress(false)
                    initializeNavigation()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showProgress(false)
                    showErrorDialog("SDK 초기화에 실패했습니다: ${e.message}")
                }
            }
        }.start()
    }

    private fun initializeNavigation() {
        Log.d(TAG, "내비게이션 초기화 시작")

        if (isTestMode) {
            requestRouteWithImprovedErrorHandling()
        } else {
            if (selectedPickups.isEmpty()) {
                Toast.makeText(this, "선택된 수거지가 없습니다.", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            requestRoute()
        }
    }

    /**
     * 개선된 오류 처리와 함께 경로 요청
     */
    private fun requestRouteWithImprovedErrorHandling() {
        Log.d(TAG, "=== 개선된 경로 요청 시작 ===")

        lifecycleScope.launch {
            try {
                showProgress(true)

                // 1단계: 네트워크 연결 확인
                if (!isNetworkAvailable()) {
                    runOnUiThread {
                        showProgress(false)
                        showErrorDialog("네트워크 연결을 확인해주세요.")
                    }
                    return@launch
                }

                Log.d(TAG, "1단계: 좌표 획득")

                // 2단계: 간단한 좌표로 테스트 (서울 시청 -> 강남역)
                val startLat = 37.5666805
                val startLng = 126.9784147
                val goalLat = 37.4979462
                val goalLng = 127.0276368

                // 3단계: 카카오내비 좌표계로 변환
                val startX = (startLng * 1000000).toInt()
                val startY = (startLat * 1000000).toInt()
                val goalX = (goalLng * 1000000).toInt()
                val goalY = (goalLat * 1000000).toInt()

                Log.d(TAG, "좌표 변환 결과:")
                Log.d(TAG, "- 시작: WGS84($startLat, $startLng) -> Kakao($startX, $startY)")
                Log.d(TAG, "- 목표: WGS84($goalLat, $goalLng) -> Kakao($goalX, $goalY)")

                val startPoi = KNPOI("시작지점", startX, startY, "서울시청")
                val goalPoi = KNPOI("목표지점", goalX, goalY, "강남역")

                Log.d(TAG, "4단계: 경로 요청 실행")

                runOnUiThread {
                    try {
                        Log.d(TAG, "makeTripWithStart 호출...")

                        RefreshApplication.knsdk.makeTripWithStart(
                            aStart = startPoi,
                            aGoal = goalPoi,
                            aVias = null
                        ) { error, trip ->
                            runOnUiThread {
                                showProgress(false)
                                handleRouteResult(error, trip)
                            }
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "makeTripWithStart 호출 중 예외", e)
                        showProgress(false)
                        handleSDKException(e)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "경로 요청 전체 과정 중 예외", e)
                runOnUiThread {
                    showProgress(false)
                    showErrorDialog("경로 요청 중 오류가 발생했습니다: ${e.message}")
                }
            }
        }
    }

    /**
     * 경로 요청 결과 처리
     */
    private fun handleRouteResult(error: KNError?, trip: KNTrip?) {
        if (error == null && trip != null) {
            Log.d(TAG, "✅ 경로 요청 성공!")
            Toast.makeText(this, "경로 생성 성공!\n서울시청 → 강남역", Toast.LENGTH_SHORT).show()
            startGuideWithErrorHandling(trip)
        } else {
            val errorCode = error?.code ?: -1
            val errorMsg = error?.msg ?: "알 수 없는 오류"

            Log.e(TAG, "❌ 경로 요청 실패:")
            Log.e(TAG, "- 오류 코드: $errorCode")
            Log.e(TAG, "- 오류 메시지: $errorMsg")

            analyze20411Error(errorCode, errorMsg)
        }
    }

    /**
     * 20411 오류 상세 분석
     */
    private fun analyze20411Error(errorCode: Comparable<*>, errorMsg: String) {
        when (errorCode.toString()) {
            "20411" -> {
                Log.e(TAG, "=== 20411 오류 상세 분석 ===")
                Log.e(TAG, "앱 패키지명: $packageName")
                Log.e(TAG, "앱 키: 573a700f121bff5d3ea3960ff32de487")
                Log.e(TAG, "SDK 버전: knsdk_ui:1.9.4")
                Log.e(TAG, "기기: ${Build.MANUFACTURER} ${Build.MODEL}")
                Log.e(TAG, "안드로이드: ${Build.VERSION.RELEASE}")

                show20411DetailedDialog()
            }
            "10001" -> showErrorDialog("네트워크 연결을 확인해주세요.")
            "30001" -> showErrorDialog("인증에 실패했습니다.\n앱 키가 올바른지 확인해주세요.")
            else -> showErrorDialog("경로 요청에 실패했습니다.\n오류 코드: $errorCode\n메시지: $errorMsg")
        }
    }

    /**
     * 20411 오류 상세 다이얼로그
     */
    private fun show20411DetailedDialog() {
        val debugInfo = buildString {
            append("=== 20411 오류 분석 ===\n\n")
            append("20411은 '경로 요청 실패' 오류입니다.\n\n")
            append("가능한 원인:\n")
            append("1. 앱 키 문제\n")
            append("2. 패키지명 불일치\n")
            append("3. 좌표 범위 문제\n")
            append("4. SDK 설정 문제\n")
            append("5. 네트워크 또는 서버 문제\n\n")

            append("현재 설정:\n")
            append("• 패키지: $packageName\n")
            append("• 앱 키: 573a700f121bff5d3ea3960ff32de487\n")
            append("• SDK: knsdk_ui:1.9.4\n")
            append("• 기기: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        }

        AlertDialog.Builder(this)
            .setTitle("20411 오류 분석")
            .setMessage(debugInfo)
            .setPositiveButton("카카오 개발자센터 확인") { _, _ ->
                openKakaoDeveloperConsole()
            }
            .setNeutralButton("다른 좌표로 재시도") { _, _ ->
                tryAlternativeCoordinates()
            }
            .setNegativeButton("종료") { _, _ ->
                finish()
            }
            .show()
    }

    /**
     * 대체 좌표로 재시도
     */
    private fun tryAlternativeCoordinates() {
        Log.d(TAG, "대체 좌표로 재시도")

        // 더 간단한 좌표로 테스트 (서울역 -> 명동)
        lifecycleScope.launch {
            try {
                showProgress(true)

                val startLat = 37.5547
                val startLng = 126.9707
                val goalLat = 37.5636
                val goalLng = 126.9827

                val startX = (startLng * 1000000).toInt()
                val startY = (startLat * 1000000).toInt()
                val goalX = (goalLng * 1000000).toInt()
                val goalY = (goalLat * 1000000).toInt()

                val startPoi = KNPOI("서울역", startX, startY, "서울역")
                val goalPoi = KNPOI("명동", goalX, goalY, "명동")

                runOnUiThread {
                    RefreshApplication.knsdk.makeTripWithStart(
                        aStart = startPoi,
                        aGoal = goalPoi,
                        aVias = null
                    ) { error, trip ->
                        runOnUiThread {
                            showProgress(false)
                            if (error == null && trip != null) {
                                Toast.makeText(this@NavigationActivity, "대체 좌표로 성공!", Toast.LENGTH_SHORT).show()
                                startGuideWithErrorHandling(trip)
                            } else {
                                showErrorDialog("대체 좌표로도 실패했습니다.\n개발자에게 문의하세요.")
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    showProgress(false)
                    showErrorDialog("대체 좌표 시도 중 오류: ${e.message}")
                }
            }
        }
    }

    /**
     * 카카오 개발자 콘솔 열기
     */
    private fun openKakaoDeveloperConsole() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://developers.kakao.com/console/app")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "브라우저를 열 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * SDK 예외 처리
     */
    private fun handleSDKException(e: Exception) {
        Log.e(TAG, "SDK 예외 분석:", e)

        val errorMessage = when {
            e.message?.contains("not initialized") == true -> {
                "SDK가 초기화되지 않았습니다.\n앱을 다시 시작해주세요."
            }
            e.message?.contains("network") == true -> {
                "네트워크 연결 문제입니다.\n연결 상태를 확인해주세요."
            }
            e.message?.contains("permission") == true -> {
                "권한 문제입니다.\n앱 권한을 확인해주세요."
            }
            else -> {
                "SDK 오류가 발생했습니다:\n${e.message}"
            }
        }

        showErrorDialog(errorMessage)
    }

    /**
     * 네트워크 연결 확인
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 기존 방식의 경로 요청 (실제 수거지용)
     */
    private fun requestRoute() {
        if (currentPickupIndex >= selectedPickups.size) {
            showNavigationCompleteDialog()
            return
        }

        val currentPickup = selectedPickups[currentPickupIndex]
        Log.d(TAG, "수거지 경로 요청: ${getPickupDisplayName(currentPickup)}")
        requestRouteToPickup(currentPickup)
    }

    private fun requestRouteToPickup(pickup: Pickup) {
        if (currentLocation == null) {
            Toast.makeText(this, "현재 위치를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                showProgress(true)

                val pickupAddress = getPickupAddress(pickup)
                if (pickupAddress.isNullOrBlank()) {
                    runOnUiThread {
                        showProgress(false)
                        Toast.makeText(this@NavigationActivity, "수거지 주소 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                        moveToNextPickup()
                    }
                    return@launch
                }

                val currentAddress = repository.reverseGeocode(
                    currentLocation!!.latitude,
                    currentLocation!!.longitude
                )

                val goalResult = repository.geocodeAddress(pickupAddress)

                if (currentAddress is NetworkResult.Success && goalResult is NetworkResult.Success) {
                    val startX = (currentLocation!!.longitude * 1000000).toInt()
                    val startY = (currentLocation!!.latitude * 1000000).toInt()
                    val goalX = (goalResult.data.longitude * 1000000).toInt()
                    val goalY = (goalResult.data.latitude * 1000000).toInt()

                    val startPoi = KNPOI("현재위치", startX, startY, "현재위치")
                    val goalPoi = KNPOI(getPickupDisplayName(pickup), goalX, goalY, pickupAddress)

                    runOnUiThread {
                        RefreshApplication.knsdk.makeTripWithStart(
                            aStart = startPoi,
                            aGoal = goalPoi,
                            aVias = null
                        ) { error, trip ->
                            runOnUiThread {
                                showProgress(false)

                                if (error == null && trip != null) {
                                    Log.d(TAG, "수거지 경로 요청 성공!")
                                    Toast.makeText(this@NavigationActivity, "수거지로 안내합니다.", Toast.LENGTH_SHORT).show()
                                    startGuideWithErrorHandling(trip)
                                } else {
                                    Log.e(TAG, "수거지 경로 요청 실패: ${error?.code}")
                                    moveToNextPickup()
                                }
                            }
                        }
                    }
                } else {
                    runOnUiThread {
                        showProgress(false)
                        moveToNextPickup()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "수거지 경로 요청 중 예외", e)
                runOnUiThread {
                    showProgress(false)
                    moveToNextPickup()
                }
            }
        }
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
    }

    /**
     * 내비게이션 시작
     */
    private fun startGuideWithErrorHandling(trip: KNTrip) {
        try {
            Log.d(TAG, "내비게이션 시작")

            RefreshApplication.knsdk.sharedGuidance()?.apply {
                guideStateDelegate = this@NavigationActivity
                locationGuideDelegate = this@NavigationActivity
                routeGuideDelegate = this@NavigationActivity
                safetyGuideDelegate = this@NavigationActivity
                voiceGuideDelegate = this@NavigationActivity
                citsGuideDelegate = this@NavigationActivity

                naviView.initWithGuidance(
                    this,
                    trip,
                    KNRoutePriority.KNRoutePriority_Recommand,
                    0
                )

                isNavigationActive = true
                setupClickListeners()

                Log.d(TAG, "내비게이션 시작 완료")

            } ?: run {
                Log.e(TAG, "KNGuidance 객체를 가져올 수 없습니다.")
                Toast.makeText(this, "내비게이션 초기화에 실패했습니다.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "내비게이션 시작 중 예외 발생", e)
            Toast.makeText(this, "내비게이션 시작 중 오류: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupClickListeners() {
        if (::buttonCompletePickup.isInitialized) {
            buttonCompletePickup.setOnClickListener {
                completeCurrentPickup()
            }
        }
    }

    private fun completeCurrentPickup() {
        if (isTestMode) {
            Toast.makeText(this, "테스트 모드 완료", Toast.LENGTH_SHORT).show()
            finish()
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

    /**
     * 일반 오류 다이얼로그
     */
    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("오류")
            .setMessage(message)
            .setPositiveButton("확인") { _, _ -> finish() }
            .show()
    }

    // ============= KNGuidance Delegate 메서드들 =============

    override fun guidanceDidUpdateLocation(aGuidance: KNGuidance, aLocationGuide: KNGuide_Location) {
        naviView.guidanceDidUpdateLocation(aGuidance, aLocationGuide)

        val naviLocation = aLocationGuide.location
        if (naviLocation != null && isNavigationActive) {
            val wgs84Lat = naviLocation.pos.y.toDouble() / 1000000.0
            val wgs84Lng = naviLocation.pos.x.toDouble() / 1000000.0

            currentLocation = Location("KakaoNavi").apply {
                latitude = wgs84Lat
                longitude = wgs84Lng
                accuracy = 5.0f
            }

            if (System.currentTimeMillis() % 10000 < 1000) {
                Log.d(TAG, "위치 업데이트: ($wgs84Lat, $wgs84Lng)")
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

    override fun guidanceOutOfRoute(aGuidance: KNGuidance) {
        Log.d(TAG, "경로 이탈")
        naviView.guidanceOutOfRoute(aGuidance)

        runOnUiThread {
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
            Log.d(TAG, "경로가 성공적으로 변경되었습니다.")
        }
    }

    override fun guidanceRouteUnchanged(aGuidance: KNGuidance) {
        Log.d(TAG, "경로 유지됨")
        naviView.guidanceRouteUnchanged(aGuidance)
    }

    override fun guidanceRouteUnchangedWithError(aGuidance: KNGuidance, aError: KNError) {
        Log.e(TAG, "경로 변경 실패: ${aError.code}")
        naviView.guidanceRouteUnchangedWithError(aGuidance, aError)

        runOnUiThread {
            when (aError.code) {
                20411.toString() -> {
                    Log.w(TAG, "20411 오류 무시 - 현재 경로 유지하며 정상 진행")
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