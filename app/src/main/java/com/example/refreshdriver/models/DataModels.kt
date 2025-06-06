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
    val roadNameAddress: String? = null,

    // 추가 주소 필드들
    @SerializedName("address")
    val address: String? = null,

    @SerializedName("detailAddress")
    val detailAddress: String? = null
) : Parcelable {

    // toString 메서드로 주소 표시 우선순위 설정
    override fun toString(): String {
        return roadNameAddress ?: name ?: address ?: "주소 정보 없음"
    }
}

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

    // 좌표 정보 (API 응답에서 직접 받을 수 있도록 추가)
    @SerializedName("latitude")
    var latitude: Double? = null,

    @SerializedName("longitude")
    var longitude: Double? = null,

    // 좌표 정보를 다른 필드명으로 받는 경우도 고려
    @SerializedName("lat")
    var lat: Double? = null,

    @SerializedName("lng")
    var lng: Double? = null,

    @SerializedName("x")
    var x: Double? = null,

    @SerializedName("y")
    var y: Double? = null,

    // UI 상태
    var isSelected: Boolean = false,

    // 추가 정보
    @SerializedName("phone")
    val phone: String? = null,

    @SerializedName("email")
    val email: String? = null,

    @SerializedName("wasteTypes")
    val wasteTypes: List<String>? = null,

    @SerializedName("estimatedWeight")
    val estimatedWeight: String? = null,

    @SerializedName("specialInstructions")
    val specialInstructions: String? = null

) : Parcelable {

    // 초기화 블록에서 좌표 정보 정규화
    init {
        // 다양한 좌표 필드명을 latitude, longitude로 통일
        if (latitude == null && lat != null) {
            latitude = lat
        }
        if (longitude == null && lng != null) {
            longitude = lng
        }
        if (latitude == null && y != null) {
            latitude = y
        }
        if (longitude == null && x != null) {
            longitude = x
        }
    }

    // 주소 텍스트 반환
    fun getAddressText(): String {
        return address?.toString() ?: "주소 정보 없음"
    }

    // 수거지 이름 반환
    fun getDisplayName(): String {
        return address?.name ?: address?.roadNameAddress ?: "수거지"
    }

    // 좌표 유효성 검사
    fun hasValidCoordinates(): Boolean {
        return latitude != null && longitude != null &&
                latitude != 0.0 && longitude != 0.0
    }

    // 거리 계산용 좌표 반환
    fun getCoordinates(): Pair<Double, Double>? {
        return if (hasValidCoordinates()) {
            Pair(latitude!!, longitude!!)
        } else null
    }
}

// 수거지 상세 정보 모델
data class PickupDetails(
    @SerializedName("details")
    val details: List<WasteDetail>?,

    @SerializedName("pricePreview")
    val pricePreview: Int?,

    @SerializedName("payment")
    val payment: Boolean?,

    @SerializedName("phone")
    val phone: String?,

    @SerializedName("email")
    val email: String?,

    @SerializedName("specialInstructions")
    val specialInstructions: String?,

    @SerializedName("accessInstructions")
    val accessInstructions: String?
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
    val pricePreview: Int?,

    @SerializedName("category")
    val category: String? = null,

    @SerializedName("description")
    val description: String? = null
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
    @SerializedName("documents")
    val documents: List<KakaoAddress>
)

data class KakaoAddress(
    @SerializedName("x")
    val x: String, // 경도

    @SerializedName("y")
    val y: String, // 위도

    @SerializedName("address_name")
    val addressName: String? = null,

    @SerializedName("road_address")
    val roadAddress: KakaoRoadAddress? = null
)

data class KakaoRoadAddress(
    @SerializedName("address_name")
    val addressName: String,

    @SerializedName("region_1depth_name")
    val region1depthName: String,

    @SerializedName("region_2depth_name")
    val region2depthName: String,

    @SerializedName("region_3depth_name")
    val region3depthName: String
)

// 수거 완료 요청 모델
data class CompletePickupRequest(
    @SerializedName("pickupId")
    val pickupId: String,

    @SerializedName("completedAt")
    val completedAt: String? = null,

    @SerializedName("notes")
    val notes: String? = null
)

// API 기본 응답 모델
data class ApiResponse<T>(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("data")
    val data: T?,

    @SerializedName("message")
    val message: String?,

    @SerializedName("error")
    val error: String? = null,

    @SerializedName("code")
    val code: Int? = null
)

// 수거지 목록 응답 모델 (API 응답이 리스트가 아닐 경우)
data class PickupListResponse(
    @SerializedName("pickups")
    val pickups: List<Pickup>,

    @SerializedName("total")
    val total: Int? = null,

    @SerializedName("page")
    val page: Int? = null,

    @SerializedName("limit")
    val limit: Int? = null
)

// 위치 정보 모델
@Parcelize
data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
) : Parcelable

// 경로 최적화 요청 모델
data class RouteOptimizationRequest(
    @SerializedName("startLocation")
    val startLocation: LocationInfo,

    @SerializedName("destinations")
    val destinations: List<Pickup>,

    @SerializedName("priority")
    val priority: String = "distance" // distance, time
)

// 경로 최적화 응답 모델
data class RouteOptimizationResponse(
    @SerializedName("optimizedOrder")
    val optimizedOrder: List<String>, // pickup IDs in optimal order

    @SerializedName("totalDistance")
    val totalDistance: Double,

    @SerializedName("totalTime")
    val totalTime: Int, // minutes

    @SerializedName("routes")
    val routes: List<RouteSegment>? = null
)

data class RouteSegment(
    @SerializedName("from")
    val from: LocationInfo,

    @SerializedName("to")
    val to: LocationInfo,

    @SerializedName("distance")
    val distance: Double,

    @SerializedName("duration")
    val duration: Int,

    @SerializedName("polyline")
    val polyline: String? = null
)

// 필터 옵션 모델
data class PickupFilter(
    val status: PickupStatus = PickupStatus.ALL,
    val sortBy: SortType = SortType.DISTANCE,
    val maxDistance: Double? = null, // km
    val timeRange: TimeRange? = null
)

enum class PickupStatus {
    ALL, INCOMPLETE, COMPLETE
}

enum class SortType {
    DISTANCE, TIME, PRIORITY
}

data class TimeRange(
    val startTime: String, // HH:mm format
    val endTime: String    // HH:mm format
)

// 통계 정보 모델
data class PickupStatistics(
    @SerializedName("total")
    val total: Int,

    @SerializedName("completed")
    val completed: Int,

    @SerializedName("pending")
    val pending: Int,

    @SerializedName("totalDistance")
    val totalDistance: Double,

    @SerializedName("estimatedTime")
    val estimatedTime: Int
)