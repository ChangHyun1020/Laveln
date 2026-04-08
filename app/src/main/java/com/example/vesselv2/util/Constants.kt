package com.example.vesselv2.util

/**
 * [전역 상수 모음] Constants
 *
 * 앱 전체에서 공통으로 사용하는 문자열 상수를 한 곳에서 관리합니다.
 * 하드코딩을 방지하여 오타·불일치 버그를 사전에 예방하고,
 * 값 변경 시 이 파일 하나만 수정하면 전체에 반영됩니다.
 *
 * ⚠️ 보안 참고:
 *   - Firebase 프로젝트 설정(google-services.json)은 .gitignore에 의해
 *     Git에 포함되지 않습니다. 절대 커밋하지 마세요.
 *   - VESSEL_COLLECTION은 Firestore 컬렉션명이므로 외부에 노출되어도
 *     Firebase Security Rules로 접근을 제어합니다.
 */
object Constants {

    // ── Firebase Firestore 컬렉션명 ─────────────────────────────────────
    /** Firestore에서 모선(라싱) 데이터를 저장하는 컬렉션명 */
    const val VESSEL_COLLECTION = "Lashing"

    // ── Firestore 필드명 ────────────────────────────────────────────────
    /** Firestore 문서 내 모선명 필드 키 */
    const val FIELD_VESSEL_NAME = "vesselName"

    // ── Intent Extra 키 ─────────────────────────────────────────────────
    /**
     * AddVesselActivity로 전달할 때 사용하는 모선명 사전 입력 키
     * (DGT 목록에서 미등록 선박을 등록할 때 이름을 자동 입력해주기 위해 사용)
     */
    const val EXTRA_PREFILL_VESSEL_NAME = "EXTRA_PREFILL_VESSEL_NAME"
}
