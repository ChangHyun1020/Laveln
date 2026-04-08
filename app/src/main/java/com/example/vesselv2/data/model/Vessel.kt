package com.example.vesselv2.data.model

/**
 * [데이터 모델] Vessel — Firebase Firestore에 저장되는 모선(라싱) 정보
 *
 * ▶ Firestore 구조:
 *   컬렉션: "Lashing" (COLLECTION 상수 참조)
 *   문서 ID: vesselName 대문자 (예: "OCEANBIRD")
 *
 * ▶ 저장 필드:
 *   - vesselName: 모선명 (대문자, 문서 ID와 동일)
 *   - date: 입항 예정일 (yyyy-MM-dd)
 *   - corn: 코너 캐스팅 타입 (예: "A", "B", "C")
 *   - row: 행(Row) 수
 *   - turnburckle: 턴버클 정보
 *   - floor: 층수
 *   - flan: 플랜지 타입
 *   - twin: 트윈 리프트 여부
 *   - Notes: 특이사항 메모
 *
 * ▶ id 필드:
 *   Firestore 문서 ID를 코드에서 사용하기 위해 추가된 필드입니다.
 *   기본값이 ""인 이유: Firestore의 toObject()가 기본 생성자를 호출할 때
 *   id는 Firestore 문서 ID이므로 자동 매핑되지 않아 수동으로 copy(id = doc.id)로 설정합니다.
 *
 * ⚠️ 보안:
 *   이 데이터 클래스는 항만 라싱(화물 고정) 장비 정보를 담습니다.
 *   외부에 공개해도 무방한 비민감 정보입니다.
 */
data class Vessel(
    val id: String = "",           // Firestore 문서 ID (= vesselName 대문자)
    val vesselName: String = "",   // 모선명
    val date: String = "",         // 입항 예정일
    val corn: String = "",         // 코너 캐스팅 타입
    val row: String = "",          // 행 수
    val turnburckle: String = "",  // 턴버클 정보
    val floor: String = "",        // 층수
    val flan: String = "",         // 플랜지 타입
    val twin: String = "",         // 트윈 리프트 여부
    val Notes: String = ""         // 특이사항 메모
) {
    companion object {
        /** Firestore 컬렉션명 — Constants.VESSEL_COLLECTION과 동일 */
        const val COLLECTION = "Lashing"
        /** 모선명 필드명 — 정렬/검색 쿼리에 사용 */
        const val FIELD_VESSEL_NAME = "vesselName"
    }

    /**
     * Firestore에 저장할 HashMap을 반환합니다.
     * AddVesselActivity에서 document.set(data)에 사용됩니다.
     *
     * @return Firestore 저장용 HashMap<String, Any>
     */
    fun toMap() = hashMapOf(
        "vesselName" to vesselName,
        "date" to date,
        "corn" to corn,
        "row" to row,
        "turnburckle" to turnburckle,
        "floor" to floor,
        "flan" to flan,
        "twin" to twin,
        "Notes" to Notes
    )
}
