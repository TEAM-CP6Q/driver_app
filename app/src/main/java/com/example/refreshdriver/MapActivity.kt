package com.example.refreshdriver

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.refreshdriver.models.Pickup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.*
import com.kakao.vectormap.shape.*
import kotlinx.coroutines.launch
import kotlin.math.*

class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var kakaoMap: KakaoMap? = null

    private lateinit var fabNavigation: FloatingActionButton
    private lateinit var fabList: FloatingActionButton
    private lateinit var fabCurrentLocation: FloatingActionButton
    private lateinit var buttonOptimizeRoute: Button
    private lateinit var buttonClearRoute: Button
    private lateinit var textRouteInfo: TextView
    private lateinit var progressBarRoute: ProgressBar

    private val allPickups = mutableListOf<Pickup>()
    private val selectedPickups = mutableListOf<Pickup>()
    private val pickupLabels = mutableMapOf<String, Label>()
    private var currentLocationLabel: Label? = null
    private var currentLocation: Location? = null

    // Polyline 경로선 관리
    private var routePolyline: Polyline? = null

    companion object {
        private const val MAP_ZOOM_LEVEL = 12
        private const val DETAIL_ZOOM_LEVEL = 15
        private const val TAG = "MapActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        initViews()
        setupToolbar()
        loadIntentData()
        setupClickListeners()
        initializeKakaoMap()
    }

    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        fabNavigation = findViewById(R.id.fabNavigation)
        fabList = findViewById(R.id.fabList)
        fabCurrentLocation = findViewById(R.id.fabCurrentLocation)
        buttonOptimizeRoute = findViewById(R.id.buttonOptimizeRoute)
        buttonClearRoute = findViewById(R.id.buttonClearRoute)
        textRouteInfo = findViewById(R.id.textRouteInfo)
        progressBarRoute = findViewById(R.id.progressBarRoute)
    }

    private fun setupToolbar() {
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "수거지 지도"
        }
    }

    private fun loadIntentData() {
        val pickupList = intent.getParcelableArrayListExtra<Pickup>("pickups")
        pickupList?.let {
            allPickups.addAll(it)
            Log.d(TAG, "전체 수거지 개수: ${allPickups.size}")
        }

        val selectedList = intent.getParcelableArrayListExtra<Pickup>("selectedPickups")
        selectedList?.let {
            selectedPickups.addAll(it)
            Log.d(TAG, "선택된 수거지 개수: ${selectedPickups.size}")
        }

        val currentLat = intent.getDoubleExtra("currentLatitude", 0.0)
        val currentLng = intent.getDoubleExtra("currentLongitude", 0.0)
        if (currentLat != 0.0 && currentLng != 0.0) {
            currentLocation = Location("").apply {
                latitude = currentLat
                longitude = currentLng
            }
            Log.d(TAG, "현재 위치: $currentLat, $currentLng")
        }
    }

    private fun setupClickListeners() {
        fabNavigation.setOnClickListener { startNavigation() }
        fabList.setOnClickListener { returnToList() }
        fabCurrentLocation.setOnClickListener { moveToCurrentLocation() }
        buttonOptimizeRoute.setOnClickListener { optimizeRoute() }
        buttonClearRoute.setOnClickListener { clearRoute() }
    }

    private fun initializeKakaoMap() {
        mapView.start(object : MapLifeCycleCallback() {
            override fun onMapDestroy() {
                Log.d(TAG, "지도 파괴됨")
            }
            override fun onMapError(error: Exception) {
                Log.e(TAG, "지도 오류", error)
                Toast.makeText(this@MapActivity, "지도 로딩 실패: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(map: KakaoMap) {
                Log.d(TAG, "지도 준비 완료")
                kakaoMap = map
                setupMap()
            }
        })
    }

    private fun setupMap() {
        kakaoMap?.let { map ->
            // 라벨 클릭 이벤트 설정
            map.setOnLabelClickListener { _: KakaoMap, _: LabelLayer, label: Label ->
                val pickup = label.tag as? Pickup
                pickup?.let { showPickupInfoWindow(it) }
                true
            }

            // 지도 구성 요소 표시
            displayCurrentLocationLabel()
            displayPickupLabels()
            displaySelectedRoute()
            adjustMapBounds()
            updateFabVisibility()
        }
    }

    private fun displayCurrentLocationLabel() {
        currentLocation?.let { location ->
            kakaoMap?.let { map ->
                try {
                    // 기존 현재 위치 마커 제거
                    currentLocationLabel?.let {
                        map.labelManager?.layer?.remove(it)
                    }

                    val position = LatLng.from(location.latitude, location.longitude)

                    // 현재 위치 마커 스타일 생성
                    val labelStyle = LabelStyle.from(R.drawable.ic_current_location)
                        .setZoomLevel(8)
                    val labelStyles = LabelStyles.from(labelStyle)

                    val labelOptions = LabelOptions.from(position)
                        .setStyles(labelStyles)
                        .setTexts(LabelTextBuilder().setTexts("현재 위치"))

                    currentLocationLabel = map.labelManager?.layer?.addLabel(labelOptions)
                    Log.d(TAG, "현재 위치 마커 추가됨")
                } catch (e: Exception) {
                    Log.e(TAG, "현재 위치 마커 추가 실패", e)
                }
            }
        }
    }

    private fun displayPickupLabels() {
        kakaoMap?.let { map ->
            try {
                // 기존 수거지 마커들 제거
                pickupLabels.values.forEach {
                    map.labelManager?.layer?.remove(it)
                }
                pickupLabels.clear()

                Log.d(TAG, "수거지 마커 생성 시작: ${allPickups.size}개")

                allPickups.forEachIndexed { index, pickup ->
                    val lat = pickup.latitude
                    val lng = pickup.longitude

                    if (lat != null && lng != null) {
                        val position = LatLng.from(lat, lng)

                        // 선택된 수거지인지 확인
                        val isSelected = selectedPickups.any { it.pickupId == pickup.pickupId }

                        // 완료 상태에 따른 마커 색상 결정
                        val markerResource = when {
                            pickup.isCompleted -> R.drawable.ic_marker_green // 완료된 수거지
                            isSelected -> R.drawable.ic_marker_orange // 선택된 수거지
                            else -> R.drawable.ic_marker_red // 일반 수거지
                        }

                        val labelStyle = LabelStyle.from(markerResource)
                            .setZoomLevel(8)
                        val labelStyles = LabelStyles.from(labelStyle)

                        val addressText = pickup.address?.name ?: pickup.address?.roadNameAddress ?: "수거지 ${index + 1}"

                        val labelOptions = LabelOptions.from(position)
                            .setStyles(labelStyles)
                            .setTexts(LabelTextBuilder().setTexts(addressText))
                            .setTag(pickup)

                        val label = map.labelManager?.layer?.addLabel(labelOptions)
                        label?.let {
                            pickupLabels[pickup.pickupId] = it
                            Log.d(TAG, "수거지 마커 추가: ${pickup.pickupId} at ($lat, $lng)")
                        }
                    } else {
                        Log.w(TAG, "수거지 좌표 없음: ${pickup.pickupId}")
                    }
                }

                Log.d(TAG, "수거지 마커 생성 완료: ${pickupLabels.size}개")
            } catch (e: Exception) {
                Log.e(TAG, "수거지 마커 생성 실패", e)
            }
        }
    }

    private fun displaySelectedRoute() {
        // 기존 경로선 제거
        routePolyline?.let { polyline ->
            kakaoMap?.shapeManager?.layer?.remove(polyline)
        }
        routePolyline = null

        if (selectedPickups.isEmpty() || currentLocation == null) {
            textRouteInfo.text = "경로를 선택해주세요"
            return
        }

        showRouteProgress(true)
        lifecycleScope.launch {
            try {
                val optimizedPickups = optimizePickupOrder(selectedPickups, currentLocation!!)
                calculateRouteInfo(optimizedPickups)
                drawRoutePolyline(optimizedPickups)
                showRouteProgress(false)
            } catch (e: Exception) {
                Log.e(TAG, "경로 표시 실패", e)
                showRouteProgress(false)
                Toast.makeText(this@MapActivity, "경로 표시 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun drawRoutePolyline(optimizedPickups: List<Pickup>) {
        try {
            val points = mutableListOf<LatLng>()

            // 현재 위치 추가
            currentLocation?.let {
                points.add(LatLng.from(it.latitude, it.longitude))
            }

            // 최적화된 수거지 순서대로 추가
            optimizedPickups.forEach { pickup ->
                pickup.latitude?.let { lat ->
                    pickup.longitude?.let { lng ->
                        points.add(LatLng.from(lat, lng))
                    }
                }
            }

            if (points.size >= 2) {
                kakaoMap?.let { map ->
                    val mapPoints = MapPoints.fromLatLng(points)
                    val polylineOptions = PolylineOptions.from(mapPoints, 8.0f, Color.BLUE)

                    // 기존 경로선 제거
                    routePolyline?.let { map.shapeManager?.layer?.remove(it) }
                    routePolyline = map.shapeManager?.layer?.addPolyline(polylineOptions)

                    Log.d(TAG, "경로선 그리기 완료: ${points.size}개 포인트")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "경로선 그리기 실패", e)
        }
    }

    @SuppressLint("DefaultLocale")
    private fun calculateRouteInfo(optimizedPickups: List<Pickup>) {
        if (optimizedPickups.isEmpty() || currentLocation == null) {
            textRouteInfo.text = "경로 정보 없음"
            return
        }

        var totalDistance = 0.0
        var prevLat = currentLocation!!.latitude
        var prevLng = currentLocation!!.longitude

        optimizedPickups.forEach { pickup ->
            val lat = pickup.latitude
            val lng = pickup.longitude
            if (lat != null && lng != null) {
                val distance = calculateDistance(prevLat, prevLng, lat, lng)
                totalDistance += distance
                prevLat = lat
                prevLng = lng
            }
        }

        val estimatedTime = (totalDistance * 2).toInt() // 시속 30km 가정
        textRouteInfo.text = "총 거리: ${String.format("%.1f", totalDistance)}km, 예상 시간: ${estimatedTime}분"
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

    @SuppressLint("DefaultLocale")
    private fun showPickupInfoWindow(pickup: Pickup) {
        val isSelected = selectedPickups.any { it.pickupId == pickup.pickupId }

        val dialogMessage = buildString {
            append("주소: ${pickup.address?.roadNameAddress ?: pickup.address?.name ?: "주소 없음"}\n")
            append("상태: ${if (pickup.isCompleted) "완료" else "미완료"}\n")

            if (currentLocation != null && pickup.latitude != null && pickup.longitude != null) {
                val distance = calculateDistance(
                    currentLocation!!.latitude, currentLocation!!.longitude,
                    pickup.latitude!!, pickup.longitude!!
                )
                append("거리: ${String.format("%.1f", distance)}km")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(pickup.address?.name ?: pickup.address?.roadNameAddress ?: "수거지")
            .setMessage(dialogMessage)
            .setPositiveButton("확인", null)
            .setNeutralButton(if (isSelected) "선택 해제" else "선택") { _, _ ->
                togglePickupSelection(pickup)
            }
            .show()
    }

    private fun togglePickupSelection(pickup: Pickup) {
        val isCurrentlySelected = selectedPickups.any { it.pickupId == pickup.pickupId }
        if (isCurrentlySelected) {
            selectedPickups.removeAll { it.pickupId == pickup.pickupId }
            pickup.isSelected = false
        } else {
            selectedPickups.add(pickup)
            pickup.isSelected = true
        }

        // 마커 스타일 업데이트
        displayPickupLabels()
        displaySelectedRoute()
        updateFabVisibility()

        Log.d(TAG, "수거지 선택 토글: ${pickup.pickupId}, 선택됨: ${!isCurrentlySelected}")
    }

    private fun updateFabVisibility() {
        fabNavigation.visibility = if (selectedPickups.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun optimizeRoute() {
        if (selectedPickups.isEmpty()) {
            Toast.makeText(this, "선택된 수거지가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentLocation == null) {
            Toast.makeText(this, "현재 위치를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        showRouteProgress(true)
        lifecycleScope.launch {
            try {
                val optimizedRoute = optimizePickupOrder(selectedPickups, currentLocation!!)
                selectedPickups.clear()
                selectedPickups.addAll(optimizedRoute)
                displaySelectedRoute()
                Toast.makeText(this@MapActivity, "경로가 최적화되었습니다.", Toast.LENGTH_SHORT).show()
                showRouteProgress(false)
            } catch (e: Exception) {
                Log.e(TAG, "경로 최적화 실패", e)
                showRouteProgress(false)
                Toast.makeText(this@MapActivity, "경로 최적화 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun optimizePickupOrder(pickups: List<Pickup>, startLocation: Location): List<Pickup> {
        if (pickups.size <= 1) return pickups

        val unvisited = pickups.toMutableList()
        val optimized = mutableListOf<Pickup>()
        var currentLat = startLocation.latitude
        var currentLng = startLocation.longitude

        while (unvisited.isNotEmpty()) {
            var nearestPickup: Pickup? = null
            var nearestDistance = Double.MAX_VALUE

            unvisited.forEach { pickup ->
                val lat = pickup.latitude
                val lng = pickup.longitude
                if (lat != null && lng != null) {
                    val distance = calculateDistance(currentLat, currentLng, lat, lng)
                    if (distance < nearestDistance) {
                        nearestDistance = distance
                        nearestPickup = pickup
                    }
                }
            }

            nearestPickup?.let { pickup ->
                optimized.add(pickup)
                unvisited.remove(pickup)
                currentLat = pickup.latitude!!
                currentLng = pickup.longitude!!
            }
        }
        return optimized
    }

    private fun clearRoute() {
        selectedPickups.clear()

        // 모든 수거지의 선택 상태 초기화
        allPickups.forEach { it.isSelected = false }

        routePolyline?.let { polyline ->
            kakaoMap?.shapeManager?.layer?.remove(polyline)
        }
        routePolyline = null

        displayPickupLabels() // 마커 색상 업데이트
        displaySelectedRoute()
        updateFabVisibility()

        Toast.makeText(this, "경로가 초기화되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun showRouteProgress(show: Boolean) {
        progressBarRoute.visibility = if (show) View.VISIBLE else View.GONE
        buttonOptimizeRoute.isEnabled = !show
        buttonClearRoute.isEnabled = !show
    }

    private fun moveToCurrentLocation() {
        currentLocation?.let { location ->
            kakaoMap?.let { map ->
                val cameraUpdate = CameraUpdateFactory.newCenterPosition(
                    LatLng.from(location.latitude, location.longitude),
                    DETAIL_ZOOM_LEVEL
                )
                map.moveCamera(cameraUpdate)
            }
        } ?: run {
            Toast.makeText(this, "현재 위치를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun adjustMapBounds() {
        if (allPickups.isEmpty() && currentLocation == null) return

        kakaoMap?.let { map ->
            var minLat = Double.MAX_VALUE
            var maxLat = Double.MIN_VALUE
            var minLng = Double.MAX_VALUE
            var maxLng = Double.MIN_VALUE
            var hasValidLocation = false

            // 현재 위치 포함
            currentLocation?.let { location ->
                minLat = minOf(minLat, location.latitude)
                maxLat = maxOf(maxLat, location.latitude)
                minLng = minOf(minLng, location.longitude)
                maxLng = maxOf(maxLng, location.longitude)
                hasValidLocation = true
            }

            // 수거지들 포함
            allPickups.forEach { pickup ->
                val lat = pickup.latitude
                val lng = pickup.longitude
                if (lat != null && lng != null) {
                    minLat = minOf(minLat, lat)
                    maxLat = maxOf(maxLat, lat)
                    minLng = minOf(minLng, lng)
                    maxLng = maxOf(maxLng, lng)
                    hasValidLocation = true
                }
            }

            if (hasValidLocation) {
                val centerLat = (minLat + maxLat) / 2
                val centerLng = (minLng + maxLng) / 2
                val cameraUpdate = CameraUpdateFactory.newCenterPosition(
                    LatLng.from(centerLat, centerLng),
                    MAP_ZOOM_LEVEL
                )
                map.moveCamera(cameraUpdate)
                Log.d(TAG, "지도 범위 조정 완료: center($centerLat, $centerLng)")
            }
        }
    }

    private fun startNavigation() {
        if (selectedPickups.isEmpty()) {
            Toast.makeText(this, "선택된 수거지가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, NavigationActivity::class.java)
        intent.putParcelableArrayListExtra("selectedPickups", ArrayList(selectedPickups))
        currentLocation?.let {
            intent.putExtra("currentLatitude", it.latitude)
            intent.putExtra("currentLongitude", it.longitude)
        }
        startActivity(intent)
    }

    private fun returnToList() {
        val intent = Intent()
        intent.putParcelableArrayListExtra("selectedPickups", ArrayList(selectedPickups))
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_map, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                returnToList()
                true
            }
            R.id.action_satellite -> {
                // 위성지도 토글 (카카오맵 API에서 지원하는 경우)
                Toast.makeText(this, "위성지도 기능은 준비 중입니다.", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_auto_select -> {
                autoSelectNearbyPickups()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun autoSelectNearbyPickups() {
        if (currentLocation == null) {
            Toast.makeText(this, "현재 위치를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        // 현재 선택 초기화
        selectedPickups.clear()
        allPickups.forEach { it.isSelected = false }

        // 미완료 수거지 중 가까운 5개 자동 선택
        val nearbyPickups = allPickups
            .filter { !it.isCompleted && it.latitude != null && it.longitude != null }
            .sortedBy { pickup ->
                calculateDistance(
                    currentLocation!!.latitude,
                    currentLocation!!.longitude,
                    pickup.latitude!!,
                    pickup.longitude!!
                )
            }
            .take(5)

        nearbyPickups.forEach { pickup ->
            pickup.isSelected = true
            selectedPickups.add(pickup)
        }

        displayPickupLabels()
        displaySelectedRoute()
        updateFabVisibility()

        Toast.makeText(this, "${nearbyPickups.size}개의 수거지가 자동 선택되었습니다.", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        returnToList()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        returnToList()
    }

    override fun onResume() {
        super.onResume()
        mapView.resume()
    }

    override fun onPause() {
        super.onPause()
        mapView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.finish()
    }
}