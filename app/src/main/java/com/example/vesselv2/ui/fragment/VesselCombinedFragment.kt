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
 * ▶ 화면 구성 (2026-08-28 리팩토링 후):
 *   ┌──────────────────────────────────┐
 *   │  BerthScheduleView (선석 그래프)   │ ← Canvas 커스텀 뷰
 *   │  → 클릭: Firebase 조회 후 DetailActivity 이동 │
 *   │    (미등록: 등록 여부 팝업)         │
 *   ├──────────────────────────────────┤
 *   │  ViewPager2 (WORKING 모선 QC 현황) │ ← WorkingVesselQcAdapter
 *   │  → 스와이프로 모선별 페이지 전환 (옵션 B)│
 *   └──────────────────────────────────┘
 *
 * ▶ Q2 정책 (자동 로드 X):
 *   - 화면 최초 진입 시 WORKING 모선의 QC 현황을 자동으로 API 조회하지 않음.
 *   - 사용자가 당겨서 새로고침(SwipeRefresh)을 수행하거나 수동 갱신 시에만 QC 상세 현황을 조회함.
 *
 * ▶ 유지보수 보존:
 *   - 기존 입항 예정 카드 리스트(RecyclerView, TimeCalAdapter) 관련 코드는 주석 처리하여 보존함.
 */
class VesselCombinedFragment : Fragment() {

    // View Binding (Fragment에서는 onDestroyView에서 반드시 null 처리 필요)
    private var _binding: FragmentVesselCombinedBinding? = null
    private val binding get() = _binding!!

    // MainActivity와 공유하는 ViewModel (activityViewModels)
    private val viewModel: VesselViewModel by activityViewModels()

    // Firestore 인스턴스 (그래프 클릭 시 모선 등록 여부 확인)
    private val db = FirebaseFirestore.getInstance()

    // ViewPager2 어댑터 (WORKING 모선 QC 현황 슬라이드)
    private var qcAdapter: WorkingVesselQcAdapter? = null

    // 현재 ViewPager2에 표시 중인 WORKING 모선 목록 (변경 감지용)
    private var currentWorkingItems: List<TimeCalItem> = emptyList()

