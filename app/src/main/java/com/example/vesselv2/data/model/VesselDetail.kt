package com.example.vesselv2.data.model

import com.example.vesselv2.ui.adapter.TimeCalItem

/**
 * [데이터 모델] QcWorkInfo — QC(Quay Crane) 크레인별 작업 현황
 *
 * 선박을 하역하는 크레인(QC) 한 대의 작업 현황을 나타냅니다.
 *
 * ▶ 데이터 출처:
 *   DgtDataSource.fetchVesselDetails() → CONTAINER_STATUS_URL API 응답에서 집계
 *
 * ▶ 필드 설명:
 *   - craneNo: QC 크레인 번호 (예: "QC01", "QC02")
 *   - completeDischarge: 완료된 양하(Discharge) 컨테이너 수 (배에서 육지로)
 *   - completeLoad: 완료된 적하(Load) 컨테이너 수 (육지에서 배로)
 *   - plannedDischarge: 잔여 예정 양하 컨테이너 수
 *   - plannedLoad: 잔여 예정 적하 컨테이너 수
 *
 * ▶ 계산 방법:
 *   - 총 작업량: completeDischarge + completeLoad + plannedDischarge + plannedLoad
 *   - 완료 합계: completeDischarge + completeLoad
 *   - 잔여 합계: plannedDischarge + plannedLoad
 */
data class QcWorkInfo(
    val craneNo: String,            // QC 크레인 번호
    val completeDischarge: Int,     // 완료 양하 컨테이너 수
    val completeLoad: Int,          // 완료 적하 컨테이너 수
    val plannedDischarge: Int,      // 잔여(예정) 양하 컨테이너 수
    val plannedLoad: Int            // 잔여(예정) 적하 컨테이너 수
)

/**
 * [데이터 모델] VesselDetailInfo — 선박 상세 정보 (실시간 작업 현황 포함)
 *
 * 그래프에서 선박을 클릭했을 때 표시되는 QC 현황 다이얼로그에 사용됩니다.
 *
 * ▶ 데이터 출처:
 *   VesselViewModel.fetchVesselWorkStatus() → DgtDataSource.fetchVesselDetails()
 *
 * ▶ 필드 설명:
 *   - item: 클릭된 선박의 TimeCalItem (모선명, 선석, ETB/ETD 등)
 *   - disQty: 총 예정 양하(Discharge) 수량
 *   - lodQty: 총 예정 적하(Load) 수량
 *   - shftQty: 이동(Shift) 수량 (터미널 내 이동)
 *   - statusStr: DGT 원시 상태 문자열 (예: "Working", "Berthed")
 *   - qcList: 크레인별 작업 현황 목록 (QcWorkInfo 배열)
 *   - progressPercent: 작업 진행률 (현재 미사용, 향후 확장용)
 *   - totalQty: 총 작업 수량 (disQty + lodQty + shftQty, 자동 계산)
 */
data class VesselDetailInfo(
    val item: TimeCalItem,                  // 클릭된 선박의 기본 정보
    val disQty: String,                     // 예정 양하 수량
    val lodQty: String,                     // 예정 적하 수량
    val shftQty: String,                    // 이동 수량
    val statusStr: String,                  // 원시 상태 문자열
    val qcList: List<QcWorkInfo> = emptyList(), // 크레인별 QC 작업 현황
    val progressPercent: Int = 0,           // 진행률 (향후 확장용)
    // 총 수량: disQty + lodQty + shftQty (null-safe, 파싱 실패 시 0 처리)
    val totalQty: String = (
        (disQty.toIntOrNull() ?: 0) +
        (lodQty.toIntOrNull() ?: 0) +
        (shftQty.toIntOrNull() ?: 0)
    ).toString()
)
