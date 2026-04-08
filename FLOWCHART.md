# VesselV2 앱 실행 흐름도 (FLOWCHART)

> 제 3자가 한눈에 파악할 수 있도록 앱의 주요 기능별 실행 순서를 정리한 문서입니다.

---

## 📁 소스 파일 구조 요약

```
app/src/main/java/com/example/vesselv2/
│
├── ui/
│   ├── activity/           ← 각 화면 (Activity)
│   │   ├── MainActivity.kt             (메인: DGT 스케줄 그래프 + 리스트)
│   │   ├── FirebaseVesselActivity.kt   (Firebase 모선 목록)
│   │   ├── AddVesselActivity.kt        (모선 신규 등록)
│   │   ├── EditVesselActivity.kt       (모선 정보 수정)
│   │   ├── DetailActivity.kt           (모선 상세 + 사진 보기)
│   │   ├── AddPhotoActivity.kt         (사진 추가)
│   │   ├── MyWorkActivity.kt           (내 근무 기록)
│   │   ├── BulkCompressActivity.kt     (사진 일괄 압축 관리)
│   │   └── PhotoViewerActivity.kt      (사진 전체화면 보기)
│   │
│   ├── fragment/
│   │   └── VesselCombinedFragment.kt   (그래프 + 리스트 통합 프래그먼트)
│   │
│   ├── viewmodel/
│   │   └── VesselViewModel.kt          (MVVM 상태 관리)
│   │
│   ├── adapter/
│   │   ├── TimeCalAdapter.kt + TimeCalItem  (DGT 리스트 어댑터 + 데이터 모델)
│   │   ├── VesselAdapter.kt            (Firebase 모선 목록 어댑터)
│   │   ├── PhotoAdapter.kt             (사진 그리드 어댑터)
│   │   ├── ImagePreviewAdapter.kt      (사진 미리보기 어댑터)
│   │   └── MyWorkAdapter.kt            (근무 기록 어댑터)
│   │
│   └── view/
│       └── BerthScheduleView.kt        (선석 배정 현황 커스텀 Canvas 뷰)
│
├── data/
│   ├── remote/
│   │   └── DgtDataSource.kt            (DGT 웹 API 크롤링)
│   ├── repository/
│   │   └── VesselRepository.kt         (Firebase Firestore/Storage 접근)
│   ├── local/
│   │   ├── MyWorkDatabase.kt           (Room DB 설정)
│   │   ├── MyWorkDao.kt                (Room DB 쿼리)
│   │   └── MyWorkEntity.kt             (근무 기록 데이터 모델)
│   └── model/
│       ├── Vessel.kt                   (Firebase 모선 데이터 모델)
│       ├── VesselDetail.kt             (QcWorkInfo + VesselDetailInfo 모델)
│       └── VesselPhoto.kt              (사진 데이터 모델)
│
└── util/
    ├── Constants.kt                    (전역 상수)
    ├── NavigationHelper.kt             (사이드바 메뉴 헬퍼)
    ├── WorkCalculator.kt               (시급 계산기)
    ├── ImageCompressor.kt              (이미지 압축 유틸)
    ├── ViewExt.kt                      (View 확장 함수)
    └── WindowExt.kt                    (WindowInsets 확장 함수)
```

---

## 🔵 흐름도 1: 앱 시작 → DGT 스케줄 로드

