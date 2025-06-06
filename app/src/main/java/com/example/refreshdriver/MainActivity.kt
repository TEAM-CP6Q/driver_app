package com.example.refreshdriver

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupSharedPreferences()
        checkAutoLogin()
        setupClickListeners()
        checkLocationPermission()
    }

    private fun initViews() {
        // 로그인 관련 뷰
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
            // 토큰이 있으면 바로 수거지 목록으로 이동
            navigateToPickupList()
        }
    }

    private fun setupClickListeners() {
        loginButton.setOnClickListener {
            performLogin()
        }

        // 로그인 버튼을 길게 누르면 네비게이션 테스트 실행 (개발용)
        loginButton.setOnLongClickListener {
            checkPermissionAndStartNavigation()
            true
        }
    }

    /**
     * 로그인 수행
     */
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

                    // 로그인 정보 저장
                    with(sharedPreferences.edit()) {
                        putString("token", loginResponse.token)
                        putString("email", loginResponse.user.email)
                        putInt("userId", loginResponse.user.id)
                        putString("role", loginResponse.user.role)
                        apply()
                    }

                    showLoading(false)
                    Toast.makeText(this@MainActivity, "로그인 성공", Toast.LENGTH_SHORT).show()

                    // 카카오 네비 SDK 초기화 후 수거지 목록으로 이동
                    initializeKakaoSDK {
                        navigateToPickupList()
                    }
                }

                is NetworkResult.Error -> {
                    showLoading(false)
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }

                is NetworkResult.Loading -> {
                    // 이미 로딩 중
                }
            }
        }
    }

    /**
     * 위치 권한 확인
     */
    private fun checkLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED -> {
                // 권한이 없으면 요청
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    /**
     * 네비게이션 테스트를 위한 권한 확인 및 시작 (개발용)
     */
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
                initializeKakaoSDK {
                    // 인증 성공 후 네비게이션 테스트 액티비티로 이동
                    Toast.makeText(this, "네비게이션 테스트 모드", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this, NavigationActivity::class.java)
                    startActivity(intent)
                }
            }
        }
    }

    /**
     * 카카오 네비 SDK 초기화
     */
    private fun initializeKakaoSDK(onSuccess: () -> Unit) {
        RefreshApplication.knsdk.apply {
            initializeWithAppKey(
                aAppKey = "573a700f121bff5d3ea3960ff32de487", // 카카오디벨로퍼스에서 부여 받은 앱 키
                aClientVersion = "1.0", // 현재 앱의 클라이언트 버전
                aUserKey = "refreshDriverUser", // 사용자 id
                aLangType = KNLanguageType.KNLanguageType_KOREAN, // 언어 타입
                aCompletion = { error ->
                    // Toast는 UI를 갱신하는 작업이기 때문에 UIThread에서 동작되도록 해야 합니다.
                    runOnUiThread {
                        if (error != null) {
                            Toast.makeText(
                                applicationContext,
                                "카카오 네비 SDK 인증에 실패했습니다: ${error.code}",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                applicationContext,
                                "카카오 네비 SDK 인증 성공",
                                Toast.LENGTH_SHORT
                            ).show()
                            onSuccess()
                        }
                    }
                }
            )
        }
    }

    /**
     * GPS 위치 권한 요청의 결과를 확인합니다.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
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
        finish() // MainActivity 종료
    }
}