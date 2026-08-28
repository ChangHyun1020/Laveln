package com.example.vesselv2.ui.adapter

/**
 * [데이터 모델] TimeCalItem — 선박 스케줄 단위 데이터
 *
 * DGT API에서 파싱된 선박 1척의 선석 배정 정보를 담습니다.
 * BerthScheduleGraph(그래프), QcPanelComposable(QC 패널),
 * VesselViewModel, DgtDataSource 등 전역에서 사용됩니다.
 *
 * [2026-08-28] TimeCalAdapter.kt 삭제 시 이 클래스도 함께 제거되지 않도록
 *              독립 파일(TimeCalItem.kt)로 분리하였습니다.
 */
data class TimeCalItem(
    val vesselName: String,
    val vesselRoute: String = "",
    val berth: String,
    val etb: String = "",
    val etd: String = "",
    val tradeTime: String = "",
    val totalHours: Double = 0.0,
    val vesselStatus: String,
    val etbDateMs: Long,
    val etdDateMs: Long,
    val calculatedAmount: Int = 0,
    val dischargeQty: String = "0",
    val loadQty: String = "0",
    val shiftQty: String = "0",
    val vesselCode: String = "",
    val voyageSeq: String = "",
    val voyageYear: String = "",
    val craneCount: Int = 0
)
