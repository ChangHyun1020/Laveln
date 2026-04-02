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
import android.widget.TableRow
import android.widget.LinearLayout
import com.example.vesselv2.R

/**
 * 입항 예정 통합 프래그먼트 (2026-03-18 리모델링)
 * 
 * 역할:
 * - 상단: BerthScheduleView 를 통한 그래프 표시
 * - 하단: RecyclerView 를 통한 선박 리스트(카드뷰) 표시
 * - 두 영역 모두 VesselViewModel 의 filteredList 를 관찰하여 동기화됨
 */
class VesselCombinedFragment : Fragment() {

    private var _binding: FragmentVesselCombinedBinding? = null
    private val binding get() = _binding!!

    private val viewModel: VesselViewModel by activityViewModels()
    private lateinit var listAdapter: TimeCalAdapter
    private val displayList = mutableListOf<TimeCalItem>()
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
        _binding = null
    }

    // ── 그래프 설정 ──────────────────────────────────────────────────────────

    private fun setupGraph() {
        binding.berthScheduleView.onItemClickListener = { item: TimeCalItem ->
            // 그래프에서 선박 클릭 시 실시간 현황 조회 (VesselViewModel 을 통해 직접 호출)
            viewModel.fetchVesselWorkStatus(item)
        }
    }

    // ── 리스트 설정 ──────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        listAdapter = TimeCalAdapter(
            items = displayList,
            onLongClick = { vesselName -> handleVesselLongClick(vesselName) },
            onSelectionChanged = { /* 선택 요약 기능은 제거됨 */ }
        )
        binding.recyclerView.apply {
            this.adapter = listAdapter
            this.layoutManager = LinearLayoutManager(requireContext())
        }
    }

    // ── 새로고침 ─────────────────────────────────────────────────────────────

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            // ViewModel 을 통해 데이터 갱신
            viewModel.fetchDgtData()
        }
    }

    // ── ViewModel 관찰 ────────────────────────────────────────────────────────

    @SuppressLint("NotifyDataSetChanged")
    private fun observeViewModel() {
        // 데이터 리스트 관찰
        viewModel.filteredList.observe(viewLifecycleOwner) { items ->
            // 1. 그래프 업데이트
            val startMs = viewModel.graphStartMs.value ?: System.currentTimeMillis()
            binding.berthScheduleView.setData(items, startMs)
            
            // 2. 리스트 업데이트
            displayList.clear()
            displayList.addAll(items)
            listAdapter.notifyDataSetChanged()

            // 빈 상태 처리
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        // 그래프 시작 시간 관찰
        viewModel.graphStartMs.observe(viewLifecycleOwner) { startMs ->
            val items = viewModel.filteredList.value ?: emptyList()
            binding.berthScheduleView.setData(items, startMs ?: System.currentTimeMillis())
        }

        // 로딩 상태 관찰
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading && displayList.isEmpty()) View.VISIBLE else View.GONE
            binding.berthScheduleView.alpha = if (isLoading) 0.5f else 1.0f
            if (!isLoading) binding.swipeRefreshLayout.isRefreshing = false
        }

        // 선박 상세 정보(QC 현황) 관찰
        viewModel.vesselDetail.observe(viewLifecycleOwner) { detail ->
            if (detail != null) {
                showQcStatusDialog(detail)
                // 다이얼로그 표시 후 초기화 (다시 클릭 시 데이터 수신을 위해)
                viewModel.setVesselDetail(null)
            }
        }
    }

    /**
     * QC 작업 현황 다이얼로그 표시
     */
    private fun showQcStatusDialog(detail: VesselDetailInfo) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_vessel_work_status, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvTitle)
        val llRowsContainer = dialogView.findViewById<LinearLayout>(R.id.llRowsContainer)
        val btnClose = dialogView.findViewById<View>(R.id.btnClose)

        tvTitle.text = "${detail.item.vesselName} QC 현황"

        // 기존 행 제거
        llRowsContainer.removeAllViews()

        if (detail.qcList.isEmpty()) {
            val emptyTv = TextView(requireContext()).apply {
                text = "작업 정보가 없습니다."
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40, 0, 40)
            }
            llRowsContainer.addView(emptyTv)
        } else {
            detail.qcList.forEach { qc ->
                val rowView = LayoutInflater.from(requireContext()).inflate(R.layout.item_qc_row, llRowsContainer, false)
                
                val workload = qc.plannedDischarge + qc.plannedLoad + qc.completeDischarge + qc.completeLoad
                val complete = qc.completeDischarge + qc.completeLoad
                val remaining = qc.plannedDischarge + qc.plannedLoad

                // 1층: QC 번호 및 총 작업량
                rowView.findViewById<TextView>(R.id.tvQcHeader).text = "${qc.craneNo}(총 작업량 : $workload)"
                
                // 2층: 완료량 합계 | 잔여량 합계
                rowView.findViewById<TextView>(R.id.tvSummaryComplete).text = complete.toString()
                rowView.findViewById<TextView>(R.id.tvSummaryRemaining).text = remaining.toString()

                // 3층: 상세 (완료 양하/적하, 잔여 양하/적하)
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

    // ── 롱클릭 핸들링 (Firebase 조회) ─────────────────────────────────────────

    private fun handleVesselLongClick(vesselName: String) {
        Toast.makeText(requireContext(), "정보 조회 중...", Toast.LENGTH_SHORT).show()
        db.collection(MainActivity.VESSEL_COLLECTION)
            .whereEqualTo(MainActivity.FIELD_VESSEL_NAME, vesselName)
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                if (!snap.isEmpty) {
                    val doc = snap.documents.first()
                    startActivity(Intent(requireContext(), DetailActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_VESSEL_NAME, vesselName)
                        putExtra(MainActivity.EXTRA_VESSEL_DOC_ID, doc.id)
                    })
                } else {
                    showRegisterDialog(vesselName)
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "조회 중 오류 발생", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showRegisterDialog(vesselName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("미등록 모선")
            .setMessage("'$vesselName' 정보가 없습니다. 등록하시겠습니까?")
            .setNegativeButton("취소", null)
            .setPositiveButton("등록") { _, _ ->
                startActivity(Intent(requireContext(), AddVesselActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_PREFILL_VESSEL_NAME, vesselName)
                })
            }
            .show()
    }
}
