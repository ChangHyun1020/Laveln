package com.example.vesselv2.ui.fragment

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.vesselv2.databinding.FragmentVesselCombinedBinding
import com.example.vesselv2.ui.activity.AddVesselActivity
import com.example.vesselv2.ui.activity.DetailActivity
import com.example.vesselv2.ui.activity.MainActivity
import com.example.vesselv2.ui.adapter.TimeCalItem
import com.example.vesselv2.ui.adapter.WorkingVesselQcAdapter
import com.example.vesselv2.ui.viewmodel.VesselViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.example.vesselv2.util.Constants
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * [보존 주석] 레거시 리스트 어댑터용 import
 * import androidx.recyclerview.widget.LinearLayoutManager
 * import com.example.vesselv2.ui.adapter.TimeCalAdapter
 * import com.example.vesselv2.data.model.VesselDetailInfo
 * import android.widget.TextView
 * import android.widget.LinearLayout
 * import com.example.vesselv2.R
 */

/**
 * [프래그먼트] VesselCombinedFragment — 선석 현황 통합 화면
 *
 * ▶ 호스트: MainActivity (XML에 정적으로 포함)
 *
 * ▶ 개제 문제 및 요구사항 해결:
 *   1. 무한 리프레시 루프 해결:
 *      - ViewPager2 콜백 중복 등록 방지, 어댑터 객체 유지 및 Diff 갱신 적용.
 *      - Coroutines Job 중복 차단 및 LiveData postValue 난사 제거.
 *   2. 최초 실행 시 자동 1회 데이터 스크래핑:
 *      - 앱 최초 진입 시 DGT 스케줄 목록이 로드되면 `hasInitialScraped` 플래그를 이용해
 *        작업 현황(WORKING 모선 QC 데이터)을 최초 1회 자동 스크래핑(조회)하여 화면에 표시함.
 *      - 이후에는 무한 루프 없이 대기하며, 수동 새로고침 버튼(↻), SwipeRefresh,
 *        또는 30초 실시간 자동 갱신 토글(⚡)을 통해서만 갱신됨.
 */
class VesselCombinedFragment : Fragment() {

    // View Binding
    private var _binding: FragmentVesselCombinedBinding? = null
    private val binding get() = _binding!!

    // ViewModel
    private val viewModel: VesselViewModel by activityViewModels()

    // Firestore
    private val db = FirebaseFirestore.getInstance()

    // ViewPager2 어댑터
    private var qcAdapter: WorkingVesselQcAdapter? = null

    // 현재 표시 중인 WORKING 모선 목록
    private var currentWorkingItems: List<TimeCalItem> = emptyList()

    // 최초 1회 QC 데이터 스크래핑 수행 여부 플래그
    private var hasInitialScraped: Boolean = false

