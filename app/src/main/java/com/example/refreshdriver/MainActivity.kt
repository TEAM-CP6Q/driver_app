package com.example.refreshdriver

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.refreshdriver.network.NetworkResult
import com.example.refreshdriver.network.PickupRepository
import com.kakaomobility.knsdk.KNLanguageType
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // UI 컴포넌트
    private lateinit var emailEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var progressBar: ProgressBar

    // 데이터 및 네트워크
    private lateinit var sharedPreferences: SharedPreferences
    private val repository = PickupRepository()

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1234
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupSharedPreferences()
        checkAutoLogin()
        setupClickListeners()
        checkLocationPermission()

        // 카카오 네비 SDK 미리 초기화 (앱 시작 시)
        initializeKakaoSDKEarly()
    }

    private fun initViews() {
        emailEditText = findViewById(R.id.editTextEmail)
        passwordEditText = findViewById(R.id.editTextPassword)
        loginButton = findViewById(R.id.buttonLogin)
        progressBar = findViewById(R.id.progressBar)
    }

    private fun setupSharedPreferences() {
        sharedPreferences = getSharedPreferences("RefreshDriver", MODE_PRIVATE)
    }

    private fun checkAutoLogin() {
        val savedToken = sharedPreferences.getString("token", null)
        if (!savedToken.isNullOrEmpty()) {
            navigateToPickupList()
        }
    }

    private fun setupClickListeners() {
        loginButton.setOnClickListener {
            performLogin()
        }

        // 개발용 테스트 모드
        loginButton.setOnLongClickListener {
            checkPermissionAndStartNavigation()
            true
        }
    }

    /**
     * 앱 시작 시 카카오 네비 SDK를 미리 초기화
     */
    private fun initializeKakaoSDKEarly() {
        Thread {
            try {
                Log.d(TAG, "카카오 네비 SDK 사전 초기화 시작")

                RefreshApplication.knsdk.apply {
                    initializeWithAppKey(
                        aAppKey = "573a700f121bff5d3ea3960ff32de487",
                        aClientVersion = "1.0",
                        aUserKey = "testUser",
                        aLangType = KNLanguageType.KNLanguageType_KOREAN
                    ) { error ->
                        runOnUiThread {
                            if (error != null) {
                                Log.e(TAG, "카카오 네비 SDK 사전 초기화 실패: ${error.code}")
                                sharedPreferences.edit().putBoolean("kakao_sdk_initialized", false).apply()
                            } else {
                                Log.d(TAG, "카카오 네비 SDK 사전 초기화 성공")
                                sharedPreferences.edit().putBoolean("kakao_sdk_initialized", true).apply()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "카카오 네비 SDK 사전 초기화 중 예외", e)
                runOnUiThread {
                    sharedPreferences.edit().putBoolean("kakao_sdk_initialized", false).apply()
                }
            }
        }.start()
    }

    private fun performLogin() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "이메일과 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        showLoading(true)

        lifecycleScope.launch {
            when (val result = repository.login(email, password)) {
                is NetworkResult.Success -> {
                    val loginResponse = result.data

                    with(sharedPreferences.edit()) {
                        putString("token", loginResponse.token)
                        putString("email", loginResponse.user.email)
                        putInt("userId", loginResponse.user.id)
                        putString("role", loginResponse.user.role)
                        apply()
                    }

                    showLoading(false)
                    Toast.makeText(this@MainActivity, "로그인 성공", Toast.LENGTH_SHORT).show()

                    // 로그인 성공 후 네비 SDK 확인 및 목록으로 이동
                    ensureKakaoSDKInitialized {
                        navigateToPickupList()
                    }
                }

                is NetworkResult.Error -> {
                    showLoading(false)
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }

                is NetworkResult.Loading -> {
                    // 로딩 중
                }
            }
        }
    }

    private fun checkLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun checkPermissionAndStartNavigation() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
            else -> {
                ensureKakaoSDKInitialized {
                    Toast.makeText(this, "네비게이션 테스트 모드", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, NavigationActivity::class.java)
                    startActivity(intent)
                }
            }
        }
    }

    /**
     * 카카오 네비 SDK가 초기화되었는지 확인하고, 필요시 재초기화
     */
    private fun ensureKakaoSDKInitialized(onSuccess: () -> Unit) {
        // SDK 초기화 상태를 SharedPreferences로 관리
        val isSDKInitialized = sharedPreferences.getBoolean("kakao_sdk_initialized", false)

        if (isSDKInitialized) {
            Log.d(TAG, "카카오 네비 SDK 이미 초기화됨 (캐시됨)")
            onSuccess()
        } else {
            Log.d(TAG, "카카오 네비 SDK 재초기화 필요")
            initializeKakaoSDK(onSuccess)
        }
    }

    /**
     * 카카오 네비 SDK 초기화 (기존 방식)
     */
    private fun initializeKakaoSDK(onSuccess: () -> Unit) {
        try {
            RefreshApplication.knsdk.apply {
                initializeWithAppKey(
                    aAppKey = "573a700f121bff5d3ea3960ff32de487",
                    aClientVersion = "1.0",
                    aUserKey = "refreshDriverUser_${System.currentTimeMillis()}",
                    aLangType = KNLanguageType.KNLanguageType_KOREAN
                ) { error ->
                    runOnUiThread {
                        if (error != null) {
                            Log.e(TAG, "카카오 네비 SDK 초기화 실패: ${error.code}")
                            Toast.makeText(
                                applicationContext,
                                "카카오 네비 SDK 인증에 실패했습니다: ${error.code}",
                                Toast.LENGTH_LONG
                            ).show()

                            // 초기화 실패 시 캐시 제거
                            sharedPreferences.edit().putBoolean("kakao_sdk_initialized", false).apply()
                        } else {
                            Log.d(TAG, "카카오 네비 SDK 초기화 성공")
                            Toast.makeText(
                                applicationContext,
                                "카카오 네비 SDK 인증 성공",
                                Toast.LENGTH_SHORT
                            ).show()

                            // 초기화 성공 시 캐시 저장
                            sharedPreferences.edit().putBoolean("kakao_sdk_initialized", true).apply()
                            onSuccess()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "카카오 네비 SDK 초기화 중 예외", e)
            runOnUiThread {
                Toast.makeText(
                    this,
                    "SDK 초기화 중 오류가 발생했습니다: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()

                // 예외 발생 시 캐시 제거
                sharedPreferences.edit().putBoolean("kakao_sdk_initialized", false).apply()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "위치 권한이 허용되었습니다.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "위치 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        loginButton.isEnabled = !show
        emailEditText.isEnabled = !show
        passwordEditText.isEnabled = !show
    }

    private fun navigateToPickupList() {
        val intent = Intent(this, PickupListActivity::class.java)
        startActivity(intent)
        finish()
    }
}