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
import com.google.android.gms.location.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.label.LabelLayer
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.label.LabelTextBuilder
import kotlinx.coroutines.launch
import android.content.DialogInterface

import kotlin.math.*


class MapActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var kakaoMap: KakaoMap? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient

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

    companion object {
        private const val MAP_ZOOM_LEVEL = 12
        private const val DETAIL_ZOOM_LEVEL = 15
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        initViews()
        setupToolbar()
        setupClickListeners()
        loadIntentData()
        setupLocationServices()
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

    private fun setupClickListeners() {
        fabNavigation.setOnClickListener {
            if (selectedPickups.isNotEmpty()) {
                startNavigation()
            } else {
                Toast.makeText(this, "내비게이션을 시작하려면 최소 1개의 수거지를 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        fabList.setOnClickListener {
            returnToList()
        }

        fabCurrentLocation.setOnClickListener {
            moveToCurrentLocation()
        }

        buttonOptimizeRoute.setOnClickListener {
            optimizeRoute()
        }

        buttonClearRoute.setOnClickListener {
            clearRoute()
        }
    }

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

    private fun setupLocationServices() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    private fun initializeKakaoMap() {
        mapView.start(object : MapLifeCycleCallback() {
            override fun onMapDestroy() {
                // 지도가 파괴될 때
            }
            override fun onMapError(error: Exception) {
                Toast.makeText(this@MapActivity, "지도 로딩 실패: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }, object : KakaoMapReadyCallback() {
            override fun onMapReady(map: KakaoMap) {
                kakaoMap = map
                setupMapFeatures()
            }
        })
    }

    private fun setupMapFeatures() {
        kakaoMap?.let { map ->
            map.setOnLabelClickListener { _: KakaoMap, _: LabelLayer, label: Label ->
                val pickup = label.tag as? Pickup
                pickup?.let {
                    showPickupInfoWindow(it)
                }
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
                val position = LatLng.from(location.latitude, location.longitude)
                val labelStyle = LabelStyle.from(android.R.drawable.ic_menu_mylocation)
                val labelStyles = LabelStyles.from(labelStyle)
                val labelOptions = LabelOptions.from(position)
                    .setStyles(labelStyles)
                    .setTexts(LabelTextBuilder().setTexts("현재 위치"))  // 생성자 + setTexts() 사용
                currentLocationLabel = map.labelManager?.layer?.addLabel(labelOptions)
            }
        }
    }

    private fun displayPickupLabels() {
        kakaoMap?.let { map ->
            pickupLabels.clear()
            allPickups.forEach { pickup ->
                if (pickup.latitude != null && pickup.longitude != null) {
                    val position = LatLng.from(pickup.latitude!!, pickup.longitude!!)
                    val isSelected = selectedPickups.any { it.pickupId == pickup.pickupId }
                    val isCompleted = pickup.isCompleted
                    val markerRes = when {
                        isCompleted -> android.R.drawable.presence_online
                        isSelected -> android.R.drawable.presence_away
                        else -> android.R.drawable.presence_busy
                    }
                    val labelStyle = LabelStyle.from(markerRes)
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
        if (selectedPickups.isEmpty()) {
            clearRouteDisplay()
            return
        }

        showRouteProgress(true)

        lifecycleScope.launch {
            try {
                val optimizedPickups = if (currentLocation != null) {
                    optimizePickupOrder(selectedPickups, currentLocation!!)
                } else {
                    selectedPickups
                }

                calculateRouteInfo(optimizedPickups)
                showRouteProgress(false)
            } catch (e: Exception) {
                showRouteProgress(false)
                Toast.makeText(this@MapActivity, "경로 표시 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun calculateRouteInfo(optimizedPickups: List<Pickup>) {
        if (optimizedPickups.isEmpty() || currentLocation == null) {
            textRouteInfo.text = "경로 정보 없음"
            return
        }

        var totalDistance = 0.0
        var previousLat = currentLocation!!.latitude
        var previousLng = currentLocation!!.longitude

        optimizedPickups.forEach { pickup ->
            if (pickup.latitude != null && pickup.longitude != null) {
                val distance = calculateDistance(
                    previousLat, previousLng,
                    pickup.latitude!!, pickup.longitude!!
                )
                totalDistance += distance
                previousLat = pickup.latitude!!
                previousLng = pickup.longitude!!
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

        AlertDialog.Builder(this)
            .setTitle((pickup.address ?: "수거지").toString())  // 명시적 toString() 추가
            .setMessage("""
                주소: ${pickup.address ?: "주소 없음"}
                상태: ${if (pickup.isCompleted) "완료" else "미완료"}
                ${if (currentLocation != null && pickup.latitude != null && pickup.longitude != null) {
                "거리: ${String.format("%.1f", calculateDistance(
                    currentLocation!!.latitude, currentLocation!!.longitude,
                    pickup.latitude!!, pickup.longitude!!
                ))}km"
            } else ""}
            """.trimIndent())
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
        kakaoMap?.let { map ->
            allPickups.forEach { pickup ->
                val label = pickupLabels[pickup.pickupId]
                if (label != null && pickup.latitude != null && pickup.longitude != null) {
                    val isSelected = selectedPickups.any { it.pickupId == pickup.pickupId }
                    val isCompleted = pickup.isCompleted

                    val markerRes = when {
                        isCompleted -> android.R.drawable.presence_online
                        isSelected -> android.R.drawable.presence_away
                        else -> android.R.drawable.presence_busy
                    }

                    map.labelManager?.layer?.remove(label)

                    val position = LatLng.from(pickup.latitude!!, pickup.longitude!!)
                    val labelStyle = LabelStyle.from(markerRes)
                    val labelStyles = LabelStyles.from(labelStyle)

                    val labelOptions = LabelOptions.from(position)
                        .setStyles(labelStyles)
                        .setTexts(LabelTextBuilder().setTexts((pickup.address ?: "수거지").toString()))
                        .setTag(pickup)

                    val newLabel = map.labelManager?.layer?.addLabel(labelOptions)
                    newLabel?.let { pickupLabels[pickup.pickupId] = it }
                }
            }
        }
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
                if (pickup.latitude != null && pickup.longitude != null) {
                    val distance = calculateDistance(
                        currentLat, currentLng,
                        pickup.latitude!!, pickup.longitude!!
                    )
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
        clearRouteDisplay()
        updateLabelStyles()
        updateFabVisibility()
        Toast.makeText(this, "경로가 초기화되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun clearRouteDisplay() {
        textRouteInfo.text = "경로를 선택해주세요"
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

            currentLocation?.let { location ->
                minLat = minOf(minLat, location.latitude)
                maxLat = maxOf(maxLat, location.latitude)
                minLng = minOf(minLng, location.longitude)
                maxLng = maxOf(maxLng, location.longitude)
                hasValidLocation = true
            }

            allPickups.forEach { pickup ->
                if (pickup.latitude != null && pickup.longitude != null) {
                    minLat = minOf(minLat, pickup.latitude!!)
                    maxLat = maxOf(maxLat, pickup.latitude!!)
                    minLng = minOf(minLng, pickup.longitude!!)
                    maxLng = maxOf(maxLng, pickup.longitude!!)
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
