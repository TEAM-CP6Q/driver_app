package com.example.refreshdriver.network

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
    suspend fun completePickup(@Body request: CompletePickupRequest): Response<Any>

    @PATCH("api/pickup/update-pickup")
    suspend fun updatePickup(@Body request: Map<String, Any>): Response<Any>
}

// 카카오 API 인터페이스
interface KakaoApiService {

    @GET("v2/local/search/address.json")
    suspend fun searchAddress(
        @Header("Authorization") authorization: String,
        @Query("query") query: String
    ): Response<KakaoGeocodingResponse>
}

// API 클라이언트 싱글톤
object ApiClient {
    private const val BASE_URL = "https://refresh-f5-server.o-r.kr/"
    private const val KAKAO_BASE_URL = "https://dapi.kakao.com/"
    private const val KAKAO_API_KEY = "90fc3c147a2997ec441fd2cd8e87e2a8"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
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

    suspend fun login(email: String, password: String): NetworkResult<LoginResponse> {
        return try {
            val response = ApiClient.apiService.login(LoginRequest(email, password))
            if (response.isSuccessful && response.body() != null) {
                NetworkResult.Success(response.body()!!)
            } else {
                NetworkResult.Error("로그인 실패: ${response.message()}")
            }
        } catch (e: Exception) {
            NetworkResult.Error("네트워크 오류: ${e.message}")
        }
    }

    suspend fun getTodayPickups(today: String): NetworkResult<List<Pickup>> {
        return try {
            val response = ApiClient.apiService.getTodayPickups(today)
            if (response.isSuccessful && response.body() != null) {
                NetworkResult.Success(response.body()!!)
            } else {
                NetworkResult.Error("수거지 목록 조회 실패: ${response.message()}")
            }
        } catch (e: Exception) {
            NetworkResult.Error("네트워크 오류: ${e.message}")
        }
    }

    suspend fun getPickupDetails(pickupId: String): NetworkResult<PickupDetails> {
        return try {
            val response = ApiClient.apiService.getPickupDetails(pickupId)
            if (response.isSuccessful && response.body() != null) {
                NetworkResult.Success(response.body()!!)
            } else {
                NetworkResult.Error("상세 정보 조회 실패: ${response.message()}")
            }
        } catch (e: Exception) {
            NetworkResult.Error("네트워크 오류: ${e.message}")
        }
    }

    suspend fun completePickup(pickupId: String): NetworkResult<Any> {
        return try {
            val response = ApiClient.apiService.completePickup(CompletePickupRequest(pickupId))
            if (response.isSuccessful) {
                NetworkResult.Success(Unit)
            } else {
                NetworkResult.Error("수거 완료 처리 실패: ${response.message()}")
            }
        } catch (e: Exception) {
            NetworkResult.Error("네트워크 오류: ${e.message}")
        }
    }

    suspend fun geocodeAddress(address: String): NetworkResult<Coordinate> {
        return try {
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
                NetworkResult.Success(coordinate)
            } else {
                NetworkResult.Error("주소 변환 실패")
            }
        } catch (e: Exception) {
            NetworkResult.Error("지오코딩 오류: ${e.message}")
        }
    }

}