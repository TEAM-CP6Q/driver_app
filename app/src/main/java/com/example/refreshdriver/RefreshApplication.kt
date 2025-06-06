// RefreshApplication.kt
package com.example.refreshdriver

import android.app.Application
import android.util.Log
import com.kakao.vectormap.KakaoMapSdk
import com.kakaomobility.knsdk.KNSDK

class RefreshApplication : Application() {

    companion object {
        lateinit var knsdk: KNSDK
            private set

        private const val TAG = "RefreshApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application onCreate 시작")

        try {
            initializeKakaoMapSDK()
            initializeKakaoNaviSDK()
            Log.d(TAG, "SDK 초기화 완료")
        } catch (e: Exception) {
            Log.e(TAG, "SDK 초기화 중 오류 발생", e)
        }
    }

    private fun initializeKakaoMapSDK() {
        try {
            // 카카오맵 SDK 초기화
            KakaoMapSdk.init(this, "573a700f121bff5d3ea3960ff32de487")
            Log.d(TAG, "카카오맵 SDK 초기화 성공")
        } catch (e: Exception) {
            Log.e(TAG, "카카오맵 SDK 초기화 실패", e)
        }
    }

    private fun initializeKakaoNaviSDK() {
        try {
            knsdk = KNSDK.apply {
                // SDK 설치 및 파일 경로 설정
                val sdkPath = "$filesDir/RefreshDriver"
                Log.d(TAG, "KNSDK 설치 경로: $sdkPath")
                install(this@RefreshApplication, sdkPath)
            }
            Log.d(TAG, "카카오 네비 SDK 초기화 성공")
        } catch (e: Exception) {
            Log.e(TAG, "카카오 네비 SDK 초기화 실패", e)
        }
    }
}