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
import com.example.vesselv2.util.NavigationHelper
import com.example.vesselv2.util.setupEdgeToEdgeInsets

/**
 * 메인 액티비티 (2026-03-18 리모델링)
 * 
 * 역할:
 * - VesselCombinedFragment 를 호스트하여 상단 그래프 + 하단 리스트 표시
 * - 툴바 검색창을 통해 VesselViewModel 의 검색 쿼리 제어
 * - 사이드바(DrawerLayout) 관리
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VESSEL_NAME = "vesselName"
        const val EXTRA_VESSEL_DOC_ID = "vesselDocId"
        const val EXTRA_PREFILL_VESSEL_NAME = "EXTRA_PREFILL_VESSEL_NAME"
        const val VESSEL_COLLECTION = "Lashing"
        const val FIELD_VESSEL_NAME = "vesselName"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: VesselViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 시스템 바 영역 확보
        setupEdgeToEdgeInsets(binding.appBarId, binding.fab)

        viewModel = ViewModelProvider(this)[VesselViewModel::class.java]

        setupToolbar()
        // setupSearchView() // 검색 기능 제거 요청으로 주석 처리
        setupFab()
        setupBackPress()
        observeViewModel()
    }

    private fun setupToolbar() {
        // 검색창 이외의 툴바 클릭 시 맨 위로 스크롤 (Fragment 내부의 RecyclerView 에 접근은 지양하고 ViewModel 등을 통해 가능)
        binding.topName.setOnClickListener {
            // 필요 시 ViewModel 을 통해 Fragment 에 알림 전달 가능
        }

        binding.ivMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // 사이드바 설정
        NavigationHelper.setupSidebar(this, binding.navigationView) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String?): Boolean {
                // ViewModel 의 검색 쿼리 업데이트 -> Fragment 에서 관찰하여 필터링됨
                viewModel.setSearchQuery(newText.orEmpty())
                return true
            }

            override fun onQueryTextSubmit(query: String?): Boolean {
                binding.searchView.clearFocus()
                return true
            }
        })
    }

    private fun setupFab() {
        // 모선 등록 화면으로 이동
        binding.fab.setOnClickListener {
            startActivity(Intent(this, AddVesselActivity::class.java))
        }
    }

    private var backPressedTime: Long = 0L

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    // 2초 안에 2번 클릭 시 종료
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

    private fun observeViewModel() {
        // UI 이벤트 (성공/오류 메시지)
        viewModel.uiEvent.observe(this) { event ->
            when (event) {
                is VesselViewModel.UiEvent.Success -> Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
                is VesselViewModel.UiEvent.Error -> Toast.makeText(this, "오류: ${event.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // DGT 데이터 최신화
        viewModel.fetchDgtData()
    }
}
