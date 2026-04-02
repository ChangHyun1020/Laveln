package com.example.vesselv2.ui.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vesselv2.databinding.ItemVesselBinding
import com.example.vesselv2.data.model.Vessel
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import androidx.core.graphics.toColorInt

/**
 * VesselAdapter - 선박 목록 어댑터
 */
class VesselAdapter(
    private val onClick: (Vessel) -> Unit,
    private val onLongClick: (Vessel, View) -> Unit
) : ListAdapter<Vessel, VesselAdapter.VesselViewHolder>(DiffCallback()) {

    inner class VesselViewHolder(
        private val binding: ItemVesselBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("UseCompatTextViewDrawableApis")
        fun bind(vessel: Vessel) {
            val context = binding.root.context

            // 오늘 날짜 (YYYY-MM-DD 형식으로 비교)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

            // 작업 예정 여부 (오늘 이후 날짜인 경우 강조)
            val isScheduled = vessel.date.isNotEmpty() && vessel.date >= today

            binding.tvVesselName.text = vessel.vesselName.trim()
            binding.tvCorn.text = vessel.corn
            binding.tvPort.text = vessel.flan
            binding.tvNotePreview.text = vessel.Notes

            if (isScheduled) {
                binding.root.setCardBackgroundColor(context.getColor(com.example.vesselv2.R.color.md_primary))
                binding.root.strokeColor = context.getColor(com.example.vesselv2.R.color.md_primary)

                binding.tvVesselName.setTextColor(context.getColor(com.example.vesselv2.R.color.white))
                binding.tvVesselName.setCompoundDrawablesWithIntrinsicBounds(
                    android.R.drawable.ic_menu_compass, 0, 0, 0
                )
                binding.tvVesselName.compoundDrawableTintList =
                    android.content.res.ColorStateList.valueOf(context.getColor(com.example.vesselv2.R.color.white))

                binding.tvCorn.setTextColor(context.getColor(com.example.vesselv2.R.color.white))
                binding.tvPort.setTextColor(context.getColor(com.example.vesselv2.R.color.white))
                binding.tvNotePreview.setTextColor(context.getColor(com.example.vesselv2.R.color.white))
                binding.tvNotePreview.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(context.getColor(com.example.vesselv2.R.color.md_secondary))
            } else {
                binding.root.setCardBackgroundColor(context.getColor(com.example.vesselv2.R.color.surface_light))
                binding.root.strokeColor = "#E1E2E5".toColorInt()

                binding.tvVesselName.setTextColor(context.getColor(com.example.vesselv2.R.color.md_primary))
                binding.tvVesselName.setCompoundDrawablesWithIntrinsicBounds(
                    android.R.drawable.ic_menu_compass, 0, 0, 0
                )
                binding.tvVesselName.compoundDrawableTintList =
                    android.content.res.ColorStateList.valueOf(context.getColor(com.example.vesselv2.R.color.md_primary))

                binding.tvCorn.setTextColor(context.getColor(com.example.vesselv2.R.color.md_primary))
                binding.tvPort.setTextColor(context.getColor(com.example.vesselv2.R.color.md_primary))
                binding.tvNotePreview.setTextColor(context.getColor(com.example.vesselv2.R.color.text_secondary))
                binding.tvNotePreview.backgroundTintList = null
            }

            binding.root.setOnClickListener { onClick(vessel) }
            binding.root.setOnLongClickListener {
                onLongClick(vessel, binding.root)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VesselViewHolder {
        val binding = ItemVesselBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VesselViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VesselViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DiffCallback : DiffUtil.ItemCallback<Vessel>() {
        override fun areItemsTheSame(old: Vessel, new: Vessel) = old.id == new.id
        override fun areContentsTheSame(old: Vessel, new: Vessel) = old == new
    }
}
