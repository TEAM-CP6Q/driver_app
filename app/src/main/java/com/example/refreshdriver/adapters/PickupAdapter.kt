package com.example.refreshdriver.adapters

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

class PickupAdapter(
    private val pickups: MutableList<Pickup>,
    private val listener: OnPickupClickListener
) : RecyclerView.Adapter<PickupAdapter.PickupViewHolder>() {

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

        // 기본 정보 설정
        holder.textPickupName.text = (pickup.address ?: "수거지 ${position + 1}").toString()
        holder.textPickupAddress.text = (pickup.address ?: "").toString()

        // 상태 설정
        holder.chipStatus.text = if (pickup.isCompleted) "완료" else "미완료"

        // 구 정보 설정
        holder.chipDistrict.text = extractDistrict(pickup.address.toString())

        // 스케줄 시간 처리
        val timeText = pickup.pickupDate?.let { dateString ->
            formatPickupDateString(dateString)
        } ?: "시간 미정"
        holder.textPickupTime.text = timeText

        // 거리 정보 (좌표가 있는 경우만)
        if (pickup.latitude != null && pickup.longitude != null) {
            holder.textPickupDistance.text = "거리: 계산중..."
        } else {
            holder.textPickupDistance.text = ""
        }

        // 선택 상태 설정
        val isSelected = pickup.isSelected ?: false
        holder.checkBoxSelection.isChecked = isSelected

        // 선택된 항목 하이라이트
        if (isSelected) {
            holder.cardView.setCardBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_blue_light))
        } else {
            holder.cardView.setCardBackgroundColor(ContextCompat.getColor(context, android.R.color.white))
        }

        // 완료 버튼 표시/숨김
        holder.buttonComplete.visibility = if (pickup.isCompleted) View.GONE else View.VISIBLE

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

        holder.checkBoxSelection.setOnCheckedChangeListener { _, isChecked ->
            pickup.isSelected = isChecked
            listener.onPickupSelectionChanged(pickup, isChecked)
        }

        holder.buttonComplete.setOnClickListener {
            listener.onCompletePickup(pickup)
        }
    }

    override fun getItemCount(): Int = pickups.size

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
        if (address == null) return "기타"

        val districtRegex = "\\s([가-힣]{1,3}구)(?:\\s|$)".toRegex()
        val matchResult = districtRegex.find(address)
        return matchResult?.groupValues?.get(1) ?: "기타"
    }

    fun updateDistances(distances: Map<String, Double>) {
        pickups.forEachIndexed { index, pickup ->
            distances[pickup.pickupId]?.let { distance ->
                val formattedDistance = String.format("%.1fkm", distance)
                // 뷰홀더를 통해 직접 업데이트
                notifyItemChanged(index)
            }
        }
    }

    fun updateData(newPickups: List<Pickup>) {
        pickups.clear()
        pickups.addAll(newPickups)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        pickups.forEach { it.isSelected = false }
        notifyDataSetChanged()
    }

    fun getSelectedPickups(): List<Pickup> {
        return pickups.filter { it.isSelected == true }
    }
}