package com.example.refreshdriver.network

import android.util.Log
import com.example.refreshdriver.models.*
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// API 인터페이스
interface ApiService {

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("api/pickup/get-today-pickup")
    suspend fun getTodayPickups(@Query("today") today: String): Response<List<Pickup>>

    @GET("api/pickup/get-details")
    suspend fun getPickupDetails(@Query("pickupId") pickupId: String): Response<PickupDetails>

    @POST("api/pickup/complete")
    suspend fun completePickup(@Body request: CompletePickupRequest): Response<ApiResponse<Any>>

    @PATCH("api/pickup/update-pickup")
    suspend fun updatePickup(@Body request: Map<String, Any>): Response<ApiResponse<Any>>

    @GET("api/pickup/statistics")
    suspend fun getPickupStatistics(): Response<PickupStatistics>

    @POST("api/route/optimize")
    suspend fun optimizeRoute(@Body request: RouteOptimizationRequest): Response<RouteOptimizationResponse>
}

// 카카오 API 인터페이스
interface KakaoApiService {

    // 기존 주소 -> 좌표 변환
    @GET("v2/local/search/address.json")
    suspend fun searchAddress(
        @Header("Authorization") authorization: String,
        @Query("query") query: String
    ): Response<KakaoGeocodingResponse>

    // 새로 추가: 좌표 -> 주소 변환 (역지오코딩)
    @GET("v2/local/geo/coord2address.json")
    suspend fun coord2Address(
        @Header("Authorization") authorization: String,
        @Query("x") longitude: String,        // 경도
        @Query("y") latitude: String,         // 위도
        @Query("input_coord") inputCoord: String = "WGS84"  // 좌표계
    ): Response<KakaoReverseGeocodingResponse>
}

// 카카오 역지오코딩 응답 모델
data class KakaoReverseGeocodingResponse(
    val meta: KakaoGeoMeta,
    val documents: List<KakaoAddressInfo>
)

data class KakaoGeoMeta(
    val total_count: Int
)

data class KakaoAddressInfo(
    val road_address: KakaoRoadAddress?,
    val address: KakaoAddressDetail?
)

data class KakaoRoadAddress(
    val address_name: String,           // 전체 도로명 주소
    val region_1depth_name: String,     // 시도
    val region_2depth_name: String,     // 구
    val region_3depth_name: String,     // 동
    val road_name: String,              // 도로명
    val underground_yn: String,         // 지하 여부
    val main_building_no: String,       // 건물 본번
    val sub_building_no: String,        // 건물 부번
    val building_name: String,          // 건물명
    val zone_no: String,                // 우편번호
    val x: String,                      // 경도
    val y: String                       // 위도
)

data class KakaoAddressDetail(
    val address_name: String,           // 전체 지번 주소
    val region_1depth_name: String,     // 시도
    val region_2depth_name: String,     // 구
    val region_3depth_name: String,     // 동
    val h_code: String,                 // 행정코드
    val b_code: String,                 // 법정코드
    val mountain_yn: String,            // 산 여부
    val main_address_no: String,        // 지번 주번지
    val sub_address_no: String,         // 지번 부번지
    val x: String,                      // 경도
    val y: String                       // 위도
)

// 새로 추가된 데이터 모델들
data class PickupStatistics(
    val totalPickups: Int,
    val completedPickups: Int,
    val pendingPickups: Int,
    val todayPickups: Int,
    val completionRate: Double
)

data class RouteOptimizationRequest(
    val startLatitude: Double,
    val startLongitude: Double,
    val destinations: List<RouteDestination>
)

data class RouteDestination(
    val pickupId: String,
    val latitude: Double,
    val longitude: Double,
    val address: String
)

data class RouteOptimizationResponse(
    val optimizedRoute: List<String>,  // pickupId 순서
    val totalDistance: Double,
    val estimatedTime: Int,
    val routes: List<RouteSegment>
)

data class RouteSegment(
    val fromPickupId: String?,
    val toPickupId: String,
    val distance: Double,
    val duration: Int
)

// 수거 완료 요청 모델 (completedAt 필드 추가)
data class CompletePickupRequest(
    val pickupId: String,
    val completedAt: String? = null
)

// API 클라이언트 싱글톤
object ApiClient {
    private const val BASE_URL = "https://refresh-f5-server.o-r.kr/"
    private const val KAKAO_BASE_URL = "https://dapi.kakao.com/"
    private const val KAKAO_API_KEY = "90fc3c147a2997ec441fd2cd8e87e2a8"
    private const val TAG = "ApiClient"

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d(TAG, message)
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        .setLenient()
        .create()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    private val kakaoRetrofit = Retrofit.Builder()
        .baseUrl(KAKAO_BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)
    val kakaoApiService: KakaoApiService = kakaoRetrofit.create(KakaoApiService::class.java)

