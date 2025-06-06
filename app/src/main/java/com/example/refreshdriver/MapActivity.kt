package com.example.refreshdriver

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.refreshdriver.models.Pickup
import com.google.android.gms.location.LocationServices
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.*
import com.kakao.vectormap.shape.*
import kotlinx.coroutines.launch
import com.kakao.vectormap.shape.PolylineOptions
import com.kakao.vectormap.shape.MapPoints


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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        initViews()
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

    // onCreate에서 데이터 수신
    private fun loadIntentData() {
        val pickupList = intent.getParcelableArrayListExtra<Pickup>("pickups")
        pickupList?.let { allPickups.addAll(it) }

        val selectedList = intent.getParcelableArrayListExtra<Pickup>("selectedPickups")
        selectedList?.let { selectedPickups.addAll(it) }

        val currentLat = intent.getDoubleExtra("currentLatitude", 0.0)
        val currentLng = intent.getDoubleExtra("currentLongitude", 0.0)
        if (currentLat != 0.0 && currentLng != 0.0) {
            currentLocation = Location("").apply {
                latitude = currentLat
                longitude = currentLng
            }
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
            override fun onMapDestroy() {}
            override fun onMapError(error: Exception) {
                Toast.makeText(this@MapActivity, "지도 로딩 실패: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(map: KakaoMap) {
                kakaoMap = map
                setupMap()
            }
        })
    }

    private fun setupMap() {
        kakaoMap?.let { map ->
            map.setOnLabelClickListener { _: KakaoMap, _: LabelLayer, label: Label ->
                val pickup = label.tag as? Pickup
                pickup?.let { showPickupInfoWindow(it) }
                true
            }
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
                // 기존 마커 제거
                currentLocationLabel?.let {
                    map.labelManager?.layer?.remove(it)
                }

                val position = LatLng.from(location.latitude, location.longitude)
                val labelStyle = LabelStyle.from(android.R.drawable.ic_menu_mylocation)
                val labelStyles = LabelStyles.from(labelStyle)
                val labelOptions = LabelOptions.from(position)
                    .setStyles(labelStyles)
                    .setTexts(LabelTextBuilder().setTexts("현재 위치"))

                currentLocationLabel = map.labelManager?.layer?.addLabel(labelOptions)
            }
        }
    }

    private fun displayPickupLabels() {
        kakaoMap?.let { map ->
            pickupLabels.values.forEach { map.labelManager?.layer?.remove(it) }
            pickupLabels.clear()
            allPickups.forEach { pickup ->
                val lat = pickup.latitude
                val lng = pickup.longitude
                if (lat != null && lng != null) {
                    val position = LatLng.from(lat, lng)
                    val labelStyle = LabelStyle.from(android.R.drawable.presence_busy)
                    val labelStyles = LabelStyles.from(labelStyle)
                    val labelOptions = LabelOptions.from(position)
                        .setStyles(labelStyles)
                        .setTexts(LabelTextBuilder().setTexts((pickup.address ?: "수거지").toString()))
                        .setTag(pickup)
                    val label = map.labelManager?.layer?.addLabel(labelOptions)
                    label?.let { pickupLabels[pickup.pickupId] = it }
                }
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
                showRouteProgress(false)
                Toast.makeText(this@MapActivity, "경로 표시 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun drawRoutePolyline(optimizedPickups: List<Pickup>) {
        val points = mutableListOf<LatLng>()

        currentLocation?.let {
            points.add(LatLng.from(it.latitude, it.longitude))
        }

        optimizedPickups.forEach { pickup ->
            pickup.latitude?.let { lat ->
                pickup.longitude?.let { lng ->
                    points.add(LatLng.from(lat, lng))
                }
            }
        }

        if (points.size >= 2) {
            kakaoMap?.let { map ->
                // MapPoints 생성 (공식 문서 방식)
                val mapPoints = MapPoints.fromLatLng(points)

                // PolylineOptions 생성 (공식 문서 방식)
                // 굵기(Width)는 Int, 색상은 Int
                val polylineOptions = PolylineOptions.from(mapPoints, 8.0f, Color.BLUE)

                // 기존 경로선 제거
                routePolyline?.let { map.shapeManager?.layer?.remove(it) }
                routePolyline = map.shapeManager?.layer?.addPolyline(polylineOptions)
            }
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

        val estimatedTime = (totalDistance * 2).toInt()
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
            append("주소: ${pickup.address ?: "주소 없음"}\n")
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
            .setTitle((pickup.address ?: "수거지") as CharSequence)
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
        } else {
            selectedPickups.add(pickup)
        }
        updateLabelStyles()
        displaySelectedRoute()
        updateFabVisibility()
    }

    private fun updateLabelStyles() {
        displayPickupLabels() // 전체 라벨 다시 그리기
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
        routePolyline?.let { polyline ->
            kakaoMap?.shapeManager?.layer?.remove(polyline)
        }
        routePolyline = null
        displaySelectedRoute()
        updateLabelStyles()
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
        if (allPickups.isEmpty()) return

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
