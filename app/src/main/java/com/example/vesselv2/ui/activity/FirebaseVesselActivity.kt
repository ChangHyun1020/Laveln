package com.example.vesselv2.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vesselv2.R
import com.example.vesselv2.databinding.ActivityFirebaseVesselBinding
import com.example.vesselv2.data.model.Vessel
import com.example.vesselv2.ui.adapter.VesselAdapter
import com.example.vesselv2.ui.viewmodel.VesselViewModel
import com.example.vesselv2.util.Constants
import com.example.vesselv2.util.NavigationHelper
import com.example.vesselv2.util.setupEdgeToEdgeInsets
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore

/**
 * [화면] FirebaseVesselActivity — Firebase에 등록된 모선 목록 화면
 *
 * ▶ 진입 경로: 사이드바 메뉴 → "모선 목록" 선택
 *
 * ▶ 주요 기능:
 *   1. Firebase Firestore에서 등록된 모선 목록을 RecyclerView로 표시
 *   2. 검색창으로 모선명/코너 기준 실시간 필터링
 *   3. 모선 카드 클릭 → DetailActivity (상세 정보 + 사진 보기)
 *   4. 모선 카드 롱클릭 → 삭제 확인 다이얼로그
 *   5. FAB(+) 클릭 → AddVesselActivity (신규 모선 등록)
 *
 * ▶ MVVM 구조:
 *   - VesselViewModel.loadVessels() → repository.getVessels() → Firestore 조회
 *   - filteredVessels LiveData를 구독하여 검색 결과 실시간 반영
 *
 * ▶ Firebase:
 *   - 컬렉션: Constants.VESSEL_COLLECTION ("Lashing")
 *   - 삭제: FirebaseFirestore.getInstance()로 직접 document.delete() 호출
 *
 * ⚠️ 보안: Firestore 컬렉션명은 Constants.VESSEL_COLLECTION 상수 사용
 */
class FirebaseVesselActivity : AppCompatActivity() {

    // View Binding
    private lateinit var binding: ActivityFirebaseVesselBinding

    // ViewModel — viewModels() 위임 프로퍼티로 액티비티 생명주기에 바인딩
    private val viewModel: VesselViewModel by viewModels()

    // RecyclerView 어댑터
    private lateinit var vesselAdapter: VesselAdapter

    // Firebase Firestore 인스턴스 (삭제 기능에서 직접 사용)
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFirebaseVesselBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 시스템 바 영역 확보
        setupEdgeToEdgeInsets(binding.appBarId, binding.fab)

        setupToolbar()
        setupSidebar()
        setupRecyclerView()
        setupSearchView()
        setupFab()
        observeViewModel()

        // 화면 진입 시 Firestore에서 모선 목록 최초 로드
        viewModel.loadVessels()

