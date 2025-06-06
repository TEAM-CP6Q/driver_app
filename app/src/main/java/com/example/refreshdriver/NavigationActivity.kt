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

    private lateinit var textCurrentSpeed: TextView
    private lateinit var textSpeedLimit: TextView
    private lateinit var textRemainingDistance: TextView
    private lateinit var textRemainingTime: TextView
    private lateinit var textNextInstruction: TextView
    private lateinit var textCurrentRoad: TextView
    private lateinit var buttonCompletePickup: Button
    private lateinit var buttonExitNavigation: Button
    private lateinit var progressNavigation: ProgressBar
    private lateinit var layoutNavigationInfo: LinearLayout

    private val selectedPickups = mutableListOf<Pickup>()
    private var currentLocation: Location? = null
    private var currentPickupIndex = 0
    private var isNavigationActive = false

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val PICKUP_COMPLETION_RADIUS = 200.0 // 200미터
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        initViews()
        setupFullScreen()
        loadIntentData()
        setupLocationServices()
        checkLocationPermissions()
        setupClickListeners()
    }

    private fun initViews() {
        naviView = findViewById(R.id.navi_view)
        textCurrentSpeed = findViewById(R.id.textCurrentSpeed)
        textSpeedLimit = findViewById(R.id.textSpeedLimit)
        textRemainingDistance = findViewById(R.id.textRemainingDistance)
        textRemainingTime = findViewById(R.id.textRemainingTime)
        textNextInstruction = findViewById(R.id.textNextInstruction)
        textCurrentRoad = findViewById(R.id.textCurrentRoad)
        buttonCompletePickup = findViewById(R.id.buttonCompletePickup)
        buttonExitNavigation = findViewById(R.id.buttonExitNavigation)
        progressNavigation = findViewById(R.id.progressNavigation)
        layoutNavigationInfo = findViewById(R.id.layoutNavigationInfo)

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

        Log.d("NavigationActivity", "선택된 수거지 개수: ${selectedPickups.size}")
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
        buttonCompletePickup.setOnClickListener {
            completeCurrentPickup()
        }

        buttonExitNavigation.setOnClickListener {
            showExitNavigationDialog()
        }
    }

    /**
     * 현재 위치를 가져온 후 내비게이션을 시작합니다.
     */
    private fun getCurrentLocationAndStartNavigation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    currentLocation = location
                    Log.d("NavigationActivity", "현재 위치: ${location.latitude}, ${location.longitude}")
                    startLocationTracking()
                    initializeNavigation()
                } else {
                    // 마지막 위치가 없으면 위치 업데이트 요청
                    requestLocationUpdates()
                }
            }
            .addOnFailureListener { e ->
                Log.e("NavigationActivity", "위치 가져오기 실패", e)
                if (selectedPickups.isNotEmpty()) {
                    // 수거지가 있으면 테스트용으로 첫 번째 수거지 근처 위치 사용
                    useDefaultLocation()
                } else {
                    Toast.makeText(this, "위치를 가져올 수 없습니다.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
    }

    /**
     * 위치 업데이트를 요청합니다.
     */
    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult ?: return
                for (location in locationResult.locations) {
                    currentLocation = location
                    Log.d("NavigationActivity", "업데이트된 위치: ${location.latitude}, ${location.longitude}")
                    fusedLocationClient.removeLocationUpdates(this)
                    startLocationTracking()
                    initializeNavigation()
                    break
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        }
    }

    /**
     * 테스트용 기본 위치를 사용합니다.
     */
    private fun useDefaultLocation() {
        // 실제 수거지가 있으면 첫 번째 수거지 근처 사용, 없으면 서울 시청
        if (selectedPickups.isNotEmpty()) {
            val firstPickup = selectedPickups[0]
            if (firstPickup.latitude != null && firstPickup.longitude != null) {
                currentLocation = Location("").apply {
                    // 첫 번째 수거지에서 약 100m 떨어진 위치로 설정 (테스트용)
                    latitude = firstPickup.latitude!! + 0.0009 // 약 100m
                    longitude = firstPickup.longitude!! + 0.0009
                }
                Log.d("NavigationActivity", "기본 위치 사용: 첫 번째 수거지 근처 (${currentLocation!!.latitude}, ${currentLocation!!.longitude})")
            } else {
                // 수거지 좌표가 없으면 서울 시청
                currentLocation = Location("").apply {
                    latitude = 37.5666805
                    longitude = 126.9784147
                }
                Log.d("NavigationActivity", "기본 위치 사용: 서울 시청")
            }
        } else {
            // 수거지가 없으면 서울 시청
            currentLocation = Location("").apply {
                latitude = 37.5666805
                longitude = 126.9784147
            }
            Log.d("NavigationActivity", "기본 위치 사용: 서울 시청 (수거지 없음)")
        }

        startLocationTracking()
        initializeNavigation()
    }

    private fun startLocationTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val locationRequest = LocationRequest.create().apply {
            interval = 1000 // 1초마다 업데이트
            fastestInterval = 500
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLocation = location
                    updateSpeedDisplay(location)
                    checkPickupProximity()
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
    }

    private fun initializeNavigation() {
        if (selectedPickups.isEmpty()) {
            Toast.makeText(this, "선택된 수거지가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        showProgress(true)
        requestRoute()
    }

    private fun requestRoute() {
        Thread {
            try {
                if (currentLocation == null) {
                    runOnUiThread {
                        Toast.makeText(this, "현재 위치를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@Thread
                }

                val currentPickup = selectedPickups[currentPickupIndex]

                if (currentPickup.latitude == null || currentPickup.longitude == null) {
                    runOnUiThread {
                        Toast.makeText(this, "수거지 좌표 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                        moveToNextPickup()
                    }
                    return@Thread
                }

                Log.d("NavigationActivity", "경로 요청 - 현재: (${currentLocation!!.latitude}, ${currentLocation!!.longitude}), 목적지: (${currentPickup.latitude}, ${currentPickup.longitude})")

                // WGS84 좌표를 카텍 좌표로 변환
                val startKatec = convertWGS84ToKatec(currentLocation!!.latitude, currentLocation!!.longitude)
                val goalKatec = convertWGS84ToKatec(currentPickup.latitude!!, currentPickup.longitude!!)

                val startPoi = KNPOI("현재위치", startKatec.first, startKatec.second, "현재위치")
                val goalPoi = KNPOI(
                    (currentPickup.address ?: "수거지").toString(),
                    goalKatec.first,
                    goalKatec.second,
                    (currentPickup.address ?: "수거지").toString()
                )

                // 경유지 설정 (현재 수거지 이후의 수거지들)
                val viaList = mutableListOf<KNPOI>()
                if (currentPickupIndex < selectedPickups.size - 1) {
                    for (i in (currentPickupIndex + 1) until selectedPickups.size) {
                        val viaPickup = selectedPickups[i]
                        if (viaPickup.latitude != null && viaPickup.longitude != null) {
                            val viaKatec = convertWGS84ToKatec(viaPickup.latitude!!, viaPickup.longitude!!)
                            viaList.add(KNPOI(
                                (viaPickup.address ?: "경유지 ${i+1}").toString(),
                                viaKatec.first,
                                viaKatec.second,
                                (viaPickup.address ?: "경유지 ${i+1}").toString()
                            ))
                        }
                    }
                }

                Log.d("NavigationActivity", "카텍 좌표 - 출발: (${startKatec.first}, ${startKatec.second}), 도착: (${goalKatec.first}, ${goalKatec.second})")
                Log.d("NavigationActivity", "경유지 개수: ${viaList.size}")

                RefreshApplication.knsdk.makeTripWithStart(
                    aStart = startPoi,
                    aGoal = goalPoi,
                    aVias = if (viaList.isEmpty()) null else viaList
                ) { aError, aTrip ->
                    runOnUiThread {
                        if (aError == null && aTrip != null) {
                            Log.d("NavigationActivity", "경로 요청 성공")
                            startGuide(aTrip)
                        } else {
                            val errorCode = aError?.code ?: -1
                            Log.e("NavigationActivity", "경로 요청 실패 - 코드: $errorCode")
                            Toast.makeText(this, "경로 요청 실패: $errorCode", Toast.LENGTH_LONG).show()
                            showProgress(false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("NavigationActivity", "경로 요청 중 예외 발생", e)
                runOnUiThread {
                    Toast.makeText(this, "경로 요청 중 오류: ${e.message}", Toast.LENGTH_LONG).show()
                    showProgress(false)
                }
            }
        }.start()
    }

    private fun startGuide(trip: KNTrip) {
        try {
            Log.d("NavigationActivity", "주행 안내 시작")

            RefreshApplication.knsdk.sharedGuidance()?.apply {
                // Guidance delegate 등록
                guideStateDelegate = this@NavigationActivity
                locationGuideDelegate = this@NavigationActivity
                routeGuideDelegate = this@NavigationActivity
                safetyGuideDelegate = this@NavigationActivity
                voiceGuideDelegate = this@NavigationActivity
                citsGuideDelegate = this@NavigationActivity

                // NaviView 초기화 및 주행 시작
                naviView.initWithGuidance(
                    this,
                    trip,
                    KNRoutePriority.KNRoutePriority_Recommand,
                    0
                )

                isNavigationActive = true
                showProgress(false)
                updateNavigationInfo()

                Log.d("NavigationActivity", "NaviView 초기화 완료")
                Toast.makeText(this@NavigationActivity, "내비게이션이 시작되었습니다.", Toast.LENGTH_SHORT).show()
            } ?: run {
                Log.e("NavigationActivity", "KNGuidance 객체를 가져올 수 없습니다.")
                Toast.makeText(this, "내비게이션 초기화에 실패했습니다.", Toast.LENGTH_LONG).show()
                showProgress(false)
            }
        } catch (e: Exception) {
            Log.e("NavigationActivity", "주행 시작 중 예외 발생", e)
            Toast.makeText(this, "내비게이션 시작 중 오류: ${e.message}", Toast.LENGTH_LONG).show()
            showProgress(false)
        }
    }

    /**
     * WGS84 좌표를 카텍(KATEC) 좌표로 변환합니다.
     */
    private fun convertWGS84ToKatec(lat: Double, lng: Double): Pair<Int, Int> {
        // 대한민국 중부원점 기준 (서울 기준)
        val katecX = ((lng - 126.0) * 200000.0 + 200000.0).toInt()
        val katecY = ((lat - 37.5) * 200000.0 + 500000.0).toInt()

        Log.d("NavigationActivity", "WGS84 ($lat, $lng) -> KATEC ($katecX, $katecY)")
        return Pair(katecX, katecY)
    }

    private fun updateSpeedDisplay(location: Location) {
        val speedKmh = (location.speed * 3.6).toInt() // m/s to km/h
        textCurrentSpeed.text = "${speedKmh}km/h"
    }

    private fun checkPickupProximity() {
        if (!isNavigationActive || currentPickupIndex >= selectedPickups.size || currentLocation == null) {
            return
        }

        val currentPickup = selectedPickups[currentPickupIndex]
        if (currentPickup.latitude != null && currentPickup.longitude != null) {
            val distance = calculateDistance(
                currentLocation!!.latitude,
                currentLocation!!.longitude,
                currentPickup.latitude!!,
                currentPickup.longitude!!
            ) * 1000 // km to meters

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
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    private fun showPickupCompletionButton(show: Boolean) {
        buttonCompletePickup.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun completeCurrentPickup() {
        if (currentPickupIndex >= selectedPickups.size) return

        val currentPickup = selectedPickups[currentPickupIndex]

        AlertDialog.Builder(this)
            .setTitle("수거 완료")
            .setMessage("${currentPickup.address ?: "이 수거지"}의 수거를 완료하시겠습니까?")
            .setPositiveButton("완료") { _, _ ->
                performPickupCompletion(currentPickup)
            }
            .setNegativeButton("취소", null)
            .show()
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
        currentPickupIndex++

        if (currentPickupIndex >= selectedPickups.size) {
            // 모든 수거지 완료
            showNavigationCompleteDialog()
        } else {
            // 다음 수거지로 경로 재계산
            Toast.makeText(this, "다음 수거지로 안내합니다.", Toast.LENGTH_SHORT).show()
            requestRoute()
        }
    }

    private fun showNavigationCompleteDialog() {
        AlertDialog.Builder(this)
            .setTitle("수거 완료")
            .setMessage("모든 수거지 방문이 완료되었습니다.")
            .setPositiveButton("확인") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun updateNavigationInfo() {
        if (currentPickupIndex < selectedPickups.size) {
            val currentPickup = selectedPickups[currentPickupIndex]

            textNextInstruction.text = "${currentPickup.address ?: "수거지"}로 이동 중"

            if (currentLocation != null && currentPickup.latitude != null && currentPickup.longitude != null) {
                val distance = calculateDistance(
                    currentLocation!!.latitude,
                    currentLocation!!.longitude,
                    currentPickup.latitude!!,
                    currentPickup.longitude!!
                )
                textRemainingDistance.text = "${String.format("%.1f", distance)}km"

                val estimatedTime = (distance * 2).toInt() // 시속 30km 가정
                textRemainingTime.text = "${estimatedTime}분"
            }
        }
    }

    private fun showProgress(show: Boolean) {
        progressNavigation.visibility = if (show) View.VISIBLE else View.GONE
        layoutNavigationInfo.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showExitNavigationDialog() {
        AlertDialog.Builder(this)
            .setTitle("내비게이션 종료")
            .setMessage("내비게이션을 종료하시겠습니까?")
            .setPositiveButton("종료") { _, _ ->
                finish()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // KNGuidance Delegate 메서드들
    override fun guidanceDidUpdateLocation(aGuidance: KNGuidance, aLocationGuide: KNGuide_Location) {
        naviView.guidanceDidUpdateLocation(aGuidance, aLocationGuide)
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
        Log.d("NavigationActivity", "주행 안내 종료")
        naviView.guidanceGuideEnded(aGuidance)
        isNavigationActive = false

        runOnUiThread {
            if (currentPickupIndex >= selectedPickups.size) {
                showNavigationCompleteDialog()
            }
        }
    }

    override fun guidanceGuideStarted(aGuidance: KNGuidance) {
        Log.d("NavigationActivity", "주행 안내 시작됨")
        naviView.guidanceGuideStarted(aGuidance)
        isNavigationActive = true
    }

    override fun guidanceOutOfRoute(aGuidance: KNGuidance) {
        Log.d("NavigationActivity", "경로 이탈")
        naviView.guidanceOutOfRoute(aGuidance)

        runOnUiThread {
            Toast.makeText(this, "경로를 이탈했습니다. 경로를 재계산합니다.", Toast.LENGTH_SHORT).show()
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
        Log.d("NavigationActivity", "경로 변경됨")
        naviView.guidanceRouteChanged(aGuidance)

        runOnUiThread {
            Toast.makeText(this, "경로가 변경되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun guidanceRouteUnchanged(aGuidance: KNGuidance) {
        naviView.guidanceRouteUnchanged(aGuidance)
    }

    override fun guidanceRouteUnchangedWithError(aGuidnace: KNGuidance, aError: KNError) {
        Log.e("NavigationActivity", "경로 변경 실패: ${aError.code}")
        naviView.guidanceRouteUnchangedWithError(aGuidnace, aError)

        runOnUiThread {
            Toast.makeText(this, "경로 재계산에 실패했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        showExitNavigationDialog()
    }

    override fun onDestroy() {
        super.onDestroy()

        // 위치 업데이트 정지
        fusedLocationClient.removeLocationUpdates(object : LocationCallback() {})

        // 내비게이션 정지
        if (isNavigationActive) {
            RefreshApplication.knsdk.sharedGuidance()?.let { guidance ->
                guidance.guideStateDelegate = null
                guidance.locationGuideDelegate = null
                guidance.routeGuideDelegate = null
                guidance.safetyGuideDelegate = null
                guidance.voiceGuideDelegate = null
                guidance.citsGuideDelegate = null
            }
        }
    }
}