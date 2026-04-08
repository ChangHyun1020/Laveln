package com.example.vesselv2.ui.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.lifecycle.ViewModelProvider
import com.example.vesselv2.databinding.ActivityMainBinding
import com.example.vesselv2.ui.viewmodel.VesselViewModel
import com.example.vesselv2.util.Constants
import com.example.vesselv2.util.NavigationHelper
import com.example.vesselv2.util.setupEdgeToEdgeInsets

/**
 * [화면] MainActivity — 앱 메인 화면 (선석 스케줄 그래프 + 입항 예정 목록)
 *
 * ▶ 진입 경로: 앱 최초 실행 또는 사이드바 "홈" 메뉴 선택
 *
 * ▶ 주요 역할:
 *   1. VesselCombinedFragment를 호스트하여 선석 그래프 + 입항 예정 리스트 표시
 *   2. 사이드바(DrawerLayout) 열기/닫기 관리
 *   3. FAB(+) 클릭 시 모선 등록 화면(AddVesselActivity)으로 이동
 *   4. 더블 백 버튼으로 앱 종료 처리
 *   5. onResume 시마다 DGT 스케줄 데이터 자동 갱신
 *
 * ▶ MVVM 구조:
 *   - VesselViewModel이 DgtDataSource를 통해 데이터를 가져와 LiveData로 제공
 *   - VesselCombinedFragment가 이 LiveData를 구독하여 UI 업데이트
 *
 * ▶ 사이드바 메뉴 (NavigationHelper 참조):
 *   - 홈(메인): MainActivity
 *   - 모선 목록: FirebaseVesselActivity
 *   - 비상 연락망: 이미지 다이얼로그
 *   - 외부 링크: DGT, PASC, BNMT, HJNC 웹사이트
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // ── Intent Extra 키 상수 ────────────────────────────────────────────
        // 다른 화면에서 MainActivity 관련 데이터를 전달할 때 사용
        const val EXTRA_VESSEL_NAME = "vesselName"
        const val EXTRA_VESSEL_DOC_ID = "vesselDocId"
        const val EXTRA_PREFILL_VESSEL_NAME = Constants.EXTRA_PREFILL_VESSEL_NAME

        // ── Firebase 컬렉션/필드 상수 (VesselCombinedFragment에서 참조) ─────
        /** Firestore 컬렉션명 */
        const val VESSEL_COLLECTION = Constants.VESSEL_COLLECTION
        /** Firestore vesselName 필드명 */
        const val FIELD_VESSEL_NAME = Constants.FIELD_VESSEL_NAME
    }

    // View Binding
    private lateinit var binding: ActivityMainBinding

    // 앱 전체 공유 ViewModel (Fragment와 공유)
    private lateinit var viewModel: VesselViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 시스템 바(상태바/네비게이션바) 영역 확보 — 겹침 방지
        setupEdgeToEdgeInsets(binding.appBarId, binding.fab)

        // ViewModel 초기화 — ViewModelProvider로 액티비티 생명주기에 바인딩
        viewModel = ViewModelProvider(this)[VesselViewModel::class.java]

        setupToolbar()
        // setupSearchView() — 검색 기능 제거 요청으로 주석 처리 (제거하지 않고 보존)
        setupFab()
        setupBackPress()
        observeViewModel()
    }

    /**
     * 툴바 설정
     * - 햄버거 버튼(ivMenu) 클릭 → 사이드바 열기
     * - 사이드바 네비게이션 메뉴 초기화 (NavigationHelper 위임)
     */
    private fun setupToolbar() {
        binding.topName.setOnClickListener {
            // 필요 시 ViewModel을 통해 Fragment에 스크롤 이벤트 전달 가능 (현재 미사용)
        }

        // 햄버거 아이콘 클릭 → START 방향(좌측) 사이드바 열기
        binding.ivMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // 사이드바(NavigationView) 메뉴 항목 클릭 처리 위임
        NavigationHelper.setupSidebar(this, binding.navigationView) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    /**
     * 검색 기능 설정 (현재 비활성화)
     *
     * 검색 쿼리 변경 시 VesselViewModel의 setSearchQuery()를 호출하면
     * VesselCombinedFragment의 filteredList가 자동으로 업데이트됩니다.
     * (기능은 구현되어 있으나 UI에서 제거 요청으로 현재 사용하지 않음)
     */
    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText.orEmpty())
                return true
            }
            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.searchView.clearFocus()
                return true
            }
        })
    }

    /**
     * FAB(플로팅 액션 버튼) 설정
     * 클릭 시 AddVesselActivity로 이동하여 신규 모선을 등록합니다.
     */
    private fun setupFab() {
        binding.fab.setOnClickListener {
            startActivity(Intent(this, AddVesselActivity::class.java))
        }
    }

    /** 마지막 뒤로가기 클릭 시각 (더블 백 누르기 앱 종료 판단용) */
    private var backPressedTime: Long = 0L

    /**
     * 뒤로가기 버튼 처리
     * - 사이드바가 열려 있으면 먼저 닫기
     * - 2초 내 2번 클릭 시 앱 종료 (실수 종료 방지)
     */
    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    // 사이드바가 열려 있으면 우선 닫기
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    // 2초 안에 두 번 누르면 종료
                    if (System.currentTimeMillis() - backPressedTime < 2000) {
                        finish()
                    } else {
                        backPressedTime = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, "'뒤로' 버튼을 한 번 더 누르시면 종료됩니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    /**
     * ViewModel LiveData 구독
     * - uiEvent: 성공/오류 토스트 메시지 표시
     *   (VesselCombinedFragment가 동일 ViewModel을 공유하므로 Fragment에서 발생한 이벤트도 수신 가능)
     */
    private fun observeViewModel() {
        viewModel.uiEvent.observe(this) { event ->
            when (event) {
                is VesselViewModel.UiEvent.Success ->
                    Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
                is VesselViewModel.UiEvent.Error ->
                    Toast.makeText(this, "오류: ${event.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * onResume: 화면이 포그라운드로 돌아올 때마다 DGT 데이터 최신화
     * (다른 화면에서 돌아올 때 데이터 갱신 보장)
     */
    override fun onResume() {
        super.onResume()
        viewModel.fetchDgtData()
    }
}