    fun getKakaoAuthHeader(): String = "KakaoAK $KAKAO_API_KEY"
}

// 네트워크 결과 래퍼
sealed class NetworkResult<T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class Error<T>(val message: String) : NetworkResult<T>()
    class Loading<T> : NetworkResult<T>()
}

// 레포지토리 클래스
class PickupRepository {
    private val TAG = "PickupRepository"

    suspend fun login(email: String, password: String): NetworkResult<LoginResponse> {
        return try {
            Log.d(TAG, "로그인 시도: $email")
            val response = ApiClient.apiService.login(LoginRequest(email, password))
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "로그인 성공")
                NetworkResult.Success(response.body()!!)
            } else {
                val errorMsg = "로그인 실패: ${response.code()} ${response.message()}"
                Log.e(TAG, errorMsg)
                NetworkResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "네트워크 오류: ${e.message}"
            Log.e(TAG, errorMsg, e)
            NetworkResult.Error(errorMsg)
        }
    }

    suspend fun getTodayPickups(today: String): NetworkResult<List<Pickup>> {
        return try {
            Log.d(TAG, "오늘 수거지 조회: $today")
            val response = ApiClient.apiService.getTodayPickups(today)
            if (response.isSuccessful && response.body() != null) {
                val pickups = response.body()!!
                Log.d(TAG, "수거지 조회 성공: ${pickups.size}개")

                // 좌표 정보 로깅
                pickups.forEach { pickup ->
                    Log.d(TAG, "수거지 ${pickup.pickupId}: lat=${pickup.latitude}, lng=${pickup.longitude}")
                }

                NetworkResult.Success(pickups)
            } else {
                val errorMsg = "수거지 목록 조회 실패: ${response.code()} ${response.message()}"
                Log.e(TAG, errorMsg)
                NetworkResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "네트워크 오류: ${e.message}"
            Log.e(TAG, errorMsg, e)
            NetworkResult.Error(errorMsg)
        }
    }

    suspend fun getPickupDetails(pickupId: String): NetworkResult<PickupDetails> {
        return try {
            Log.d(TAG, "수거지 상세 조회: $pickupId")
            val response = ApiClient.apiService.getPickupDetails(pickupId)
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "수거지 상세 조회 성공")
                NetworkResult.Success(response.body()!!)
            } else {
                val errorMsg = "상세 정보 조회 실패: ${response.code()} ${response.message()}"
                Log.e(TAG, errorMsg)
                NetworkResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "네트워크 오류: ${e.message}"
            Log.e(TAG, errorMsg, e)
            NetworkResult.Error(errorMsg)
        }
    }

    suspend fun completePickup(pickupId: String): NetworkResult<Any> {
        return try {
            Log.d(TAG, "수거 완료 처리: $pickupId")
            val request = CompletePickupRequest(
                pickupId = pickupId,
                completedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            )
            val response = ApiClient.apiService.completePickup(request)
            if (response.isSuccessful) {
                Log.d(TAG, "수거 완료 처리 성공")
                NetworkResult.Success(Unit)
            } else {
                val errorMsg = "수거 완료 처리 실패: ${response.code()} ${response.message()}"
                Log.e(TAG, errorMsg)
                NetworkResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "네트워크 오류: ${e.message}"
            Log.e(TAG, errorMsg, e)
            NetworkResult.Error(errorMsg)
        }
    }

    suspend fun geocodeAddress(address: String): NetworkResult<Coordinate> {
        return try {
            Log.d(TAG, "주소 지오코딩: $address")
            val response = ApiClient.kakaoApiService.searchAddress(
                ApiClient.getKakaoAuthHeader(),
                address
            )
            if (response.isSuccessful && response.body()?.documents?.isNotEmpty() == true) {
                val doc = response.body()!!.documents[0]
                val coordinate = Coordinate(
                    id = "",
                    latitude = doc.y.toDouble(),
                    longitude = doc.x.toDouble(),
                    name = "",
                    address = address
                )
                Log.d(TAG, "지오코딩 성공: ${doc.y}, ${doc.x}")
                NetworkResult.Success(coordinate)
            } else {
                val errorMsg = "주소 변환 실패: $address"
                Log.w(TAG, errorMsg)
                NetworkResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "지오코딩 오류: ${e.message}"
            Log.e(TAG, errorMsg, e)
            NetworkResult.Error(errorMsg)
        }
    }

    suspend fun getPickupStatistics(): NetworkResult<PickupStatistics> {
        return try {
            Log.d(TAG, "수거지 통계 조회")
            val response = ApiClient.apiService.getPickupStatistics()
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "통계 조회 성공")
                NetworkResult.Success(response.body()!!)
            } else {
                val errorMsg = "통계 조회 실패: ${response.code()} ${response.message()}"
                Log.e(TAG, errorMsg)
                NetworkResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "네트워크 오류: ${e.message}"
            Log.e(TAG, errorMsg, e)
            NetworkResult.Error(errorMsg)
        }
    }

    suspend fun optimizeRoute(request: RouteOptimizationRequest): NetworkResult<RouteOptimizationResponse> {
        return try {
            Log.d(TAG, "경로 최적화 요청: ${request.destinations.size}개 목적지")
            val response = ApiClient.apiService.optimizeRoute(request)
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "경로 최적화 성공")
                NetworkResult.Success(response.body()!!)
            } else {
                val errorMsg = "경로 최적화 실패: ${response.code()} ${response.message()}"
                Log.e(TAG, errorMsg)
                NetworkResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "네트워크 오류: ${e.message}"
            Log.e(TAG, errorMsg, e)
            NetworkResult.Error(errorMsg)
        }
    }

    /**
     * 좌표를 주소로 변환 (역지오코딩)
     */
    /**
     * 좌표를 주소로 변환 (역지오코딩) - 올바른 API 사용
     */
    suspend fun reverseGeocode(latitude: Double, longitude: Double): NetworkResult<String> {
        return try {
            Log.d(TAG, "역지오코딩 요청: ($latitude, $longitude)")
            val response = ApiClient.kakaoApiService.coord2Address(
                authorization = ApiClient.getKakaoAuthHeader(),
                longitude = longitude.toString(),
                latitude = latitude.toString()
            )

            if (response.isSuccessful && response.body() != null) {
                val result = response.body()!!
                Log.d(TAG, "역지오코딩 응답: ${result.documents.size}개 결과")

                if (result.documents.isNotEmpty()) {
                    val addressInfo = result.documents[0]

                    // 도로명 주소 우선, 없으면 지번 주소 사용
                    val address = addressInfo.road_address?.address_name
                        ?: addressInfo.address?.address_name

                    if (!address.isNullOrBlank()) {
                        Log.d(TAG, "역지오코딩 성공: ($latitude, $longitude) -> $address")
                        NetworkResult.Success(address)
                    } else {
                        val errorMsg = "주소 정보가 없습니다"
                        Log.w(TAG, errorMsg)
                        NetworkResult.Error(errorMsg)
                    }
                } else {
                    val errorMsg = "해당 좌표의 주소를 찾을 수 없습니다"
                    Log.w(TAG, errorMsg)
                    NetworkResult.Error(errorMsg)
                }
            } else {
                val errorMsg = "역지오코딩 요청 실패: ${response.code()} ${response.message()}"
                Log.e(TAG, errorMsg)
                NetworkResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = "역지오코딩 오류: ${e.message}"
            Log.e(TAG, errorMsg, e)
            NetworkResult.Error(errorMsg)
        }
    }


    /**
     * 좌표를 주소로 변환 후 다시 좌표로 변환하여 도로 접근 가능한 좌표 얻기
     */
    suspend fun getNavigableCoordinates(latitude: Double, longitude: Double): NetworkResult<Coordinate> {
        return try {
            Log.d(TAG, "내비게이션 가능 좌표 변환 시작: ($latitude, $longitude)")

            // 1단계: 좌표 -> 주소 변환
            when (val addressResult = reverseGeocode(latitude, longitude)) {
                is NetworkResult.Success -> {
                    val address = addressResult.data
                    Log.d(TAG, "1단계 완료 - 주소: $address")

                    // 2단계: 주소 -> 좌표 변환 (도로 접근 가능한 좌표)
                    when (val coordResult = geocodeAddress(address)) {
                        is NetworkResult.Success -> {
                            val newCoord = coordResult.data
                            Log.d(TAG, "2단계 완료 - 새 좌표: (${newCoord.latitude}, ${newCoord.longitude})")

                            // 주소 정보도 포함한 Coordinate 반환
                            NetworkResult.Success(
                                Coordinate(
                                    id = "",
                                    latitude = newCoord.latitude,
                                    longitude = newCoord.longitude,
                                    name = address,
                                    address = address
                                )
                            )
                        }
                        is NetworkResult.Error -> {
                            Log.w(TAG, "2단계 실패: ${coordResult.message}")
                            NetworkResult.Error("주소를 좌표로 변환 실패: ${coordResult.message}")
                        }
                        else -> NetworkResult.Loading()
                    }
                }
                is NetworkResult.Error -> {
                    Log.w(TAG, "1단계 실패: ${addressResult.message}")
                    NetworkResult.Error("좌표를 주소로 변환 실패: ${addressResult.message}")
                }
                else -> NetworkResult.Loading()
            }
        } catch (e: Exception) {
            val errorMsg = "내비게이션 가능 좌표 변환 오류: ${e.message}"
            Log.e(TAG, errorMsg, e)
            NetworkResult.Error(errorMsg)
        }
    }
}