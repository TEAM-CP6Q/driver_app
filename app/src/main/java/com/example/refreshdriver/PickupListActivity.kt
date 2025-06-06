package com.example.refreshdriver

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.refreshdriver.adapters.PickupAdapter
import com.example.refreshdriver.models.Pickup
import com.example.refreshdriver.network.NetworkResult
import com.example.refreshdriver.network.PickupRepository
import com.google.android.gms.location.*
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class PickupListActivity : AppCompatActivity(), PickupAdapter.OnPickupClickListener {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var filterChipGroup: ChipGroup
    private lateinit var sortSpinner: Spinner
    private lateinit var fabMap: FloatingActionButton
    private lateinit var fabNavigation: FloatingActionButton
    private lateinit var selectedCountText: TextView
    private lateinit var bottomActionLayout: LinearLayout

    private lateinit var pickupAdapter: PickupAdapter
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val repository = PickupRepository()

    private val allPickups = mutableListOf<Pickup>()
    private val filteredPickups = mutableListOf<Pickup>()
    private var currentLocation: Location? = null

    private var currentFilter = "all"
    private var currentSort = "distance"

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val MAP_ACTIVITY_REQUEST_CODE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pickup_list)

        initViews()
        setupToolbar()
        setupRecyclerView()
        setupSwipeRefresh()
        setupFiltersAndSort()
        setupLocationServices()
        setupClickListeners()

        checkLocationPermissions()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerViewPickups)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        progressBar = findViewById(R.id.progressBar)
        emptyStateLayout = findViewById(R.id.emptyStateLayout)
        filterChipGroup = findViewById(R.id.filterChipGroup)
        sortSpinner = findViewById(R.id.sortSpinner)
        fabMap = findViewById(R.id.fabMap)
        fabNavigation = findViewById(R.id.fabNavigation)
        selectedCountText = findViewById(R.id.selectedCountText)
        bottomActionLayout = findViewById(R.id.bottomActionLayout)

        sharedPreferences = getSharedPreferences("RefreshDriver", MODE_PRIVATE)
    }

    private fun setupToolbar() {
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "수거지 목록"
        }
    }

    private fun setupRecyclerView() {
        pickupAdapter = PickupAdapter(filteredPickups, this)

        // 현재 위치가 있으면 어댑터에 전달
        currentLocation?.let { location ->
            pickupAdapter.updateCurrentLocation(location)
        }

        recyclerView.apply {
            adapter = pickupAdapter
            layoutManager = LinearLayoutManager(this@PickupListActivity)
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            loadPickups()
        }
        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light
        )
    }

    private fun setupFiltersAndSort() {
        val filterAll = findViewById<Chip>(R.id.chipAll)
        val filterIncomplete = findViewById<Chip>(R.id.chipIncomplete)
        val filterComplete = findViewById<Chip>(R.id.chipComplete)

        filterAll.setOnClickListener { applyFilter("all") }
        filterIncomplete.setOnClickListener { applyFilter("incomplete") }
        filterComplete.setOnClickListener { applyFilter("complete") }

        val sortOptions = arrayOf("거리순", "시간순")
        val sortAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sortOptions)
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sortSpinner.adapter = sortAdapter

        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentSort = if (position == 0) "distance" else "time"
                applyFilterAndSort()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupLocationServices() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    private fun setupClickListeners() {
        fabMap.setOnClickListener {
            val selectedPickups = pickupAdapter.getSelectedPickups()
            val intent = Intent(this, MapActivity::class.java).apply {
                putParcelableArrayListExtra("selectedPickups", ArrayList(selectedPickups))
                putParcelableArrayListExtra("pickups", ArrayList(allPickups))
                putExtra("currentLatitude", currentLocation?.latitude ?: 0.0)
                putExtra("currentLongitude", currentLocation?.longitude ?: 0.0)
            }
            startActivityForResult(intent, MAP_ACTIVITY_REQUEST_CODE)
        }

        fabNavigation.setOnClickListener {
            val selectedPickups = pickupAdapter.getSelectedPickups()
            if (selectedPickups.isNotEmpty()) {
                startNavigation(selectedPickups)
            } else {
                Toast.makeText(this, "내비게이션을 시작하려면 최소 1개의 수거지를 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.buttonAutoSelect).setOnClickListener {
            autoSelectOptimalRoute()
        }

        findViewById<Button>(R.id.buttonClearSelection).setOnClickListener {
            clearSelection()
        }
    }

    private fun checkLocationPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                getCurrentLocation()
                loadPickups()
            }
            else -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            currentLocation = location

            // 어댑터에 현재 위치 전달
            if (::pickupAdapter.isInitialized) {
                pickupAdapter.updateCurrentLocation(location)
            }

            if (location == null) {
                requestLocationUpdate()
            } else {
                applyFilterAndSort()
            }
        }
    }

    private fun requestLocationUpdate() {
        val locationRequest = LocationRequest.create().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLocation = location

                    // 어댑터에 현재 위치 전달
                    if (::pickupAdapter.isInitialized) {
                        pickupAdapter.updateCurrentLocation(location)
                    }

                    fusedLocationClient.removeLocationUpdates(this)
                    applyFilterAndSort()
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

    private fun loadPickups() {
        showLoading(true)

        lifecycleScope.launch {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            when (val result = repository.getTodayPickups(today)) {
                is NetworkResult.Success -> {
                    allPickups.clear()
                    allPickups.addAll(result.data)

                    // 좌표 정보가 없는 수거지들에 대해 지오코딩 수행
                    geocodePickupsWithoutCoordinates()

                    applyFilterAndSort()
                    showLoading(false)
                }
                is NetworkResult.Error -> {
                    showError(result.message)
                    showLoading(false)
                }
                is NetworkResult.Loading -> {
                    // 로딩 중
                }
            }
        }
    }

    private fun geocodePickupsWithoutCoordinates() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pickupsToGeocode = withContext(Dispatchers.Main) {
                    allPickups.toList().filter { pickup ->
                        pickup.latitude == null || pickup.longitude == null
                    }
                }

                pickupsToGeocode.forEachIndexed { index, pickup ->
                    val address = pickup.address?.roadNameAddress ?: pickup.address?.name
                    if (!address.isNullOrBlank()) {
                        try {
                            when (val result = repository.geocodeAddress(address)) {
                                is NetworkResult.Success -> {
                                    pickup.latitude = result.data.latitude
                                    pickup.longitude = result.data.longitude
                                }
                                is NetworkResult.Error -> {
                                    pickup.latitude = 37.5666805
                                    pickup.longitude = 126.9784147
                                }
                                else -> {}
                            }
                        } catch (e: Exception) {
                            pickup.latitude = 37.5666805
                            pickup.longitude = 126.9784147
                        }

                        if (index < pickupsToGeocode.size - 1) {
                            kotlinx.coroutines.delay(200)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    // 지오코딩 완료 후 어댑터에 현재 위치 업데이트
                    if (::pickupAdapter.isInitialized) {
                        pickupAdapter.updateCurrentLocation(currentLocation)
                    }
                    applyFilterAndSort()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("일부 수거지 위치 정보를 가져올 수 없습니다.")
                    if (::pickupAdapter.isInitialized) {
                        pickupAdapter.updateCurrentLocation(currentLocation)
                    }
                    applyFilterAndSort()
                }
            }
        }
    }

    private fun applyFilter(filter: String) {
        currentFilter = filter

        for (i in 0 until filterChipGroup.childCount) {
            val chip = filterChipGroup.getChildAt(i) as Chip
            chip.isChecked = false
        }

        when (filter) {
            "all" -> findViewById<Chip>(R.id.chipAll).isChecked = true
            "incomplete" -> findViewById<Chip>(R.id.chipIncomplete).isChecked = true
            "complete" -> findViewById<Chip>(R.id.chipComplete).isChecked = true
        }

        applyFilterAndSort()
    }

    private fun applyFilterAndSort() {
        filteredPickups.clear()

        val filtered = when (currentFilter) {
            "incomplete" -> allPickups.filter { !it.isCompleted }
            "complete" -> allPickups.filter { it.isCompleted }
            else -> allPickups
        }

        val sorted = when (currentSort) {
            "distance" -> {
                if (currentLocation != null) {
                    filtered.sortedBy { pickup ->
                        val lat = pickup.latitude ?: 0.0
                        val lng = pickup.longitude ?: 0.0
                        calculateDistance(
                            currentLocation!!.latitude,
                            currentLocation!!.longitude,
                            lat,
                            lng
                        )
                    }
                } else {
                    filtered
                }
            }
            "time" -> {
                filtered.sortedBy { pickup ->
                    pickup.pickupDate?.let { dateString ->
                        try {
                            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                            format.parse(dateString)?.time ?: 0L
                        } catch (e: Exception) {
                            0L
                        }
                    } ?: 0L
                }
            }
            else -> filtered
        }

        filteredPickups.addAll(sorted)
        pickupAdapter.notifyDataSetChanged()
        updateEmptyState()
        updateSelectedCount()
    }

    private fun startNavigation(selectedPickups: List<Pickup>) {
        if (selectedPickups.isEmpty()) {
            Toast.makeText(this, "선택된 수거지가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, NavigationActivity::class.java)
        intent.putParcelableArrayListExtra("selectedPickups", ArrayList(selectedPickups))

        currentLocation?.let { location ->
            intent.putExtra("currentLatitude", location.latitude)
            intent.putExtra("currentLongitude", location.longitude)
        }

        startActivity(intent)
    }

    private fun autoSelectOptimalRoute() {
        if (currentLocation == null) {
            Toast.makeText(this, "현재 위치를 가져올 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        clearSelection()

        val incompletePickups = allPickups.filter { !it.isCompleted }
        if (incompletePickups.isEmpty()) {
            Toast.makeText(this, "완료되지 않은 수거지가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val optimalPickups = incompletePickups
            .filter { it.latitude != null && it.longitude != null }
            .sortedBy { pickup ->
                calculateDistance(
                    currentLocation!!.latitude,
                    currentLocation!!.longitude,
                    pickup.latitude!!,
                    pickup.longitude!!
                )
            }
            .take(5)

        optimalPickups.forEach { pickup ->
            pickup.isSelected = true
        }

        pickupAdapter.notifyDataSetChanged()
        updateSelectedCount()

        Toast.makeText(this, "${optimalPickups.size}개의 수거지가 자동 선택되었습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun clearSelection() {
        pickupAdapter.clearSelection()
        updateSelectedCount()
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

    private fun updateSelectedCount() {
        val selectedCount = pickupAdapter.getSelectedPickups().size
        selectedCountText.text = "${selectedCount}개 선택됨"
        bottomActionLayout.visibility = if (selectedCount > 0) View.VISIBLE else View.GONE

        // 내비게이션 FAB 표시/숨김
        fabNavigation.visibility = if (selectedCount > 0) View.VISIBLE else View.GONE
    }

    private fun updateEmptyState() {
        if (filteredPickups.isEmpty()) {
            emptyStateLayout.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyStateLayout.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun showLoading(isLoading: Boolean) {
        if (isLoading) {
            progressBar.visibility = View.VISIBLE
            swipeRefreshLayout.isRefreshing = false
        } else {
            progressBar.visibility = View.GONE
            swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onPickupClick(pickup: Pickup) {
        showPickupDetailDialog(pickup)
    }

    override fun onPickupSelectionChanged(pickup: Pickup, isSelected: Boolean) {
        updateSelectedCount()
    }

    override fun onCompletePickup(pickup: Pickup) {
        lifecycleScope.launch {
            when (val result = repository.completePickup(pickup.pickupId)) {
                is NetworkResult.Success -> {
                    pickup.isCompleted = true
                    applyFilterAndSort()
                    Toast.makeText(this@PickupListActivity, "수거가 완료되었습니다.", Toast.LENGTH_SHORT).show()
                }
                is NetworkResult.Error -> {
                    Toast.makeText(this@PickupListActivity, "수거 완료 처리에 실패했습니다: ${result.message}", Toast.LENGTH_SHORT).show()
                }
                is NetworkResult.Loading -> {
                    // 로딩 상태
                }
            }
        }
    }

    private fun showPickupDetailDialog(pickup: Pickup) {
        val message = buildString {
            append("주소: ${pickup.address?.roadNameAddress ?: pickup.address?.name ?: "주소 정보 없음"}\n")
            append("상태: ${if (pickup.isCompleted) "완료" else "미완료"}\n")

            pickup.pickupDate?.let { dateString ->
                try {
                    val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    val date = inputFormat.parse(dateString)
                    if (date != null) {
                        val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        append("예정 시간: ${outputFormat.format(date)}\n")
                    } else {

                    }
                } catch (e: Exception) {
                    append("예정 시간: 시간 정보 오류\n")
                }
            } ?: append("예정 시간: 시간 미정\n")

            if (currentLocation != null && pickup.latitude != null && pickup.longitude != null) {
                val distance = calculateDistance(
                    currentLocation!!.latitude,
                    currentLocation!!.longitude,
                    pickup.latitude!!,
                    pickup.longitude!!
                )
                append("거리: ${String.format("%.1f", distance)}km")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("수거지 정보")
            .setMessage(message)
            .setPositiveButton("선택/해제") { _, _ ->
                val newState = !(pickup.isSelected ?: false)
                pickup.isSelected = newState
                updateSelectedCount()
                pickupAdapter.notifyDataSetChanged()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    getCurrentLocation()
                    loadPickups()
                } else {
                    Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
                    loadPickups()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == MAP_ACTIVITY_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.getParcelableArrayListExtra<Pickup>("selectedPickups")?.let { updatedSelection ->
                allPickups.forEach { pickup ->
                    pickup.isSelected = updatedSelection.any { it.pickupId == pickup.pickupId }
                }
                applyFilterAndSort()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_pickup_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_refresh -> {
                loadPickups()
                true
            }
            R.id.action_logout -> {
                showLogoutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("로그아웃")
            .setMessage("로그아웃 하시겠습니까?")
            .setPositiveButton("로그아웃") { _, _ ->
                with(sharedPreferences.edit()) {
                    clear()
                    apply()
                }
                finish()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadPickups()
    }
}