```mermaid
flowchart TD
    A([앱 실행]) --> B[MainActivity.onCreate]
    B --> C[VesselViewModel 초기화]
    B --> D[VesselCombinedFragment 로드]
    B --> E[onResume 호출]
    E --> F["VesselViewModel.fetchDgtData()"]
    F --> G["IO 스레드\nDgtDataSource.ensureSession()"]
    G --> H{세션 유효?}
    H -- "아니오 (30분 초과)" --> I["DGT 페이지 GET\nhttps://info.dgtbusan.com"]
    H -- "예" --> J
    I --> I1[쿠키 + CSRF 토큰 캐싱]
    I1 --> J["DGT API POST\n/DGT/berth/vesselSchedule"]
    J --> K[JSON 응답 파싱\nconvertToTimeCalItem]
    K --> L[TimeCalItem 리스트 생성]
    L --> M[상태 정렬\nWORKING → BERTHED → PLANNED → DEPARTED]
    M --> N["Main 스레드\nVesselViewModel.setOriginalData()"]
    N --> O["applyDefaultFilter()\n오늘~7일 이내 필터"]
    O --> P["LiveData 업데이트\n_filteredList"]
    P --> Q["VesselCombinedFragment 관찰"]
    Q --> R[BerthScheduleView 그래프 렌더링]
    Q --> S[TimeCalAdapter 리스트 업데이트]
```

---

## 🟢 흐름도 2: 모선 추가 등록

```mermaid
flowchart TD
    A([메인 화면 FAB 클릭]) --> B[AddVesselActivity 시작]
    B --> C[입력 필드 표시\n모선명/코너/플랜지/층수 등]
    C --> D{DGT 리스트에서 왔는가?}
    D -- "예 (미등록 모선 감지)" --> E["Intent Extra로\n모선명 자동 입력"]
    D -- "아니오" --> F[수동 입력]
    E --> G
    F --> G[갤러리에서 사진 선택\n최대 4장]
    G --> H{저장 버튼 클릭}
    H --> I["Firestore에 문서 저장\nConstants.VESSEL_COLLECTION\n문서ID = 모선명 대문자"]
    I --> J{사진이 있는가?}
    J -- "있음" --> K["ImageCompressor로 압축\n(JPEG 품질 최적화)"]
    K --> L["Firebase Storage 업로드\nimages/{vesselName}/{vesselName}_00.jpg"]
    L --> M{전체 업로드 완료?}
    M -- "완료" --> N[토스트 성공 메시지\nRESULT_OK 반환]
    J -- "없음" --> N
    N --> O([이전 화면으로 복귀])
```

---

## 🟡 흐름도 3: 모선 상세 보기 → 수정/삭제

```mermaid
flowchart TD
    A([FirebaseVesselActivity 모선 카드 클릭]) --> B[DetailActivity 시작\nvesselName / vesselDocId 전달]
    B --> C["Firestore.get(Source.SERVER)\nConstants.VESSEL_COLLECTION\n/vesselName"]
    C --> D[bindVesselFields\n화면에 정보 표시]
    D --> E["Storage.listAll()\nimages/vesselName/"]
    E --> F[downloadUrl 병렬 조회]
    F --> G[PhotoAdapter 사진 그리드 표시]

    G --> H{FAB 팝업 선택}
    H -- "수정" --> I["EditVesselActivity 시작\ncachedDocument 데이터 전달"]
    I --> J[입력 필드에 기존 값 표시]
    J --> K{수정 버튼 클릭}
    K --> L["Firestore.update()\nConstants.VESSEL_COLLECTION/vesselName"]
    L --> M[RESULT_OK → DetailActivity 재로드]

    H -- "삭제" --> N[삭제 확인 다이얼로그]
    N -- "확인" --> O["Storage 사진 전체 삭제\nimages/vesselName/ 폴더"]
    O --> P["Firestore 문서 삭제\nConstants.VESSEL_COLLECTION/vesselName"]
    P --> Q[RESULT_OK → 목록 화면 갱신]
```

---

## 🔴 흐름도 4: 선석 그래프 → QC 작업 현황 다이얼로그

