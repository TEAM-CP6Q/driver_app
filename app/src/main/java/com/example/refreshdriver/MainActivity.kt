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

    // SDK 초기화 상태 관리
    private var isSDKInitialized = false

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

        // 카카오 SDK 초기화 (한 번만)
        initializeKakaoSDKOnce()
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
            // SDK 초기화를 기다린 후 이동
            ensureSDKInitializedThenNavigate {
                navigateToPickupList()
            }
        }
    }

    private fun setupClickListeners() {
        loginButton.setOnClickListener {
            performLogin()
        }

        // 개발용 테스트 모드 (롱클릭)
        loginButton.setOnLongClickListener {
            checkPermissionAndStartNavigation()
            true
        }
    }

    /**
     * 카카오 SDK 한 번만 초기화 (중복 방지)
     */
    private fun initializeKakaoSDKOnce() {
        if (isSDKInitialized) {
            Log.d(TAG, "SDK 이미 초기화됨")
            return
        }

        Log.d(TAG, "카카오 SDK 초기화 시작...")

        try {
            RefreshApplication.knsdk.apply {
                initializeWithAppKey(
                    aAppKey = "573a700f121bff5d3ea3960ff32de487",
                    aClientVersion = "1.0",
                    aUserKey = "refreshDriverUser",
                    aLangType = KNLanguageType.KNLanguageType_KOREAN
                ) { error ->
                    runOnUiThread {
                        if (error != null) {
                            Log.e(TAG, "카카오 SDK 초기화 실패: ${error.code}")
                            isSDKInitialized = false

                            // 초기화 실패해도 앱은 계속 실행
                            Toast.makeText(
                                this@MainActivity,
                                "내비게이션 기능을 사용할 수 없습니다.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Log.d(TAG, "✅ 카카오 SDK 초기화 성공")
                            isSDKInitialized = true

                            Toast.makeText(
                                this@MainActivity,
                                "내비게이션 준비 완료",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "카카오 SDK 초기화 중 예외", e)
            isSDKInitialized = false

            Toast.makeText(
                this,
                "SDK 초기화 오류: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * SDK 초기화 상태 확인 후 실행
     */
    private fun ensureSDKInitializedThenNavigate(onSuccess: () -> Unit) {
        if (isSDKInitialized) {
            Log.d(TAG, "SDK 준비됨 - 바로 실행")
            onSuccess()
        } else {
            Log.d(TAG, "SDK 초기화 대기 중...")

            // 최대 5초 대기
            var waitCount = 0
            val checkSDKStatus = object : Runnable {
                override fun run() {
                    when {
                        isSDKInitialized -> {
                            Log.d(TAG, "SDK 초기화 완료 - 실행")
                            onSuccess()
                        }
                        waitCount < 50 -> { // 5초 (100ms * 50)
                            waitCount++
                            progressBar.postDelayed(this, 100)
                        }
                        else -> {
                            Log.w(TAG, "SDK 초기화 타임아웃 - 그래도 진행")
                            onSuccess()
                        }
                    }
                }
            }

            progressBar.post(checkSDKStatus)
        }
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

                    // SDK 초기화 확인 후 이동
                    ensureSDKInitializedThenNavigate {
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
                startTestNavigation()
            }
        }
    }

    /**
     * 테스트 내비게이션 시작
     */
    private fun startTestNavigation() {
        if (!isSDKInitialized) {
            Toast.makeText(this, "SDK 초기화가 완료되지 않았습니다. 잠시만 기다려주세요.", Toast.LENGTH_SHORT).show()

            // SDK 초기화 대기 후 재시도
            ensureSDKInitializedThenNavigate {
                startTestNavigationNow()
            }
        } else {
            startTestNavigationNow()
        }
    }

    private fun startTestNavigationNow() {
        Log.d(TAG, "테스트 내비게이션 시작")
        Toast.makeText(this, "테스트 내비게이션 시작 (서울역→여의도역)", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, NavigationActivity::class.java)
        intent.putExtra("testMode", true)
        startActivity(intent)
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

                    // 롱클릭으로 테스트 내비게이션을 시도했다면 바로 시작
                    if (loginButton.isPressed) {
                        startTestNavigation()
                    }
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

    override fun onResume() {
        super.onResume()

        // 액티비티가 다시 활성화될 때 SDK 상태 로깅
        Log.d(TAG, "onResume - SDK 초기화 상태: $isSDKInitialized")
    }
}