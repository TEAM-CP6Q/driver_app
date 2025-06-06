package com.example.refreshdriver.models

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// Parcelable 버전의 Pickup 모델 (Intent로 전달하기 위함)
@Parcelize
data class PickupParcelable(
    val pickupId: String,
    val addressName: String?,
    val addressRoad: String?,
    val pickupDate: String?,
    var isCompleted: Boolean = false,
    var latitude: Double? = null,
    var longitude: Double? = null,
    var isSelected: Boolean = false
) : Parcelable {

    companion object {
        // Pickup을 PickupParcelable로 변환
        fun fromPickup(pickup: Pickup): PickupParcelable {
            return PickupParcelable(
                pickupId = pickup.pickupId,
                addressName = pickup.address?.name,
                addressRoad = pickup.address?.roadNameAddress,
                pickupDate = pickup.pickupDate,
                isCompleted = pickup.isCompleted,
                latitude = pickup.latitude,
                longitude = pickup.longitude,
                isSelected = pickup.isSelected
            )
        }

        // List<Pickup>을 ArrayList<PickupParcelable>로 변환
        fun fromPickupList(pickups: List<Pickup>): ArrayList<PickupParcelable> {
            return ArrayList(pickups.map { fromPickup(it) })
        }
    }

    // PickupParcelable을 Pickup으로 변환
    fun toPickup(): Pickup {
        return Pickup(
            pickupId = pickupId,
            address = Address(
                name = addressName,
                roadNameAddress = addressRoad
            ),
            pickupDate = pickupDate,
            isCompleted = isCompleted,
            latitude = latitude,
            longitude = longitude,
            isSelected = isSelected
        )
    }

    override fun describeContents(): Int {
        TODO("Not yet implemented")
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        TODO("Not yet implemented")
    }
}

// 확장 함수 추가
fun List<PickupParcelable>.toPickupList(): List<Pickup> {
    return this.map { it.toPickup() }
}

fun List<Pickup>.toParcelableList(): ArrayList<PickupParcelable> {
    return PickupParcelable.fromPickupList(this)
}