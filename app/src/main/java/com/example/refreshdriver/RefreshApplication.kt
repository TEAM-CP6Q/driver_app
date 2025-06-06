// RefreshApplication.kt
package com.example.refreshdriver

import android.app.Application
import com.google.android.gms.common.api.internal.BackgroundDetector.initialize
import com.kakao.vectormap.KakaoMapSdk
import com.kakaomobility.knsdk.KNSDK

class RefreshApplication : Application() {

    companion object {
        lateinit var knsdk: KNSDK
            private set
    }

    override fun onCreate() {
        super.onCreate()
        initializeKakaoSDK()
        // 카카오맵 SDK 초기화 (실제 앱 키로 교체 필요)
        KakaoMapSdk.init(this, "573a700f121bff5d3ea3960ff32de487")


    }



    private fun initializeKakaoSDK() {
        knsdk = KNSDK.apply {
            // SDK 설치 및 파일 경로 설정
            install(this@RefreshApplication, "$filesDir/RefreshDriver")
        }
    }
}