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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vesselv2.databinding.FragmentVesselCombinedBinding
import com.example.vesselv2.ui.activity.AddVesselActivity
import com.example.vesselv2.ui.activity.DetailActivity
import com.example.vesselv2.ui.activity.MainActivity
import com.example.vesselv2.ui.adapter.TimeCalAdapter
import com.example.vesselv2.ui.adapter.TimeCalItem
import com.example.vesselv2.ui.viewmodel.VesselViewModel
import com.example.vesselv2.data.model.VesselDetailInfo
import com.example.vesselv2.data.model.QcWorkInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.FirebaseFirestore
import android.widget.TextView
import android.widget.LinearLayout
import com.example.vesselv2.R
import com.example.vesselv2.util.Constants

/**
 * [프래그먼트] VesselCombinedFragment — 입항 예정 통합 화면
 *
 * ▶ 호스트: MainActivity (XML에 정적으로 포함)
 *
 * ▶ 화면 구성:
 *   ┌──────────────────────────────────┐
 *   │  BerthScheduleView (선석 그래프)   │ ← Canvas 커스텀 뷰
 *   ├──────────────────────────────────┤
 *   │  RecyclerView (입항 예정 카드 리스트) │ ← TimeCalAdapter
 *   └──────────────────────────────────┘
 *
 * ▶ 데이터 흐름:
 *   VesselViewModel.filteredList → 그래프 + 리스트 동시 업데이트
 *   VesselViewModel.vesselDetail → QC 현황 다이얼로그 표시
 *
 * ▶ 사용자 인터랙션:
 *   - 그래프 선박 클릭: fetchVesselWorkStatus() → QC 다이얼로그
 *   - 리스트 카드 클릭: Firebase에서 등록 여부 확인 → DetailActivity 또는 등록 다이얼로그
 *   - 리스트 모선명 롱클릭: 클립보드 복사 (TimeCalAdapter 내부 처리)
 *   - 당겨서 새로고침: fetchDgtData()
 *
 * ▶ Firebase 사용:
 *   롱클릭 시 Firestore에서 해당 모선의 등록 여부를 확인합니다.
 *   (DGT 선박 이름으로 Firebase에 등록된 모선을 찾아 DetailActivity로 이동)
 */
class VesselCombinedFragment : Fragment() {

    // View Binding (Fragment에서는 onDestroyView에서 반드시 null 처리 필요)
    private var _binding: FragmentVesselCombinedBinding? = null
    private val binding get() = _binding!!

    // MainActivity와 공유하는 ViewModel (activityViewModels)
    private val viewModel: VesselViewModel by activityViewModels()

    // 리스트 어댑터
    private lateinit var listAdapter: TimeCalAdapter

    // 리스트 표시용 가변 목록 (어댑터와 공유)
    private val displayList = mutableListOf<TimeCalItem>()

    // Firestore 인스턴스 (롱클릭 시 모선 등록 여부 확인)
    private val db = FirebaseFirestore.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVesselCombinedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGraph()
        setupRecyclerView()
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
     * 그래프에서 선박 바를 클릭하면 실시간 QC 작업 현황을 조회합니다.
     */
    private fun setupGraph() {
        binding.berthScheduleView.onItemClickListener = { item: TimeCalItem ->
            // ViewModel을 통해 QC 현황 API 호출 (IO 스레드에서 실행)
            viewModel.fetchVesselWorkStatus(item)
        }
    }

    // ── 리스트 설정 ──────────────────────────────────────────────────────────

    /**
     * 입항 예정 카드 리스트 RecyclerView 초기화
     * - 카드 클릭: handleVesselLongClick() → Firebase 조회 → DetailActivity
     * - 모선명 롱클릭: 클립보드 복사 (TimeCalAdapter 내부 처리)
     */
    private fun setupRecyclerView() {
        listAdapter = TimeCalAdapter(
            items = displayList,
            onLongClick = { vesselName -> handleVesselLongClick(vesselName) },
            onSelectionChanged = { /* 선택 요약 기능 미사용 */ }
        )
        binding.recyclerView.apply {
            this.adapter = listAdapter
            this.layoutManager = LinearLayoutManager(requireContext())
        }
    }

