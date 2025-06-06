package com.example.refreshdriver.models

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

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

// 주소 모델 (Parcelable로 변경)
@Parcelize
data class Address(
    @SerializedName("name")
    val name: String? = null,

    @SerializedName("roadNameAddress")
    val roadNameAddress: String? = null
) : Parcelable

// 수거지 모델 (Parcelable 자동 구현)
@Parcelize
data class Pickup(
    @SerializedName("pickupId")
    val pickupId: String,

    @SerializedName("address")
    val address: Address? = null,

    @SerializedName("pickupDate")
    val pickupDate: String? = null,

    @SerializedName("isCompleted")
    var isCompleted: Boolean = false,

    // 좌표 정보 (추가)
    var latitude: Double? = null,
    var longitude: Double? = null,

    // UI 상태
    var isSelected: Boolean = false
) : Parcelable

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
@Parcelize
data class Coordinate(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val address: String
) : Parcelable

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
