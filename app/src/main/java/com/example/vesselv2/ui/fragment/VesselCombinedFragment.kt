package com.example.vesselv2.ui.fragment

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.vesselv2.databinding.FragmentVesselCombinedBinding
import com.example.vesselv2.ui.activity.AddVesselActivity
import com.example.vesselv2.ui.activity.DetailActivity
import com.example.vesselv2.ui.activity.MainActivity
import com.example.vesselv2.ui.adapter.TimeCalItem
import com.example.vesselv2.ui.viewmodel.VesselViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import com.example.vesselv2.util.Constants
import kotlinx.coroutines.Job
import androidx.compose.ui.draw.alpha  // Modifier.alpha() — setupGraph, updateComposeGraph에서 로딩 시 그래프 반투명 처리용
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

    // 현재 표시 중인 WORKING 모선 목록
    private var currentWorkingItems = androidx.compose.runtime.mutableStateOf<List<TimeCalItem>>(emptyList())

    // 각 모선별 QC 데이터 상태
    private var qcStates = androidx.compose.runtime.mutableStateOf<Map<String, com.example.vesselv2.ui.view.QcLoadState>>(emptyMap())

    // 실시간 자동 갱신 타이머 Coroutine Job
    private var autoRefreshJob: Job? = null
    private var isAutoRefreshOn: Boolean = false

    /**
     * [2026-08-28 추가] 그래프 접기/펼치기 상태
     * - SharedPreferences에 저장하여 앱 재시작 후에도 유지
     * - 기본값: true (펼침) — 최초 진입 사용자가 그래프를 인지할 수 있도록
     */
    private var isGraphExpanded: Boolean = true

    /** 그래프 접기/펼치기 애니메이션 Duration (ms) */
    private val GRAPH_ANIM_DURATION = 250L

    /** SharedPreferences 키 상수 */
    companion object {
        private const val PREF_NAME = "vessel_combined_pref"
        private const val PREF_KEY_GRAPH_EXPANDED = "is_graph_expanded"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVesselCombinedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGraph()
        setupGraphToggle()   // [2026-08-28 추가] 그래프 접기/펼치기 초기화
        setupSwipeRefresh()
        setupRefreshControls()
        setupQcPager()
        observeViewModel()
    }

    override fun onDestroyView() {
        stopAutoRefresh()
        super.onDestroyView()
        _binding = null
    }

    // ── 그래프 설정 ──────────────────────────────────────────────────────────

    private fun setupGraph() {
        binding.berthScheduleComposeView.apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // 초기에 빈 화면 렌더링, 이후 observeViewModel 에서 갱신됨
                com.example.vesselv2.ui.view.BerthScheduleGraph(
                    items = viewModel.filteredList.value ?: emptyList(),
                    baseDateMs = viewModel.graphStartMs.value ?: System.currentTimeMillis(),
                    onItemClick = { item: TimeCalItem -> handleGraphItemClick(item) },
                    modifier = androidx.compose.ui.Modifier.let { m ->
                        if (viewModel.isLoading.value == true) m.alpha(0.5f) else m
                    }
                )
            }
        }
    }
    
    private fun updateComposeGraph(items: List<TimeCalItem>, startMs: Long, isLoading: Boolean) {
        binding.berthScheduleComposeView.setContent {
            com.example.vesselv2.ui.view.BerthScheduleGraph(
                items = items,
                baseDateMs = startMs,
                onItemClick = { item: TimeCalItem -> handleGraphItemClick(item) },
                modifier = androidx.compose.ui.Modifier.let { m ->
                    if (isLoading) m.alpha(0.5f) else m
                }
            )
        }
    }

    // ── 그래프 접기/펼치기 ────────────────────────────────────────────────────

    /**
     * [2026-08-28 추가] 그래프 접기/펼치기 초기화
     *
     * ▶ 동작:
     *   1. SharedPreferences에서 마지막 상태를 복원하여 그래프 초기 표시 여부 결정
     *   2. llGraphHeader 클릭 시 toggleGraph() 호출
     *   3. 화살표(ivGraphChevron)는 펼침 시 0°, 접힘 시 180° 회전
     */
    private fun setupGraphToggle() {
        // SharedPreferences에서 이전 상태 복원
        val prefs = requireContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        isGraphExpanded = prefs.getBoolean(PREF_KEY_GRAPH_EXPANDED, true)

        // 초기 상태 즉시 반영 (애니메이션 없이)
        applyGraphState(animate = false)

        // 헤더 행 클릭 시 접기/펼치기 토글
        binding.llGraphHeader.setOnClickListener {
            toggleGraph()
        }
    }

    /**
     * 그래프 접기/펼치기 토글
     * 상태 전환 후 SharedPreferences에 저장하고 애니메이션 적용
     */
    private fun toggleGraph() {
        isGraphExpanded = !isGraphExpanded

        // SharedPreferences에 상태 저장 (앱 재시작 후에도 유지)
        requireContext()
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEY_GRAPH_EXPANDED, isGraphExpanded)
            .apply()

        applyGraphState(animate = true)
    }

    /**
     * 현재 isGraphExpanded 상태를 UI에 반영합니다.
     *
     * @param animate true이면 높이 애니메이션 + 화살표 회전 적용, false이면 즉시 적용
     */
    private fun applyGraphState(animate: Boolean) {
        val graphView = binding.scrollViewGraph
        val chevron = binding.ivGraphChevron

        if (animate) {
            if (isGraphExpanded) {
                // ── 펼치기: VISIBLE 후 높이 0 → 원래 높이 애니메이션 ──
                graphView.visibility = View.VISIBLE
                graphView.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        (graphView.parent as View).width, View.MeasureSpec.EXACTLY
                    ),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val targetHeight = graphView.measuredHeight

                ValueAnimator.ofInt(0, targetHeight).apply {
                    duration = GRAPH_ANIM_DURATION
                    addUpdateListener { anim ->
                        graphView.layoutParams = graphView.layoutParams.also {
                            it.height = anim.animatedValue as Int
                        }
                        graphView.requestLayout()
                    }
                    // 애니메이션 완료 후 높이 제한 해제 (wrap_content 복원)
                    doOnEnd { graphView.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    start()
                }

                // 화살표 0° (펼침 상태)
                chevron.animate().rotation(0f).setDuration(GRAPH_ANIM_DURATION).start()

            } else {
                // ── 접기: 현재 높이 → 0 애니메이션 후 GONE ──
                val startHeight = graphView.measuredHeight

                ValueAnimator.ofInt(startHeight, 0).apply {
                    duration = GRAPH_ANIM_DURATION
                    addUpdateListener { anim ->
                        graphView.layoutParams = graphView.layoutParams.also {
                            it.height = anim.animatedValue as Int
                        }
                        graphView.requestLayout()
                    }
                    doOnEnd { graphView.visibility = View.GONE }
                    start()
                }

                // 화살표 180° (접힘 상태)
                chevron.animate().rotation(180f).setDuration(GRAPH_ANIM_DURATION).start()
            }

        } else {
            // 애니메이션 없이 즉시 반영 (초기 상태 복원 시 사용)
            if (isGraphExpanded) {
                graphView.visibility = View.VISIBLE
                graphView.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                chevron.rotation = 0f
            } else {
                graphView.visibility = View.GONE
                chevron.rotation = 180f
            }
        }
    }

    /**
     * ValueAnimator에서 애니메이션 종료 시 실행할 콜백을 DSL 스타일로 등록하는 확장 함수
     * (android.animation.Animator.AnimatorListener 보일러플레이트 대체)
     */
    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
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
        if (currentWorkingItems.value.isNotEmpty()) {
            val newMap = qcStates.value.toMutableMap()
            currentWorkingItems.value.forEach { newMap[it.vesselName] = com.example.vesselv2.ui.view.QcLoadState.Loading }
            qcStates.value = newMap
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

    // ── Compose View 설정 (하단 QC 패널) ───────────────────────────────────

    private fun setupQcPager() {
        binding.vpWorkingVesselsCompose.apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val items = currentWorkingItems.value
                val states = qcStates.value

                // Compose는 Compose UI 렌더링만 담당
                // llWorkingSection/tvEmpty 가시성 제어는 observeViewModel()에서 수행
                if (items.isNotEmpty()) {
                    com.example.vesselv2.ui.view.QcPagerComposable(
                        workingItems = items,
                        qcStates = states,
                        onPageChanged = { page ->
                            updatePageIndicator(page, items.size)
                        }
                    )
                }
            }
        }
    }

    // ── ViewModel 관찰 ────────────────────────────────────────────────────────

    @SuppressLint("NotifyDataSetChanged")
    private fun observeViewModel() {
        viewModel.filteredList.observe(viewLifecycleOwner) { items ->
            // 1. 선석 그래프 업데이트
            val startMs = viewModel.graphStartMs.value ?: System.currentTimeMillis()
            updateComposeGraph(items, startMs, viewModel.isLoading.value == true)

            // 2. WORKING 모선 추출
            val workingItems = items.filter { it.vesselStatus == "WORKING" }

            // 3. WORKING 모선 상태 갱신
            currentWorkingItems.value = workingItems

            // 4. View 시스템 가시성 제어 — LiveData 옵저버(Main 스레드)에서 안전하게 수행
            if (workingItems.isEmpty()) {
                binding.llWorkingSection.visibility = View.GONE
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                binding.llWorkingSection.visibility = View.VISIBLE
                binding.tvEmpty.visibility = View.GONE
            }

            // 5. [요구사항 2] 최초 앱 실행 시 작업현황 1회 자동 새로고침(스크래핑)
            // viewModel.hasInitialScraped: Fragment 재생성(ud654면 회전)에도 유지됨
            if (!viewModel.hasInitialScraped && workingItems.isNotEmpty()) {
                viewModel.hasInitialScraped = true
                val newMap = qcStates.value.toMutableMap()
                workingItems.forEach { newMap[it.vesselName] = com.example.vesselv2.ui.view.QcLoadState.Loading }
                qcStates.value = newMap
                viewModel.fetchAllWorkingVesselStatus(workingItems)
            }
        }

        viewModel.graphStartMs.observe(viewLifecycleOwner) { startMs ->
            val items = viewModel.filteredList.value ?: emptyList()
            updateComposeGraph(items, startMs ?: System.currentTimeMillis(), viewModel.isLoading.value == true)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            val hasWorking = currentWorkingItems.value.isNotEmpty()
            binding.progressBar.visibility =
                if (isLoading && !hasWorking) View.VISIBLE else View.GONE
            
            val items = viewModel.filteredList.value ?: emptyList()
            val startMs = viewModel.graphStartMs.value ?: System.currentTimeMillis()
            updateComposeGraph(items, startMs, isLoading)

            // 무한 리프레시 루프 방지: DGT 데이터 로딩 완료 시 isRefreshing 해제
            if (!isLoading) {
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }

        // Map 수신 시 어댑터 부분 업데이트만 수행
        // Map 수신 시 상태 갱신
        viewModel.workingVesselDetails.observe(viewLifecycleOwner) { detailMap ->
            val newMap = qcStates.value.toMutableMap()
            detailMap.forEach { (vesselName, detail) ->
                if (detail != null) {
                    newMap[vesselName] = com.example.vesselv2.ui.view.QcLoadState.Loaded(detail)
                } else {
                    newMap[vesselName] = com.example.vesselv2.ui.view.QcLoadState.Error("정보 없음")
                }
            }
            qcStates.value = newMap
            binding.swipeRefreshLayout.isRefreshing = false
        }
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
