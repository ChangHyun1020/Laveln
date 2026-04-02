package com.example.vesselv2.data.model

/**
 * [MODEL] VesselPhoto - 선박 현장 사진 데이터 모델
 */
data class VesselPhoto(
    val storagePath: String = "",  // Storage 전체 경로
    val fileName: String = "",     // 파일명
    val imageUrl: String = ""      // HTTPS 다운로드 URL
)
