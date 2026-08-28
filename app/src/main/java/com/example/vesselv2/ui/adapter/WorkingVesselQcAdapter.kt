package com.example.vesselv2.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.vesselv2.R
import com.example.vesselv2.data.model.VesselDetailInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [어댑터] WorkingVesselQcAdapter — WORKING 모선 QC 현황 ViewPager2 어댑터
 *
 * ▶ 역할:
 *   WORKING 상태인 모선 목록을 슬라이드(페이지) 형태로 표시합니다.
 *   각 페이지는 item_working_vessel_qc.xml 레이아웃으로 구성됩니다.
 *
 * ▶ 로드 상태 (QcLoadState):
 *   - NotLoaded: 아직 조회 요청 전 (초기 상태) → "새로고침하여 불러오세요." 안내
 *   - Loading:   DGT API 조회 중 → ProgressBar 표시
 *   - Loaded:    조회 완료 → QC 크레인별 테이블 표시 (qcList 비면 "정보 없음" 표시)
 *   - Error:     조회 실패 → 오류 메시지 표시
 *
 * ▶ 무한 루프 방지:
 *   - stateCache에 저장된 기존 상태와 동일한 경우 notifyItemChanged를 호출하지 않음.
 */
class WorkingVesselQcAdapter(
    // WORKING 모선 기본 정보 목록
    private val workingItems: List<TimeCalItem>
) : RecyclerView.Adapter<WorkingVesselQcAdapter.QcViewHolder>() {

    /**
     * QC 현황 로드 상태 (sealed class)
     */
    sealed class QcLoadState {
        object NotLoaded : QcLoadState()
        object Loading : QcLoadState()
        data class Loaded(val detail: VesselDetailInfo) : QcLoadState()
        data class Error(val message: String?) : QcLoadState()
    }

    // 각 모선별 로드 상태 캐시 (vesselName → QcLoadState)
    private val stateCache = mutableMapOf<String, QcLoadState>()

    /** ETB/ETD 날짜 표시 포맷 (예: "08/28 08:00") */
    private val dateFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    // ── 어댑터 상태 업데이트 API ─────────────────────────────────────────────

    /**
     * 특정 모선을 로딩 중 상태로 변경합니다. (중복 변경 방지 가드)
     */
    fun setLoading(vesselName: String) {
        if (stateCache[vesselName] == QcLoadState.Loading) return // 이미 로딩 중이면 스킵
        stateCache[vesselName] = QcLoadState.Loading
        notifyItemChangedByName(vesselName)
    }

    /**
     * 특정 모선의 QC 상세 정보를 업데이트합니다. (동일 데이터 수신 시 notify 스킵)
     */
    fun updateDetail(vesselName: String, detail: VesselDetailInfo) {
        val currentState = stateCache[vesselName]
        if (currentState is QcLoadState.Loaded && currentState.detail == detail) {
            return // 동일한 데이터면 UI 재렌더링 스킵하여 무한 루프 차단
        }
        stateCache[vesselName] = QcLoadState.Loaded(detail)
        notifyItemChangedByName(vesselName)
    }

    /**
     * 특정 모선의 QC 조회 실패 상태를 설정합니다.
     */
    fun setError(vesselName: String, message: String?) {
        stateCache[vesselName] = QcLoadState.Error(message)
        notifyItemChangedByName(vesselName)
    }

    /**
     * 전체 캐시를 초기화합니다.
     */
    @Suppress("NotifyDataSetChanged")
    fun clearDetails() {
        stateCache.clear()
        notifyDataSetChanged()
    }

    /**
     * 모선명으로 해당 position을 찾아 notifyItemChanged 호출합니다.
     */
    private fun notifyItemChangedByName(vesselName: String) {
        val idx = workingItems.indexOfFirst { it.vesselName == vesselName }
        if (idx != -1) notifyItemChanged(idx)
    }

    // ── RecyclerView.Adapter 구현 ─────────────────────────────────────────────

    override fun getItemCount(): Int = workingItems.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QcViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_working_vessel_qc, parent, false)
        return QcViewHolder(view)
    }

    override fun onBindViewHolder(holder: QcViewHolder, position: Int) {
        val item = workingItems[position]
        val state = stateCache[item.vesselName] ?: QcLoadState.NotLoaded
        holder.bind(item, state)
    }

    // ── ViewHolder ───────────────────────────────────────────────────────────

    inner class QcViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // ── 헤더 뷰 ──
        private val tvVesselName: TextView = itemView.findViewById(R.id.tvVesselName)
        private val tvBerthAndTime: TextView = itemView.findViewById(R.id.tvBerthAndTime)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)

        // ── 요약 수치 뷰 ──
        private val tvTotalQty: TextView = itemView.findViewById(R.id.tvTotalQty)
        private val tvDischargeQty: TextView = itemView.findViewById(R.id.tvDischargeQty)
        private val tvLoadQty: TextView = itemView.findViewById(R.id.tvLoadQty)
        private val tvShiftQty: TextView = itemView.findViewById(R.id.tvShiftQty)

        // ── QC 테이블 관련 뷰 ──
        private val llQcTable: LinearLayout = itemView.findViewById(R.id.llQcTable)
        private val llQcRowsContainer: LinearLayout = itemView.findViewById(R.id.llQcRowsContainer)

        // ── 상태 안내 뷰 ──
        private val tvNotLoaded: TextView = itemView.findViewById(R.id.tvNotLoaded)
        private val tvNoQcInfo: TextView = itemView.findViewById(R.id.tvNoQcInfo)
        private val pbQcLoading: ProgressBar = itemView.findViewById(R.id.pbQcLoading)

        fun bind(item: TimeCalItem, state: QcLoadState) {
            tvVesselName.text = item.vesselName
            tvBerthAndTime.text = buildString {
                append(item.berth)
                append(" · ")
                append(dateFmt.format(Date(item.etbDateMs)))
                append(" ~ ")
                append(dateFmt.format(Date(item.etdDateMs)))
            }
            tvStatus.text = "작업중"

            when (state) {
                is QcLoadState.NotLoaded -> showNotLoadedState()
                is QcLoadState.Loading   -> showLoadingState()
                is QcLoadState.Loaded    -> {
                    val detail = state.detail
                    tvTotalQty.text = detail.totalQty
                    tvDischargeQty.text = detail.disQty
                    tvLoadQty.text = detail.lodQty
                    tvShiftQty.text = detail.shftQty

                    if (detail.qcList.isEmpty()) showNoQcState()
                    else showQcTableState(detail)
                }
                is QcLoadState.Error -> showErrorState(state.message)
            }
        }

        private fun showNotLoadedState() {
            llQcTable.visibility = View.GONE
            tvNotLoaded.visibility = View.VISIBLE
            tvNoQcInfo.visibility = View.GONE
            pbQcLoading.visibility = View.GONE
            resetSummary()
        }

        private fun showLoadingState() {
            llQcTable.visibility = View.GONE
            tvNotLoaded.visibility = View.GONE
            tvNoQcInfo.visibility = View.GONE
            pbQcLoading.visibility = View.VISIBLE
            resetSummary()
        }

        private fun showNoQcState() {
            llQcTable.visibility = View.GONE
            tvNotLoaded.visibility = View.GONE
            tvNoQcInfo.visibility = View.VISIBLE
            pbQcLoading.visibility = View.GONE
        }

        private fun showErrorState(message: String?) {
            llQcTable.visibility = View.GONE
            tvNotLoaded.visibility = View.GONE
            tvNoQcInfo.text = "조회 실패: ${message ?: "알 수 없는 오류"}"
            tvNoQcInfo.visibility = View.VISIBLE
            pbQcLoading.visibility = View.GONE
            resetSummary()
        }

        private fun showQcTableState(detail: VesselDetailInfo) {
            llQcTable.visibility = View.VISIBLE
            tvNotLoaded.visibility = View.GONE
            tvNoQcInfo.visibility = View.GONE
            pbQcLoading.visibility = View.GONE

            llQcRowsContainer.removeAllViews()

            detail.qcList.forEach { qc ->
                val rowView = LayoutInflater.from(itemView.context)
                    .inflate(R.layout.item_qc_row, llQcRowsContainer, false)

                val workload = qc.plannedDischarge + qc.plannedLoad +
                        qc.completeDischarge + qc.completeLoad
                val complete = qc.completeDischarge + qc.completeLoad
                val remaining = qc.plannedDischarge + qc.plannedLoad

                rowView.findViewById<TextView>(R.id.tvQcHeader).text =
                    "${qc.craneNo}(총 작업량 : $workload)"

                rowView.findViewById<TextView>(R.id.tvSummaryComplete).text = complete.toString()
                rowView.findViewById<TextView>(R.id.tvSummaryRemaining).text = remaining.toString()

                rowView.findViewById<TextView>(R.id.tvCompDis).text = qc.completeDischarge.toString()
                rowView.findViewById<TextView>(R.id.tvCompLod).text = qc.completeLoad.toString()
                rowView.findViewById<TextView>(R.id.tvRemDis).text = qc.plannedDischarge.toString()
                rowView.findViewById<TextView>(R.id.tvRemLod).text = qc.plannedLoad.toString()

                llQcRowsContainer.addView(rowView)
            }
        }

        private fun resetSummary() {
            tvTotalQty.text = "-"
            tvDischargeQty.text = "-"
            tvLoadQty.text = "-"
            tvShiftQty.text = "-"
        }
    }
}