    /*
     * ===================================================================
     * [유지보수용 보존 주석] 기존 입항 예정 RecyclerView 관련 변수
     * ===================================================================
     * private lateinit var listAdapter: TimeCalAdapter
     * private val displayList = mutableListOf<TimeCalItem>()
     */

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVesselCombinedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGraph()
        // setupRecyclerView() // [보존 주석] 레거시 RecyclerView 초기화 주석 처리
        setupSwipeRefresh()
        observeViewModel()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 메모리 누수 방지: binding 해제
        _binding = null
    }

    // ── 그래프 설정 ──────────────────────────────────────────────────────────

    /**
     * BerthScheduleView(선석 그래프) 클릭 리스너 설정
     *
     * ▶ [2026-08-28 변경]
     *   - 그래프 바 클릭 시 파이어베이스 조회
     *   - 등록된 모선: DetailActivity로 이동
     *   - 미등록 모선: 등록 여부 팝업(showRegisterDialog) 후 진행
     */
    private fun setupGraph() {
        binding.berthScheduleView.onItemClickListener = { item: TimeCalItem ->
            handleGraphItemClick(item)
        }
    }

    // ── 새로고침 ─────────────────────────────────────────────────────────────

    /**
     * SwipeRefreshLayout 당겨서 새로고침 설정
     * - DGT 스케줄 데이터 재조회
     * - [Q2 적용] 수동 새로고침 시에만 WORKING 모선 QC 현황 일괄 조회 (fetchAllWorkingVesselStatus)
     */
    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.fetchDgtData()
            // Q2: 당겨서 새로고침 실행 시 WORKING 모선 QC 현황 수동 조회 요청
            if (currentWorkingItems.isNotEmpty()) {
                // 어댑터 항목들을 Loading 상태로 변경
                currentWorkingItems.forEach { qcAdapter?.setLoading(it.vesselName) }
                viewModel.fetchAllWorkingVesselStatus(currentWorkingItems)
            }
        }
    }

    // ── ViewModel 관찰 ────────────────────────────────────────────────────────

    /**
     * ViewModel LiveData 구독 — 데이터 변경 시 UI 자동 업데이트
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun observeViewModel() {
        // 필터링된 선박 목록 변경 관찰
        viewModel.filteredList.observe(viewLifecycleOwner) { items ->
            // 1. 선석 그래프 업데이트
            val startMs = viewModel.graphStartMs.value ?: System.currentTimeMillis()
            binding.berthScheduleView.setData(items, startMs)

            // 2. WORKING 모선 추출
            val workingItems = items.filter { it.vesselStatus == "WORKING" }

            // 3. WORKING 모선 목록이 변경되었을 때만 ViewPager2 구조 업데이트
            if (workingItems.map { it.vesselName } != currentWorkingItems.map { it.vesselName }) {
                currentWorkingItems = workingItems
                setupViewPager(workingItems)
                // [Q2 정책 적용: 자동 로드 X]
                // 화면 진입/목록 변경 시 자동 API 호출(fetchAllWorkingVesselStatus)을 하지 않고
                // 사용자가 SwipeRefresh를 통해 갱신할 수 있도록 NotLoaded 상태 유지.
            }

            /*
             * ===================================================================
             * [유지보수용 보존 주석] 기존 입항 예정 RecyclerView 데이터 바인딩
             * ===================================================================
             * displayList.clear()
             * displayList.addAll(items)
             * listAdapter.notifyDataSetChanged()
             * binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
             */
        }

        // 그래프 시작 시각 변경 관찰 — 그래프 재렌더링
        viewModel.graphStartMs.observe(viewLifecycleOwner) { startMs ->
            val items = viewModel.filteredList.value ?: emptyList()
            binding.berthScheduleView.setData(items, startMs ?: System.currentTimeMillis())
        }

        // 로딩 상태 관찰 — ProgressBar 표시/숨기기
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            val hasWorking = currentWorkingItems.isNotEmpty()
            binding.progressBar.visibility =
                if (isLoading && !hasWorking) View.VISIBLE else View.GONE
            binding.berthScheduleView.alpha = if (isLoading) 0.5f else 1.0f
            if (!isLoading) binding.swipeRefreshLayout.isRefreshing = false
        }

        // WORKING 모선 QC 현황 변경 관찰 → ViewPager2 어댑터 업데이트
        viewModel.workingVesselDetails.observe(viewLifecycleOwner) { detailMap ->
            val adapter = qcAdapter ?: return@observe
            detailMap.forEach { (vesselName, detail) ->
                if (detail != null) {
                    adapter.updateDetail(vesselName, detail)
                } else {
                    // detail이 null인 경우 (조회 전이거나 실패 시)
                    // 필요 시 adapter.setError 또는 NotLoaded 상태 유지
                }
            }
        }

        /*
         * ===================================================================
         * [유지보수용 보존 주석] 기존 단일 vesselDetail(팝업 다이얼로그용) 관찰 로직
         * ===================================================================
         * viewModel.vesselDetail.observe(viewLifecycleOwner) { detail ->
         *     if (detail != null) {
         *         showQcStatusDialog(detail)
         *         viewModel.setVesselDetail(null)
         *     }
         * }
         */
    }

    // ── ViewPager2 설정 (옵션 B: 모선별 1페이지) ─────────────────────────────

    /**
     * WORKING 모선 QC 현황 ViewPager2를 초기화합니다.
     *
     * @param workingItems WORKING 상태인 TimeCalItem 목록
     */
    private fun setupViewPager(workingItems: List<TimeCalItem>) {
        if (workingItems.isEmpty()) {
            binding.llWorkingSection.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            qcAdapter = null
            return
        }

        binding.llWorkingSection.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        qcAdapter = WorkingVesselQcAdapter(workingItems)
        binding.vpWorkingVessels.adapter = qcAdapter

        updatePageIndicator(0, workingItems.size)

        binding.vpWorkingVessels.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updatePageIndicator(position, workingItems.size)
            }
        })
    }

    /**
     * 페이지 인디케이터 텍스트 업데이트 (예: "1 / 3")
     */
    private fun updatePageIndicator(position: Int, total: Int) {
        binding.tvPageIndicator.text = "${position + 1} / $total"
    }

    // ── 그래프 클릭 처리 (Firebase 조회 후 이동/팝업) ───────────────────────

    /**
     * 그래프에서 선박 바를 클릭했을 때 처리합니다.
     *
     * 1. Firestore에서 모선명으로 검색
     * 2. 등록됨: DetailActivity로 이동
     * 3. 미등록: 미등록 알림 팝업 → AddVesselActivity 이동 지원
     */
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

    /**
     * 미등록 모선 안내 다이얼로그
     */
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

    /*
     * ===================================================================
     * [유지보수용 보존 주석] 기존 입항 예정 RecyclerView 초기화 및 QC 다이얼로그 함수
     * ===================================================================
     *
     * private fun setupRecyclerView() {
     *     listAdapter = TimeCalAdapter(
     *         items = displayList,
     *         onLongClick = { vesselName -> handleVesselLongClick(vesselName) },
     *         onSelectionChanged = { }
     *     )
     *     binding.recyclerView.apply {
     *         this.adapter = listAdapter
     *         this.layoutManager = LinearLayoutManager(requireContext())
     *     }
     * }
     *
     * private fun showQcStatusDialog(detail: VesselDetailInfo) {
     *     val dialogView = LayoutInflater.from(requireContext())
     *         .inflate(R.layout.dialog_vessel_work_status, null)
     *     val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
     *     val llRowsContainer = dialogView.findViewById<LinearLayout>(R.id.llRowsContainer)
     *     val btnClose = dialogView.findViewById<View>(R.id.btnClose)
     *
     *     tvTitle.text = "${detail.item.vesselName} QC 현황"
     *     llRowsContainer.removeAllViews()
     *
     *     if (detail.qcList.isEmpty()) {
     *         val emptyTv = TextView(requireContext()).apply {
     *             text = "작업 정보가 없습니다."
     *             gravity = android.view.Gravity.CENTER
     *             setPadding(0, 40, 0, 40)
     *         }
     *         llRowsContainer.addView(emptyTv)
     *     } else {
     *         detail.qcList.forEach { qc ->
     *             val rowView = LayoutInflater.from(requireContext())
     *                 .inflate(R.layout.item_qc_row, llRowsContainer, false)
     *             val workload = qc.plannedDischarge + qc.plannedLoad + qc.completeDischarge + qc.completeLoad
     *             val complete = qc.completeDischarge + qc.completeLoad
     *             val remaining = qc.plannedDischarge + qc.plannedLoad
     *
     *             rowView.findViewById<TextView>(R.id.tvQcHeader).text = "${qc.craneNo}(총 작업량 : $workload)"
     *             rowView.findViewById<TextView>(R.id.tvSummaryComplete).text = complete.toString()
     *             rowView.findViewById<TextView>(R.id.tvSummaryRemaining).text = remaining.toString()
     *             rowView.findViewById<TextView>(R.id.tvCompDis).text = qc.completeDischarge.toString()
     *             rowView.findViewById<TextView>(R.id.tvCompLod).text = qc.completeLoad.toString()
     *             rowView.findViewById<TextView>(R.id.tvRemDis).text = qc.plannedDischarge.toString()
     *             rowView.findViewById<TextView>(R.id.tvRemLod).text = qc.plannedLoad.toString()
     *
     *             llRowsContainer.addView(rowView)
     *         }
     *     }
     *
     *     val dialog = MaterialAlertDialogBuilder(requireContext())
     *         .setView(dialogView)
     *         .setCancelable(true)
     *         .create()
     *
     *     btnClose.setOnClickListener { dialog.dismiss() }
     *     dialog.show()
     * }
     */
}
