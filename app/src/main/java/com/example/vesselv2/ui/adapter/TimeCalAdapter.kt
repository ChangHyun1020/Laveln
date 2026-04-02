package com.example.vesselv2.ui.adapter

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.vesselv2.R
import com.example.vesselv2.databinding.ItemTimeCalBinding

/**
 * TimeCalItem - 입항 예정 모선 리스트 데이터 모델
 */
data class TimeCalItem(
    val vesselName: String,
    val vesselRoute: String, // VesselName(ServiceLane)
    val berth: String,       // BerthNo(AlongSide)
    val etb: String,         // 접안 시간 (yy/MM/dd HH:mm 포맷)
    val etd: String,         // 출항 시간 (yy/MM/dd HH:mm 포맷)
    val tradeTime: String,
    val totalHours: Double,
    val vesselStatus: String, // WORKING, PLANNED, DEPARTED, BERTHED
    val etbDateMs: Long = 0,
    val etdDateMs: Long = 0,
    var isSelected: Boolean = false,
    var calculatedAmount: Int = 0,
    val workStartDate: String = "-",
    val workEndDate: String = "-",
    val dischargeQty: String = "0",
    val loadQty: String = "0",
    val shiftQty: String = "0"
)

@Suppress("DEPRECATION")
class TimeCalAdapter(
    private val items: List<TimeCalItem>,
    private val onLongClick: ((String) -> Unit)? = null,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<TimeCalAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ItemTimeCalBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("SetTextI18n", "DefaultLocale")
        fun bind(item: TimeCalItem) {
            val context = binding.root.context

            // ── 1행: 선석 / 모선명(Route) 바인딩 ──
            binding.tvBerth.text = item.berth
            binding.tvVesselName.text = item.vesselRoute

            // ── 2행: 접안/출항 시간 표시 (yy/MM/dd HH:mm 포맷) ──
            binding.tvEtb.text = item.etb
            binding.tvEtd.text = item.etd

            // ── 2행: 총 근무시간 ──
            binding.tvTotalHours.text = String.format("%.1f시간", item.totalHours)

            // ── 3행: 작업량 (양하/선적/shift) ──
            binding.tvWorkVolume.text = "${item.dischargeQty} / ${item.loadQty} / ${item.shiftQty}"

            // ── 1행: 상태 배지 디자인 (바다 테마 색상) ──
            // 웹 페이지 기준: P(Planned), B(Berthed), W(Working), D(Departed)
            when (item.vesselStatus.uppercase().take(1)) {
                "W" -> {
                    binding.tvStatus.text = "작업"
                    binding.tvStatus.setTextColor(Color.WHITE)
                    binding.tvStatus.backgroundTintList =
                        ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.status_working)
                        ) // 노을빛 (작업 중)
                }

                "B" -> {
                    binding.tvStatus.text = "접안"
                    binding.tvStatus.setTextColor(Color.WHITE)
                    binding.tvStatus.backgroundTintList =
                        ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.status_berthed)
                        ) // 바다 이끼색 (접안)
                }

                "P" -> {
                    binding.tvStatus.text = "예정"
                    binding.tvStatus.setTextColor(Color.WHITE)
                    binding.tvStatus.backgroundTintList =
                        ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.status_planned)
                        ) // 밝은 파도색 (예정)
                }

                "D" -> {
                    binding.tvStatus.text = "종료"
                    binding.tvStatus.setTextColor(Color.WHITE)
                    binding.tvStatus.backgroundTintList =
                        ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.status_departed)
                        ) // 바다 안개색 (출항)
                }

                else -> {
                    binding.tvStatus.text = item.vesselStatus
                    binding.tvStatus.setTextColor(Color.WHITE)
                    binding.tvStatus.backgroundTintList =
                        ColorStateList.valueOf(
                            ContextCompat.getColor(context, R.color.status_departed)
                        )
                }
            }

            // ── 3번 기능: WORKING 상태 카드 배경색 강조 (작업 중 / 예정 구분) ──
            // "작업(WORKING)" 상태는 연한 강조색 배경으로 즉시 구분 가능하게 처리
            if (item.vesselStatus.uppercase().take(1) == "W") {
                binding.root.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.status_working_bg)
                )
            } else {
                binding.root.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.surface_light)
                )
            }

            // 선택 상태 UI
            if (item.isSelected) {
                binding.cardView.strokeWidth = 4
                binding.cardView.strokeColor = ContextCompat.getColor(context, R.color.md_primary)
                binding.cardView.cardElevation = 8f
                binding.ivCheck.visibility = View.VISIBLE
            } else {
                binding.cardView.strokeWidth = 1
                binding.cardView.strokeColor = ContextCompat.getColor(context, R.color.dark_divider)
                binding.cardView.cardElevation = 2f
                binding.ivCheck.visibility = View.GONE
            }

            // ── 3번 기능: 모선명 롱클릭 → 텍스트 클립보드 복사 ──
            // tvVesselName 롱클릭은 텍스트 복사 전용 (이벤트 소비)
            // 카드 전체 롱클릭은 기존 Firebase 조회 기능 유지
            binding.tvVesselName.setOnLongClickListener {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("모선명", item.vesselName)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "'${item.vesselName}' 복사됨", Toast.LENGTH_SHORT).show()
                true // 이벤트 소비 → 카드 전체 롱클릭으로 전파되지 않음
            }

            // 카드 전체 롱클릭: Firebase 조회 (기존 기능 유지)
            binding.cardView.setOnClickListener {
                onLongClick?.invoke(item.vesselName)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTimeCalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size
}
