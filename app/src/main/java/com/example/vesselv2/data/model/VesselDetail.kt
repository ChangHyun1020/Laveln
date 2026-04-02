package com.example.vesselv2.data.model

import com.example.vesselv2.ui.adapter.TimeCalItem

/**
 * QC 크레인별 작업 정보
 */
data class QcWorkInfo(
    val craneNo: String,
    val completeDischarge: Int,
    val completeLoad: Int,
    val plannedDischarge: Int,
    val plannedLoad: Int
)

/**
 * 선박 상세 정보 (실시간 작업 현황 포함)
 */
data class VesselDetailInfo(
    val item: TimeCalItem,
    val disQty: String,
    val lodQty: String,
    val shftQty: String,
    val statusStr: String,
    val qcList: List<QcWorkInfo> = emptyList(),
    val progressPercent: Int = 0,
    val totalQty: String = ((disQty.toIntOrNull() ?: 0) + (lodQty.toIntOrNull() ?: 0) + (shftQty.toIntOrNull() ?: 0)).toString()
)
