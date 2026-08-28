package com.example.vesselv2.ui.view

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vesselv2.data.model.VesselDetailInfo
import com.example.vesselv2.ui.adapter.TimeCalItem
import java.text.SimpleDateFormat
import java.util.*

sealed class QcLoadState {
    object NotLoaded : QcLoadState()
    object Loading : QcLoadState()
    data class Loaded(val detail: VesselDetailInfo) : QcLoadState()
    data class Error(val message: String?) : QcLoadState()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QcPagerComposable(
    workingItems: List<TimeCalItem>,
    qcStates: Map<String, QcLoadState>,
    onPageChanged: (Int) -> Unit
) {
    if (workingItems.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { workingItems.size })

    // 페이지 변경 감지
    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        val item = workingItems[page]
        val state = qcStates[item.vesselName] ?: QcLoadState.NotLoaded
        QcPageContent(item = item, state = state)
    }
}

@Composable
fun QcPageContent(item: TimeCalItem, state: QcLoadState) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(scrollState)
    ) {
        // 모선 정보 헤더 카드
        VesselHeaderCard(item)
        Spacer(modifier = Modifier.height(6.dp))

        // 상태에 따른 요약 수치
        val detail = (state as? QcLoadState.Loaded)?.detail
        QcSummaryCard(detail)
        Spacer(modifier = Modifier.height(6.dp))

        // QC 크레인별 작업 현황 테이블 카드
        QcTableCard(state)
    }
}

@Composable
fun VesselHeaderCard(item: TimeCalItem) {
    val dateFmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    val etbStr = dateFmt.format(Date(item.etbDateMs))
    val etdStr = dateFmt.format(Date(item.etdDateMs))
    val diffMs = item.etdDateMs - item.etbDateMs
    val totalHoursStr = if (diffMs > 0) "  ${diffMs / (1000 * 60 * 60)}시간" else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0077C2)) // md_primary
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.vesselName,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.berth} · $etbStr ~ $etdStr$totalHoursStr",
                    color = Color(0xFFBBDEFB),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                text = "작업중",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(Color(0xFF0277BD), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun QcSummaryCard(detail: VesselDetailInfo?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFAFAFA)) // surface_light
        ) {
            SummaryItem("총 작업량", detail?.totalQty ?: "-", Modifier.weight(1f))
            VerticalDivider(modifier = Modifier.height(50.dp), color = Color(0xFFE0E0E0))
            SummaryItem("양하", detail?.disQty ?: "-", Modifier.weight(1f))
            VerticalDivider(modifier = Modifier.height(50.dp), color = Color(0xFFE0E0E0))
            SummaryItem("적하", detail?.lodQty ?: "-", Modifier.weight(1f))
            VerticalDivider(modifier = Modifier.height(50.dp), color = Color(0xFFE0E0E0))
            SummaryItem("Shift", detail?.shftQty ?: "-", Modifier.weight(1f))
        }
    }
}

@Composable
fun SummaryItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, color = Color(0xFF757575), fontSize = 11.sp) // text_secondary
        Text(
            text = value,
            color = Color(0xFF212121), // on_surface
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
fun QcTableCard(state: QcLoadState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFAFAFA))
        ) {
            when (state) {
                is QcLoadState.NotLoaded -> {
                    Text(
                        text = "↻  스와이프하여 새로고침하면 QC 현황을 불러옵니다.",
                        color = Color(0xFF757575),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    )
                }
                is QcLoadState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 24.dp)
                    )
                }
                is QcLoadState.Error -> {
                    Text(
                        text = "조회 실패: ${state.message ?: "알 수 없는 오류"}",
                        color = Color(0xFF757575),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    )
                }
                is QcLoadState.Loaded -> {
                    val detail = state.detail
                    if (detail.qcList.isEmpty()) {
                        Text(
                            text = "QC 작업 정보가 없습니다.",
                            color = Color(0xFF757575),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        )
                    } else {
                        QcTable(detail)
                    }
                }
            }
        }
    }
}

@Composable
fun QcTable(detail: VesselDetailInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFB0BEC5))
            .padding(1.dp) // creates a border effect
    ) {
        // 좌측: QC 레이블
        Box(
            modifier = Modifier
                .width(40.dp)
                // fillMaxHeight requires IntrinsicSize.Min on the parent Row
                .background(Color(0xFF0077C2))
        ) {
            Text(
                text = "QC",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(4.dp)
            )
        }

        // 우측: 데이터 영역
        Column(
            modifier = Modifier
                .weight(1f)
                .background(Color.White)
        ) {
            // 헤더 1계층
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "완료량",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF212121),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFFE3F2FD))
                        .padding(8.dp)
                )
                Text(
                    text = "잔여량",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF212121),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFFE1F5FE))
                        .padding(8.dp)
                )
            }

            // 헤더 2계층
            Row(modifier = Modifier.fillMaxWidth()) {
                // [\ucd5c\uc801\ud654] headerModifier \uc81c\uac70 \u2014 \uc778\ub77c\uc778 Modifier \uc9c1\uc811 \uc801\uc6a9
                val cellMod = Modifier.weight(1f).padding(4.dp)
                Text("\uc591\ud558", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = cellMod.background(Color(0xFFE3F2FD)))
                Text("\uc801\ud558", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = cellMod.background(Color(0xFFE3F2FD)))
                Text("\uc591\ud558", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = cellMod.background(Color(0xFFE1F5FE)))
                Text("\uc801\ud558", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = cellMod.background(Color(0xFFE1F5FE)))
            }

            // 행 데이터
            detail.qcList.forEach { qc ->
                val workload = qc.plannedDischarge + qc.plannedLoad + qc.completeDischarge + qc.completeLoad
                val complete = qc.completeDischarge + qc.completeLoad
                val remaining = qc.plannedDischarge + qc.plannedLoad

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${qc.craneNo}(총 작업량 : $workload)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFF212121),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFCFD8DC))
                            .padding(6.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF5F5F5))
                    ) {
                        Text(
                            text = complete.toString(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(8.dp)
                        )
                        VerticalDivider(modifier = Modifier.height(35.dp), color = Color(0xFFB0BEC5))
                        Text(
                            text = remaining.toString(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .padding(8.dp)
                        )
                    }
                    HorizontalDivider(color = Color(0xFFB0BEC5), thickness = 0.5.dp)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        val rowMod = Modifier
                            .weight(1f)
                            .padding(6.dp)
                        Text(qc.completeDischarge.toString(), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = rowMod)
                        VerticalDivider(modifier = Modifier.height(30.dp), thickness = 0.5.dp, color = Color(0xFFCFD8DC))
                        Text(qc.completeLoad.toString(), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = rowMod)
                        VerticalDivider(modifier = Modifier.height(30.dp), color = Color(0xFFB0BEC5))
                        Text(qc.plannedDischarge.toString(), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = rowMod)
                        VerticalDivider(modifier = Modifier.height(30.dp), thickness = 0.5.dp, color = Color(0xFFCFD8DC))
                        Text(qc.plannedLoad.toString(), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = rowMod)
                    }
                    HorizontalDivider(color = Color(0xFFB0BEC5), thickness = 0.5.dp)
                }
            }
        }
    }
}