        // 뒤로가기: 사이드바가 열려 있으면 닫기, 그렇지 않으면 이전 화면으로
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    finish()
                }
            }
        })
    }

    /**
     * 툴바 설정 — 햄버거 버튼 클릭 시 사이드바 열기
     */
    private fun setupToolbar() {
        binding.ivMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    /**
     * 사이드바(DrawerLayout) 설정
     * NavigationHelper에 메뉴 클릭 처리를 위임하고,
     * 현재 화면("모선 목록")을 사이드바에 하이라이트로 표시합니다.
     */
    private fun setupSidebar() {
        NavigationHelper.setupSidebar(this, binding.navigationView) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        // 현재 화면에 해당하는 메뉴 아이템 선택 상태로 표시
        binding.navigationView.setCheckedItem(R.id.nav_timeCal)
    }

    /**
     * RecyclerView 설정
     * - 카드 클릭: DetailActivity로 이동 (vesselName, vesselDocId 전달)
     * - 카드 롱클릭: 삭제 확인 다이얼로그 표시
     */
    private fun setupRecyclerView() {
        vesselAdapter = VesselAdapter(
            onClick = { vessel: Vessel ->
                // 클릭한 모선의 이름과 문서 ID를 DetailActivity에 전달
                val intent = Intent(this, DetailActivity::class.java).apply {
                    putExtra("vesselName", vessel.vesselName)
                    putExtra("vesselDocId", vessel.id)
                }
                startActivity(intent)
            },
            onLongClick = { vessel: Vessel, _ ->
                // 롱클릭 시 삭제 확인 다이얼로그 표시
                showDeleteDialog(vessel)
            }
        )

        binding.recyclerView.apply {
            adapter = vesselAdapter
            layoutManager = LinearLayoutManager(this@FirebaseVesselActivity)
        }
    }

    /**
     * 검색창 설정
     * - 검색창 열기: 타이틀 텍스트 숨기기
     * - 검색창 닫기: 타이틀 텍스트 복원
     * - 텍스트 변경 시: ViewModel의 setFirebaseSearchQuery()로 실시간 필터링
     */
    private fun setupSearchView() {
        // 검색창이 열리면 상단 타이틀 숨기기 (공간 확보)
        binding.searchView.setOnSearchClickListener {
            binding.topName.visibility = View.GONE
        }
        // 검색창이 닫히면 타이틀 복원
        binding.searchView.setOnCloseListener {
            binding.topName.visibility = View.VISIBLE
            false
        }
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setFirebaseSearchQuery(query ?: "")
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                // 입력할 때마다 즉시 필터링 (실시간 검색)
                viewModel.setFirebaseSearchQuery(newText ?: "")
                return true
            }
        })
    }

    /**
     * FAB(플로팅 액션 버튼) 설정
     * 클릭 시 AddVesselActivity로 이동하여 신규 모선 등록
     */
    private fun setupFab() {
        binding.fab.setOnClickListener {
            startActivity(Intent(this, AddVesselActivity::class.java))
        }
    }

    /**
     * ViewModel LiveData 구독
     * - isLoading: 로딩 중 ProgressBar 표시/숨기기
     * - filteredVessels: 필터링된 모선 목록 RecyclerView 업데이트 및 빈 상태 메시지 처리
     */
    private fun observeViewModel() {
        // 로딩 상태 관찰 — ProgressBar 표시/숨기기
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        // 필터링된 모선 목록 관찰
        viewModel.filteredVessels.observe(this) { list ->
            vesselAdapter.submitList(list)

            val isSearch = !viewModel.firebaseSearchQuery.value.isNullOrEmpty()
            val isEmpty = list.isEmpty()

            // 검색 결과 없음 vs 등록된 모선 없음 구분 표시
            binding.tvEmpty.visibility = if (isEmpty && !isSearch) View.VISIBLE else View.GONE
            binding.tvSearchEmpty.visibility = if (isEmpty && isSearch) View.VISIBLE else View.GONE
        }
    }

    /**
     * 삭제 확인 다이얼로그 표시
     * 삭제는 되돌릴 수 없으므로 사용자 확인을 받은 후 진행합니다.
     *
     * @param vessel 삭제할 모선 데이터
     */
    private fun showDeleteDialog(vessel: Vessel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_title_delete)
            .setMessage(getString(R.string.dialog_msg_delete, vessel.vesselName))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                deleteVesselFromFirebase(vessel)
            }
            .show()
    }

    /**
     * Firestore에서 모선 문서를 삭제하고 목록을 갱신합니다.
     *
     * ⚠️ 주의: 이 함수는 Firestore 문서만 삭제합니다.
     *   Storage 사진까지 삭제하려면 DetailActivity의 deleteVessel()을 사용하세요.
     *
     * ⚠️ 보안: Constants.VESSEL_COLLECTION 상수 사용 (하드코딩 금지)
     *
     * @param vessel 삭제할 모선 객체
     */
    private fun deleteVesselFromFirebase(vessel: Vessel) {
        // ⚠️ 보안: 컬렉션명은 Constants 상수 사용
        db.collection(Constants.VESSEL_COLLECTION).document(vessel.vesselName)
            .delete()
            .addOnSuccessListener {
                Toast.makeText(this, R.string.toast_delete_success, Toast.LENGTH_SHORT).show()
                viewModel.loadVessels() // 삭제 후 목록 갱신
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, getString(R.string.toast_delete_fail, e.message), Toast.LENGTH_LONG).show()
            }
    }
}
