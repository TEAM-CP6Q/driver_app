// utils/LocationHelper.kt
package com.example.refreshdriver.utils

import android.content.Context
import kotlin.math.*

class LocationHelper(private val context: Context) {

    /**
     * 두 지점 간의 거리를 계산합니다 (Haversine 공식 사용)
     * @param lat1 출발지 위도
     * @param lon1 출발지 경도
     * @param lat2 도착지 위도
     * @param lon2 도착지 경도
     * @return 거리 (km)
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0 // 지구 반지름 (km)

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    /**
     * 거리를 포맷팅합니다
     */
    fun formatDistance(distanceKm: Double): String {
        return when {
            distanceKm < 1.0 -> "${(distanceKm * 1000).toInt()}m"
            distanceKm < 10.0 -> "${String.format("%.1f", distanceKm)}km"
            else -> "${distanceKm.toInt()}km"
        }
    }

    /**
     * 시간을 포맷팅합니다 (분 단위)
     */
    fun formatTime(minutes: Int): String {
        return when {
            minutes < 60 -> "${minutes}분"
            minutes < 1440 -> "${minutes / 60}시간 ${minutes % 60}분"
            else -> "${minutes / 1440}일 ${(minutes % 1440) / 60}시간"
        }
    }

    /**
     * 주소에서 구 정보를 추출합니다
     */
    fun extractDistrict(address: String?): String {
        if (address == null) return "기타"

        val districtPattern = "\\s([가-힣]{1,3}구)(?:\\s|$)".toRegex()
        val matchResult = districtPattern.find(address)
        return matchResult?.groupValues?.get(1) ?: "기타"
    }
}