    // ── 새로고침 ─────────────────────────────────────────────────────────────

    /**
     * SwipeRefreshLayout 당겨서 새로고침 설정
     * 당기면 ViewModel을 통해 DGT 데이터를 재조회합니다.
     */
    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.fetchDgtData()
        }
    }

    // ── ViewModel 관찰 ────────────────────────────────────────────────────────

    /**
     * ViewModel LiveData 구독 — 데이터 변경 시 UI 자동 업데이트
     *
     * filteredList: 그래프 + 리스트 동시 업데이트
     * graphStartMs: 그래프 시작 시각 변경 시 재렌더링
     * isLoading: ProgressBar 표시/숨기기
     * vesselDetail: QC 현황 다이얼로그 표시
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun observeViewModel() {
        // 필터링된 선박 목록 변경 관찰
        viewModel.filteredList.observe(viewLifecycleOwner) { items ->
            // 1. 선석 그래프 업데이트
            val startMs = viewModel.graphStartMs.value ?: System.currentTimeMillis()
            binding.berthScheduleView.setData(items, startMs)

            // 2. 카드 리스트 업데이트
            displayList.clear()
            displayList.addAll(items)
            listAdapter.notifyDataSetChanged()

            // 데이터가 없으면 빈 화면 안내 표시
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        // 그래프 시작 시각 변경 관찰 — 그래프 재렌더링
        viewModel.graphStartMs.observe(viewLifecycleOwner) { startMs ->
            val items = viewModel.filteredList.value ?: emptyList()
            binding.berthScheduleView.setData(items, startMs ?: System.currentTimeMillis())
        }

        // 로딩 상태 관찰 — 데이터 없을 때만 ProgressBar 표시 (리스트가 있으면 숨김)
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading && displayList.isEmpty()) View.VISIBLE else View.GONE
            // 로딩 중 그래프 반투명 처리 (데이터 갱신 중임을 시각적으로 표시)
            binding.berthScheduleView.alpha = if (isLoading) 0.5f else 1.0f
            // 로딩 완료 시 SwipeRefresh 인디케이터 숨기기
            if (!isLoading) binding.swipeRefreshLayout.isRefreshing = false
        }

        // 선박 상세 정보(QC 현황) 수신 관찰 → 다이얼로그 표시
        viewModel.vesselDetail.observe(viewLifecycleOwner) { detail ->
            if (detail != null) {
                showQcStatusDialog(detail)
                // 다이얼로그 표시 후 null로 초기화 (같은 선박을 다시 클릭해도 동작하도록)
                viewModel.setVesselDetail(null)
            }
        }
    }

    // ── QC 현황 다이얼로그 ────────────────────────────────────────────────────

    /**
     * 선박 QC 크레인별 작업 현황 다이얼로그를 표시합니다.
     *
     * ▶ 다이얼로그 구조 (dialog_vessel_work_status.xml):
     *   - 상단 제목: "{모선명} QC 현황"
     *   - 크레인별 행(item_qc_row.xml): QC번호, 총 작업량, 완료/잔여 양하/적하
     *   - 닫기 버튼
     *
     * ▶ 표시 정보:
     *   - 총 작업량 = 완료(양하+적하) + 잔여(양하+적하)
     *   - 완료: completeDischarge + completeLoad
     *   - 잔여: plannedDischarge + plannedLoad
     *
     * @param detail 선박 상세 정보 (QcWorkInfo 목록 포함)
     */
    private fun showQcStatusDialog(detail: VesselDetailInfo) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_vessel_work_status, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        val llRowsContainer = dialogView.findViewById<LinearLayout>(R.id.llRowsContainer)
        val btnClose = dialogView.findViewById<View>(R.id.btnClose)

        tvTitle.text = "${detail.item.vesselName} QC 현황"

        // 이전 내용 초기화
        llRowsContainer.removeAllViews()

        if (detail.qcList.isEmpty()) {
            // QC 정보가 없는 경우 안내 메시지 표시
            val emptyTv = TextView(requireContext()).apply {
                text = "작업 정보가 없습니다."
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40, 0, 40)
            }
            llRowsContainer.addView(emptyTv)
        } else {
            // 크레인별 작업 현황 행 추가
            detail.qcList.forEach { qc ->
                val rowView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_qc_row, llRowsContainer, false)

                // 수량 계산
                val workload = qc.plannedDischarge + qc.plannedLoad + qc.completeDischarge + qc.completeLoad
                val complete = qc.completeDischarge + qc.completeLoad
                val remaining = qc.plannedDischarge + qc.plannedLoad

                // 1층: QC 번호 및 총 작업량
                rowView.findViewById<TextView>(R.id.tvQcHeader).text =
                    "${qc.craneNo}(총 작업량 : $workload)"

                // 2층: 완료/잔여 합계
                rowView.findViewById<TextView>(R.id.tvSummaryComplete).text = complete.toString()
                rowView.findViewById<TextView>(R.id.tvSummaryRemaining).text = remaining.toString()

                // 3층: 상세 (양하/적하 각각)
                rowView.findViewById<TextView>(R.id.tvCompDis).text = qc.completeDischarge.toString()
                rowView.findViewById<TextView>(R.id.tvCompLod).text = qc.completeLoad.toString()
                rowView.findViewById<TextView>(R.id.tvRemDis).text = qc.plannedDischarge.toString()
                rowView.findViewById<TextView>(R.id.tvRemLod).text = qc.plannedLoad.toString()

                llRowsContainer.addView(rowView)
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ── 카드 클릭 처리 (Firebase 등록 여부 확인) ─────────────────────────

    /**
     * 입항 예정 카드 클릭 처리
     *
     * Firestore에서 해당 모선명으로 등록된 문서를 검색합니다:
     * - 등록된 경우: DetailActivity로 이동
     * - 미등록된 경우: 등록 안내 다이얼로그 표시
     *
     * ⚠️ 보안: Constants 상수 사용
     *
     * @param vesselName 클릭된 모선명 (DGT에서 가져온 이름)
     */
    private fun handleVesselLongClick(vesselName: String) {
        Toast.makeText(requireContext(), "정보 조회 중...", Toast.LENGTH_SHORT).show()
        // ⚠️ 보안: 컬렉션명과 필드명은 Constants 상수 사용
        db.collection(Constants.VESSEL_COLLECTION)
            .whereEqualTo(Constants.FIELD_VESSEL_NAME, vesselName)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                if (!snap.isEmpty) {
                    // 등록된 모선 → 상세 화면으로 이동
                    val doc = snap.documents.first()
                    startActivity(Intent(requireContext(), DetailActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_VESSEL_NAME, vesselName)
                        putExtra(MainActivity.EXTRA_VESSEL_DOC_ID, doc.id)
                    })
                } else {
                    // 미등록 모선 → 등록 안내 다이얼로그
                    showRegisterDialog(vesselName)
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "조회 중 오류 발생", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * 미등록 모선 등록 안내 다이얼로그
     * "등록" 선택 시 AddVesselActivity로 이동하고 모선명을 자동 입력합니다.
     *
     * @param vesselName DGT에서 가져온 선박명 (AddVesselActivity에 사전 입력)
     */
    private fun showRegisterDialog(vesselName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("미등록 모선")
            .setMessage("'$vesselName' 정보가 없습니다. 등록하시겠습니까?")
            .setNegativeButton("취소", null)
            .setPositiveButton("등록") { _, _ ->
                startActivity(Intent(requireContext(), AddVesselActivity::class.java).apply {
                    // 모선명을 사전 입력하여 등록 편의성 향상
                    putExtra(Constants.EXTRA_PREFILL_VESSEL_NAME, vesselName)
                })
            }
            .show()
    }
}
