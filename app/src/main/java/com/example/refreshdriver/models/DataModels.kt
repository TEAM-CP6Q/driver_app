package com.example.refreshdriver.models

import android.os.Parcel
import android.os.Parcelable
import com.google.gson.annotations.SerializedName

// 로그인 요청 모델
data class LoginRequest(
    val email: String,
    val password: String
)

// 로그인 응답 모델
data class LoginResponse(
    val token: String,
    val user: User
)

// 사용자 모델
data class User(
    val id: Int,
    val email: String,
    val role: String
)

// 수거지 모델
data class Pickup(
    @SerializedName("pickupId")
    val pickupId: String,

    @SerializedName("address")
    val address: Address?,

    @SerializedName("pickupDate")
    val pickupDate: String?,

    @SerializedName("isCompleted")
    var isCompleted: Boolean = false,

    // 좌표 정보 (추가)
    var latitude: Double? = null,
    var longitude: Double? = null,

    // UI 상태
    var isSelected: Boolean = false
) : Parcelable {
    override fun describeContents(): Int {
        TODO("Not yet implemented")
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        TODO("Not yet implemented")
    }
}

// 주소 모델
data class Address(
    @SerializedName("name")
    val name: String?,

    @SerializedName("roadNameAddress")
    val roadNameAddress: String?
)

// 수거지 상세 정보 모델
data class PickupDetails(
    val details: List<WasteDetail>?,
    val pricePreview: Int?,
    val payment: Boolean?,
    val phone: String?,
    val email: String?
)

// 폐기물 상세 정보 모델
data class WasteDetail(
    @SerializedName("wasteId")
    val wasteId: String,

    @SerializedName("wasteName")
    val wasteName: String,

    @SerializedName("weight")
    val weight: String,

    @SerializedName("pricePreview")
    val pricePreview: Int?
)

// 좌표 모델
data class Coordinate(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val address: String
)

// 카카오 지오코딩 응답 모델
data class KakaoGeocodingResponse(
    val documents: List<KakaoAddress>
)

data class KakaoAddress(
    val x: String, // 경도
    val y: String  // 위도
)

// 수거 완료 요청 모델
data class CompletePickupRequest(
    val pickupId: String
)

// API 기본 응답 모델
data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val message: String?
)