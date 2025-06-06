package com.example.refreshdriver.adapters

import android.location.Location
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.refreshdriver.R
import com.example.refreshdriver.models.Pickup
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class PickupAdapter(
    private val pickups: MutableList<Pickup>,
    private val listener: OnPickupClickListener
) : RecyclerView.Adapter<PickupAdapter.PickupViewHolder>() {

    // 현재 위치 변수 추가
    private var currentLocation: Location? = null

    interface OnPickupClickListener {
        fun onPickupClick(pickup: Pickup)
        fun onPickupSelectionChanged(pickup: Pickup, isSelected: Boolean)
        fun onCompletePickup(pickup: Pickup)
    }

    class PickupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardView: CardView = itemView.findViewById(R.id.cardView)
        val textPickupName: TextView = itemView.findViewById(R.id.textPickupName)
        val textPickupAddress: TextView = itemView.findViewById(R.id.textPickupAddress)
        val textPickupTime: TextView = itemView.findViewById(R.id.textPickupTime)
        val textPickupDistance: TextView = itemView.findViewById(R.id.textPickupDistance)
        val chipStatus: TextView = itemView.findViewById(R.id.chipStatus)
        val chipDistrict: TextView = itemView.findViewById(R.id.chipDistrict)
        val checkBoxSelection: CheckBox = itemView.findViewById(R.id.checkBoxSelection)
        val buttonComplete: Button = itemView.findViewById(R.id.buttonComplete)
        val iconPickup: ImageView = itemView.findViewById(R.id.iconPickup)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PickupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pickup, parent, false)
        return PickupViewHolder(view)
    }

    override fun onBindViewHolder(holder: PickupViewHolder, position: Int) {
        val pickup = pickups[position]
        val context = holder.itemView.context

        // 수거지 이름 설정
        val pickupName = when {
            !pickup.address?.roadNameAddress.isNullOrBlank() -> pickup.address?.roadNameAddress
            !pickup.address?.name.isNullOrBlank() -> pickup.address?.name
            pickup.latitude != null && pickup.longitude != null -> "수거지 ${position + 1} (${pickup.latitude}, ${pickup.longitude})"
            else -> "수거지 ${position + 1}"
        }
        holder.textPickupName.text = pickupName.toString()

        // 주소 정보 설정
        val addressText = getPickupAddress(pickup)
        val coordinateInfo = if (pickup.latitude != null && pickup.longitude != null) {
            "\n좌표: (${String.format("%.6f", pickup.latitude)}, ${String.format("%.6f", pickup.longitude)})"
        } else {
            "\n좌표: 없음"
        }
        holder.textPickupAddress.text = "$addressText$coordinateInfo"

        // 상태 설정
        if (pickup.isCompleted) {
            holder.chipStatus.text = "완료"
            holder.chipStatus.setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
        } else {
            holder.chipStatus.text = "미완료"
            holder.chipStatus.setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_orange_dark))
        }

        // 구 정보 설정
        holder.chipDistrict.text = extractDistrict(addressText)

        // 시간 설정
        val timeText = pickup.pickupDate?.let { dateString ->
            formatPickupDateString(dateString)
        } ?: "시간 미정"
        holder.textPickupTime.text = timeText

        // *** 거리 계산 및 표시 (핵심 수정 부분) ***
        if (pickup.latitude != null && pickup.longitude != null && currentLocation != null) {
            holder.textPickupDistance.visibility = View.VISIBLE
            val distance = calculateDistance(
                currentLocation!!.latitude, currentLocation!!.longitude,
                pickup.latitude!!, pickup.longitude!!
            )
            holder.textPickupDistance.text = "거리: ${String.format("%.1f", distance)}km"
        } else {
            holder.textPickupDistance.visibility = View.GONE
        }

        // 선택 상태 설정
        val isSelected = pickup.isSelected ?: false
        holder.checkBoxSelection.isChecked = isSelected

        // 선택된 항목 하이라이트
        if (isSelected) {
            holder.cardView.setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorSelectedBackground))
            holder.cardView.cardElevation = 8f
            holder.itemView.isSelected = true
        } else {
            holder.cardView.setCardBackgroundColor(ContextCompat.getColor(context, android.R.color.white))
            holder.cardView.cardElevation = 2f
            holder.itemView.isSelected = false
        }

        // 완료 버튼 표시/숨김
        holder.buttonComplete.visibility = if (pickup.isCompleted) View.GONE else View.VISIBLE

        // 아이콘 설정
        when {
            pickup.isCompleted -> {
                holder.iconPickup.setImageResource(R.drawable.ic_check_circle)
                holder.iconPickup.setColorFilter(ContextCompat.getColor(context, R.color.colorSuccess))
            }
            pickup.latitude != null && pickup.longitude != null -> {
                holder.iconPickup.setImageResource(R.drawable.ic_location)
                holder.iconPickup.setColorFilter(
                    if (isSelected) ContextCompat.getColor(context, R.color.colorAccent)
                    else ContextCompat.getColor(context, R.color.colorPrimary)
                )
            }
            else -> {
                holder.iconPickup.setImageResource(R.drawable.ic_location_off)
                holder.iconPickup.setColorFilter(ContextCompat.getColor(context, android.R.color.darker_gray))
            }
        }

        // 클릭 리스너 설정
        holder.cardView.setOnClickListener {
            listener.onPickupClick(pickup)
        }

        holder.cardView.setOnLongClickListener {
            val newSelectedState = !isSelected
            pickup.isSelected = newSelectedState
            listener.onPickupSelectionChanged(pickup, newSelectedState)
            notifyItemChanged(position)
            true
        }

        // 체크박스 리스너 설정
        holder.checkBoxSelection.setOnCheckedChangeListener(null)
        holder.checkBoxSelection.isChecked = isSelected

        holder.checkBoxSelection.setOnCheckedChangeListener { _, isChecked ->
            if (pickup.isSelected != isChecked) {
                pickup.isSelected = isChecked
                listener.onPickupSelectionChanged(pickup, isChecked)
                holder.itemView.post {
                    updateViewSelection(holder, isChecked)
                }
            }
        }

        holder.buttonComplete.setOnClickListener {
            listener.onCompletePickup(pickup)
        }
    }

    override fun getItemCount(): Int = pickups.size

    // *** 핵심 함수들 구현 ***

    /**
     * 현재 위치 업데이트 (핵심 함수)
     */
    fun updateCurrentLocation(location: Location?) {
        currentLocation = location
        notifyDataSetChanged() // 전체 리스트 거리 재계산
    }

    /**
     * 거리 계산 함수 (핵심 함수)
     */
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

    /**
     * 수거지 주소 가져오기
     */
    private fun getPickupAddress(pickup: Pickup): String {
        return pickup.address?.roadNameAddress
            ?: pickup.address?.name
            ?: pickup.address?.toString()
            ?: "주소 정보 없음"
    }

    private fun formatPickupDateString(dateString: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = inputFormat.parse(dateString) ?: return "시간 미정"

            val calendar = Calendar.getInstance()
            calendar.time = date

            val today = Calendar.getInstance()
            val tomorrow = Calendar.getInstance()
            tomorrow.add(Calendar.DAY_OF_YEAR, 1)

            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val timeString = timeFormat.format(date)

            when {
                isSameDay(calendar, today) -> "오늘 $timeString"
                isSameDay(calendar, tomorrow) -> "내일 $timeString"
                else -> {
                    val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                    dateFormat.format(date)
                }
            }
        } catch (e: Exception) {
            "시간 미정"
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun extractDistrict(address: String?): String {
        if (address.isNullOrBlank()) return "기타"

        val districtRegex = "\\s([가-힣]{1,3}구)(?:\\s|$)".toRegex()
        val matchResult = districtRegex.find(address)
        return matchResult?.groupValues?.get(1) ?: run {
            val cityRegex = "([가-힣]{2,4}시|[가-힣]{2,4}도)".toRegex()
            val cityMatch = cityRegex.find(address)
            cityMatch?.groupValues?.get(1) ?: "기타"
        }
    }

    /**
     * 거리 정보를 업데이트합니다
     */
    fun updateDistances(distances: Map<String, Double>) {
        distances.forEach { (pickupId, distance) ->
            val index = pickups.indexOfFirst { it.pickupId == pickupId }
            if (index != -1) {
                notifyItemChanged(index)
            }
        }
    }

    /**
     * 특정 수거지의 거리를 업데이트합니다
     */
    fun updatePickupDistance(pickupId: String, distance: Double) {
        val index = pickups.indexOfFirst { it.pickupId == pickupId }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    /**
     * 실제 데이터로 완전히 새로 설정합니다
     */
    fun updateData(newPickups: List<Pickup>) {
        pickups.clear()
        pickups.addAll(newPickups)
        notifyDataSetChanged()
    }

    /**
     * 좌표가 있는 수거지만 필터링하여 반환
     */
    fun getPickupsWithCoordinates(): List<Pickup> {
        return pickups.filter { it.latitude != null && it.longitude != null }
    }

    /**
     * 좌표가 없는 수거지만 필터링하여 반환
     */
    fun getPickupsWithoutCoordinates(): List<Pickup> {
        return pickups.filter { it.latitude == null || it.longitude == null }
    }

    /**
     * 모든 선택을 해제합니다
     */
    fun clearSelection() {
        pickups.forEach { it.isSelected = false }
        notifyDataSetChanged()
    }

    /**
     * 선택된 수거지 목록을 반환합니다
     */
    fun getSelectedPickups(): List<Pickup> {
        return pickups.filter { it.isSelected == true }
    }

    /**
     * 특정 수거지의 선택 상태를 변경합니다
     */
    fun toggleSelection(pickupId: String) {
        val index = pickups.indexOfFirst { it.pickupId == pickupId }
        if (index != -1) {
            pickups[index].isSelected = !(pickups[index].isSelected ?: false)
            notifyItemChanged(index)
        }
    }

    /**
     * 뷰의 선택 상태를 직접 업데이트
     */
    private fun updateViewSelection(holder: PickupViewHolder, isSelected: Boolean) {
        val context = holder.itemView.context

        if (isSelected) {
            holder.cardView.setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorSelectedBackground))
            holder.cardView.cardElevation = 8f
            holder.itemView.isSelected = true
            holder.iconPickup.setColorFilter(ContextCompat.getColor(context, R.color.colorAccent))
        } else {
            holder.cardView.setCardBackgroundColor(ContextCompat.getColor(context, android.R.color.white))
            holder.cardView.cardElevation = 2f
            holder.itemView.isSelected = false
            holder.iconPickup.setColorFilter(ContextCompat.getColor(context, R.color.colorPrimary))
        }
    }

    /**
     * 완료된 수거지 개수 반환
     */
    fun getCompletedCount(): Int {
        return pickups.count { it.isCompleted }
    }

    /**
     * 전체 수거지 개수 반환
     */
    fun getTotalCount(): Int {
        return pickups.size
    }
}