```mermaid
flowchart TD
    A([BerthScheduleView에서 선박 바 터치]) --> B["onItemClickListener 콜백\nTimeCalItem 전달"]
    B --> C["VesselViewModel\n.fetchVesselWorkStatus(item)"]
    C --> D["IO 스레드\nDgtDataSource.fetchVesselDetails(item)"]
    D --> E["선석 스케줄 재조회\n-5일 ~ +15일 범위"]
    E --> F{선박 매칭}
    F -- "vesselCode+voyageSeq+voyageYear 일치" --> G[정확한 항차 타겟 확정]
    F -- "이름 부분 일치 (Fallback)" --> H[ETB 가장 가까운 항차 선택]
    G --> I
    H --> I["QC 작업 현황 API 호출\n/DGT/document/vesselContainer\nvesselCode + voyage 전달"]
    I --> J["컨테이너 목록 수신\npsituation + disLoad 집계"]
    J --> K[크레인별 QcWorkInfo 집계]
    K --> L["Main 스레드\nVesselDetailInfo 구성"]
    L --> M["LiveData 업데이트\n_vesselDetail"]
    M --> N["VesselCombinedFragment 관찰"]
    N --> O["showQcStatusDialog(detail)"]
    O --> P[QC 현황 다이얼로그 표시\n크레인별 완료/잔여 양하/적하]
```

---

## 🟣 흐름도 5: 내 근무 기록 관리 (MyWorkActivity)

```mermaid
flowchart TD
    A([사이드바 메뉴 진입]) --> B[MyWorkActivity 시작]
    B --> C["Room DB 조회\nMyWorkDatabase.getWorkByMonth(month)"]
    C --> D["Flow 수신 → Coroutines collectLatest"]
    D --> E[MyWorkAdapter 근무 목록 표시]
    E --> F[월별 총 근무 시간 합산 표시]

    E --> G{항목 편집 클릭}
    G --> H[DatePickerDialog\n날짜 선택]
    H --> I[TimePickerDialog\n시작 시간 선택]
    I --> J[TimePickerDialog\n종료 시간 선택]
    J --> K["WorkCalculator.calculate()\n시간대별 시급 계산"]
    K --> L["Room DB 저장\nMyWorkDao.insertWork()"]
    L --> M["Flow 자동 갱신\nUI 업데이트"]

    E --> N{항목 삭제 클릭}
    N --> O[삭제 확인 다이얼로그]
    O --> P["Room DB 삭제\nMyWorkDao.deleteWork()"]
    P --> M
```

---

## 🔷 흐름도 6: MVVM 구조 데이터 흐름 개요

```mermaid
flowchart LR
    subgraph View ["🖥️ View 계층 (UI)"]
        MA[MainActivity]
        FA[FirebaseVesselActivity]
        DA[DetailActivity]
        FR[VesselCombinedFragment]
        BSV[BerthScheduleView]
    end

    subgraph VM ["⚙️ ViewModel 계층"]
        VVM[VesselViewModel]
    end

    subgraph Data ["📦 Data 계층"]
        REPO[VesselRepository]
        DGT[DgtDataSource]
        DB[MyWorkDatabase\nRoom]
    end

    subgraph Firebase ["☁️ Firebase"]
        FS[(Firestore\nLashing 컬렉션)]
        ST[(Storage\nimages/)]
    end

    subgraph DgtServer ["🌐 DGT 서버"]
        API[info.dgtbusan.com\nAPI]
    end

    MA & FA & DA & FR --> VVM
    FR --> BSV
    VVM --> REPO
    VVM --> DGT
    REPO --> FS
    REPO --> ST
    DGT --> API
    VVM -.->|LiveData 관찰| MA & FA & DA & FR
```

---

## 🔐 보안 요소 요약

| 항목 | 처리 방식 |
|------|---------|
| `google-services.json` (Firebase 키) | `.gitignore`에 포함 → Git 업로드 안 됨 |
| `local.properties` (SDK 경로) | `.gitignore`에 포함 → Git 업로드 안 됨 |
| `erm.jpg` (비상 연락망 이미지) | `.gitignore`에 포함 → Git 업로드 안 됨 |
| Firestore 컬렉션명 | `Constants.VESSEL_COLLECTION` 상수 사용 (하드코딩 없음) |
| DGT API CSRF 토큰 | 로그에 앞 10자만 출력 (전체 노출 방지) |
| SSL 인증서 우회 | 내부망 전용 → 경고 주석으로 이유 문서화 |
| DGT 로그인 정보 | 사용하지 않음 (세션 쿠키 방식만 사용) |