    // ViewPager2 페이지 변경 콜백 (1회 등록 보장)
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    // 실시간 자동 갱신 타이머 Coroutine Job
    private var autoRefreshJob: Job? = null
    private var isAutoRefreshOn: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVesselCombinedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGraph()
        setupSwipeRefresh()
        setupRefreshControls()
        setupViewPagerCallback()
        observeViewModel()
    }

    override fun onDestroyView() {
        stopAutoRefresh()
        pageChangeCallback?.let { binding.vpWorkingVessels.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
        super.onDestroyView()
        _binding = null
    }

    // ── 그래프 설정 ──────────────────────────────────────────────────────────

    private fun setupGraph() {
        binding.berthScheduleView.onItemClickListener = { item: TimeCalItem ->
            handleGraphItemClick(item)
        }
    }

    // ── 새로고침 및 실시간 자동 갱신 설정 ───────────────────────────────────

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            performFullRefresh()
        }
    }

    /**
     * 원클릭 수동 새로고침 버튼 및 실시간 자동 갱신 토글 설정
     */
    private fun setupRefreshControls() {
        // 원클릭 수동 새로고침 버튼 (손가락 슬라이드 없이 탭 1번으로 새로고침)
        binding.btnManualRefresh.setOnClickListener {
            Toast.makeText(requireContext(), "데이터를 갱신합니다.", Toast.LENGTH_SHORT).show()
            performFullRefresh()
        }

        // 실시간 자동 갱신 토글 버튼 (30초 주기 자동 갱신)
        binding.btnAutoRefresh.setOnClickListener {
            isAutoRefreshOn = !isAutoRefreshOn
            if (isAutoRefreshOn) {
                binding.btnAutoRefresh.text = "⚡ 실시간 30초 ON"
                binding.btnAutoRefresh.setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
                startAutoRefresh()
                Toast.makeText(requireContext(), "30초 주기 실시간 자동 갱신이 시작되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnAutoRefresh.text = "⚡ 자동 off"
                binding.btnAutoRefresh.setTextColor(resources.getColor(com.example.vesselv2.R.color.text_secondary, null))
                stopAutoRefresh()
                Toast.makeText(requireContext(), "실시간 자동 갱신이 정지되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 전체 원스톱 새로고침 동기 수행 (스케줄 + 모든 WORKING QC 병렬 스크래핑) */
    private fun performFullRefresh() {
        if (currentWorkingItems.isNotEmpty()) {
            currentWorkingItems.forEach { qcAdapter?.setLoading(it.vesselName) }
        }
        viewModel.refreshAllData()
    }

    /** 30초 주기 실시간 자동 갱신 타이머 시작 */
    private fun startAutoRefresh() {
        stopAutoRefresh()
        autoRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(30_000L)
                if (isAutoRefreshOn) {
                    performFullRefresh()
                }
            }
        }
    }

    /** 자동 갱신 타이머 중단 */
    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    // ── ViewPager2 콜백 등록 (중복 등록 방지) ────────────────────────────────

    private fun setupViewPagerCallback() {
        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position, currentWorkingItems.size)
            }
        }
        binding.vpWorkingVessels.registerOnPageChangeCallback(pageChangeCallback!!)
    }

    // ── ViewModel 관찰 ────────────────────────────────────────────────────────

    @SuppressLint("NotifyDataSetChanged")
    private fun observeViewModel() {
        viewModel.filteredList.observe(viewLifecycleOwner) { items ->
            // 1. 선석 그래프 업데이트
            val startMs = viewModel.graphStartMs.value ?: System.currentTimeMillis()
            binding.berthScheduleView.setData(items, startMs)

            // 2. WORKING 모선 추출
            val workingItems = items.filter { it.vesselStatus == "WORKING" }

            // 3. WORKING 모선 목록 이름 비교로 불필요한 ViewPager2 재구성 차단
            val newNames = workingItems.map { it.vesselName }
            val oldNames = currentWorkingItems.map { it.vesselName }

            if (newNames != oldNames || qcAdapter == null) {
                currentWorkingItems = workingItems
                setupViewPager(workingItems)
            }

            // 4. [요구사항 2] 최초 앱 실행 시 작업현황 1회 자동 새로고침(스크래핑)
            if (!hasInitialScraped && workingItems.isNotEmpty()) {
                hasInitialScraped = true
                currentWorkingItems.forEach { qcAdapter?.setLoading(it.vesselName) }
                viewModel.fetchAllWorkingVesselStatus(workingItems)
            }
        }

        viewModel.graphStartMs.observe(viewLifecycleOwner) { startMs ->
            val items = viewModel.filteredList.value ?: emptyList()
            binding.berthScheduleView.setData(items, startMs ?: System.currentTimeMillis())
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            val hasWorking = currentWorkingItems.isNotEmpty()
            binding.progressBar.visibility =
                if (isLoading && !hasWorking) View.VISIBLE else View.GONE
            binding.berthScheduleView.alpha = if (isLoading) 0.5f else 1.0f

            // 무한 리프레시 루프 방지: DGT 데이터 로딩 완료 시 isRefreshing 해제
            if (!isLoading) {
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }

        // Map 수신 시 어댑터 부분 업데이트만 수행
        viewModel.workingVesselDetails.observe(viewLifecycleOwner) { detailMap ->
            val adapter = qcAdapter ?: return@observe
            detailMap.forEach { (vesselName, detail) ->
                if (detail != null) {
                    adapter.updateDetail(vesselName, detail)
                }
            }
            binding.swipeRefreshLayout.isRefreshing = false
        }
    }

    // ── ViewPager2 설정 ───────────────────────────────────────────────────────

    private fun setupViewPager(workingItems: List<TimeCalItem>) {
        if (workingItems.isEmpty()) {
            binding.llWorkingSection.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            qcAdapter = null
            return
        }

        binding.llWorkingSection.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        // 어댑터 새로 생성 후 설정
        qcAdapter = WorkingVesselQcAdapter(workingItems)
        binding.vpWorkingVessels.adapter = qcAdapter

        updatePageIndicator(binding.vpWorkingVessels.currentItem, workingItems.size)
    }

    private fun updatePageIndicator(position: Int, total: Int) {
        val safePos = if (position in 0 until total) position else 0
        binding.tvPageIndicator.text = "${safePos + 1} / $total"
    }

    // ── 그래프 클릭 처리 ───────────────────────────────────────────────────────

    private fun handleGraphItemClick(item: TimeCalItem) {
        Toast.makeText(requireContext(), "'${item.vesselName}' 정보 조회 중...", Toast.LENGTH_SHORT).show()
        db.collection(Constants.VESSEL_COLLECTION)
            .whereEqualTo(Constants.FIELD_VESSEL_NAME, item.vesselName)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                if (!snap.isEmpty) {
                    val doc = snap.documents.first()
                    startActivity(Intent(requireContext(), DetailActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_VESSEL_NAME, item.vesselName)
                        putExtra(MainActivity.EXTRA_VESSEL_DOC_ID, doc.id)
                    })
                } else {
                    showRegisterDialog(item.vesselName)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "조회 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showRegisterDialog(vesselName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("미등록 모선")
            .setMessage("'$vesselName' 모선 정보가 없습니다. 등록하시겠습니까?")
            .setNegativeButton("취소", null)
            .setPositiveButton("등록") { _, _ ->
                startActivity(Intent(requireContext(), AddVesselActivity::class.java).apply {
                    putExtra(Constants.EXTRA_PREFILL_VESSEL_NAME, vesselName)
                })
            }
            .show()
    }
}
