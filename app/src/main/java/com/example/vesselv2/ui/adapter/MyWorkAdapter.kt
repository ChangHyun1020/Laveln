package com.example.vesselv2.ui.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.vesselv2.data.local.MyWorkEntity
import com.example.vesselv2.databinding.ItemMyWorkBinding
import java.text.SimpleDateFormat
import java.util.Locale

class MyWorkAdapter(
        private var items: List<MyWorkEntity>,
        private val onEditClick: (MyWorkEntity) -> Unit,
        private val onDeleteClick: (MyWorkEntity) -> Unit
) : RecyclerView.Adapter<MyWorkAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ItemMyWorkBinding) :
            RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("SetTextI18n", "DefaultLocale")
        fun bind(item: MyWorkEntity) {
            binding.tvVesselName.text = item.vesselName
            binding.tvWorkDate.text = item.workDate

            // 시간 범위 표시: MM/dd HH:mm ~ HH:mm 형식 (날짜 포함 가독성 향상)
            val dateSdf = SimpleDateFormat("MM/dd", Locale.getDefault())
            val timeSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val dateStr = dateSdf.format(item.startTimeMs)
            val timeRange =
                    "${dateStr} ${timeSdf.format(item.startTimeMs)} ~ ${timeSdf.format(item.endTimeMs)}"
            binding.tvWorkTimeRange.text = timeRange

            val details = mutableListOf("${item.totalHours}시간")
            if (item.isSkill) details.add("기량")
            if (item.rainHours > 0) details.add("우천(${item.rainHours}h)")
            binding.tvWorkDetails.text = details.joinToString(", ")

            // 금액 정보 숨김 (GONE)
            binding.tvAmount.visibility = View.GONE

            binding.layoutEditTime.setOnClickListener { onEditClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMyWorkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    @SuppressLint("NotifyDataSetChanged")
    fun updateItems(newItems: List<MyWorkEntity>) {
        this.items = newItems
        notifyDataSetChanged()
    }
}
