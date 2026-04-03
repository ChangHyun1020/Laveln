# vesselV2 - 선박 래싱 사진 관리 앱
## Kotlin / JDK 17 / Android Studio Panda / Firebase

---

## 📱 앱 구조 (실제 Firebase 기준)

```
Fb
└── La/                    ← 기존 컬렉션 (변경 없음)
    ├── example          ← 문서 (기존 필드 그대로)
    │   ├── Notes: "..."
    │   ├── corn: "dx"
    │   ├── date: "2024-02-22"
    │   ├── flan: "AB"
    │   ├── floor: "1"
    │   ├── row: "15"
    │   ├── turnburckle: "tt"
    │   ├── twin: "tt"
    │   ├── vesselName: "tt"
    │   └── 📁 photos/           ← ✨ 새로 추가되는 서브컬렉션
    │       ├── {photoId}
    │       │   ├── imageUrl: "https://..."
    │       │   ├── memo: "6bay 현장"
    │       │   └── createdAt: 1234567890
    │       └── ...
    ├── ALS LUNA
    └── ...

fs
└── lp/
    └── example/
        ├── uuid1.jpg
        └── uuid2.jpg
```

---

## ✅ 적용 방법

### STEP 1 — 프로젝트 열기
1. ZIP 압축 해제
2. Android Studio → `File → Open` → `vesselV2` 폴더 선택
3. Gradle Sync 완료까지 대기

### STEP 2 — google-services.json 교체 ⚠️ 필수
1. Firebase 콘솔 → 프로젝트: **lashingstoragetest**
2. 프로젝트 설정(⚙️) → 앱 탭 → **google-services.json 다운로드**
3. `vesselV2/app/google-services.json` 파일 **덮어쓰기**

> 패키지명 `com.example.vesselv2` 으로 Android 앱이 등록되어 있어야 합니다.
> 없다면: Firebase 콘솔 → Android 앱 추가 → 패키지명 입력

### STEP 3 — Firebase Storage 활성화
Firebase 콘솔 → **Storage** → 시작하기 → 테스트 모드

### STEP 4 — 실행
`File → Sync Project with Gradle Files` → ▶ Run

---

## 🔥 Firestore Security Rules (Storage)
```
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /lashing_photos/{vesselName}/{imageFile} {
      allow read: if true;
      allow write: if request.auth != null
                   && request.resource.size < 10 * 1024 * 1024
                   && request.resource.contentType.matches('image/.*');
    }
  }
}
```

---

## ❓ 자주 발생하는 오류

| 오류 | 원인 | 해결 |
|------|------|------|
| 선박 목록 안 불러와짐 | google-services.json 미교체 | STEP 2 반복 |
| 사진 업로드 실패 | Storage 미활성화 | STEP 3 확인 |
| `Default FirebaseApp not initialized` | 패키지명 불일치 | Firebase 앱 등록 패키지명 확인 |

---

## 🗂️ 버전 관리 (Git) 가이드

### 📌 초기 설정 (최초 1회)
```bash
# 1. 프로젝트 폴더에서 Git 초기화
git init

# 2. 원격 저장소 연결 (GitHub/GitLab URL를 본인 저장소로 교체)
git remote add origin https://github.com/[계정명]/VesselV2.git

# 3. 첫 커밋
git add .
git commit -m "chore: 초기 프로젝트 커밋 (v1.0)"
git push -u origin main
```

### 🌿 브랜치 전략 (Feature Branch)
```
main        ← 배포 가능한 안정 버전 (직접 push 금지)
  └── develop      ← 개발 통합 브랜치
        ├── feature/ui-vessel-card   ← 기능 개발
        ├── feature/date-filter      ← 기능 개발
        └── fix/time-display-bug     ← 버그 수정
```

```bash
# 새 기능 개발 시
git checkout -b feature/새기능이름

# 개발 완료 후 develop 에 합치기
git checkout develop
git merge --no-ff feature/새기능이름
git branch -d feature/새기능이름
```

### 📝 커밋 메시지 규칙
| 접두어 | 용도 | 예시 |
|--------|------|------|
| `feat:` | 새 기능 추가 | `feat: 날짜 범위 필터 추가` |
| `fix:` | 버그 수정 | `fix: 시간 표시 오류 수정` |
| `ui:` | UI/UX 변경 | `ui: 입항 카드 레이아웃 개선` |
| `refactor:` | 코드 리팩토링 | `refactor: VesselAdapter 구조 정리` |
| `chore:` | 빌드/설정 변경 | `chore: .gitignore 업데이트` |
| `docs:` | 문서 수정 | `docs: README 가이드 추가` |

### 🔄 일반 개발 흐름
```bash
# 현재 상태 확인
git status
git log --oneline -10

# 변경 사항 저장
git add .
git commit -m "feat: 기능 설명"

# 원격 저장소에 올리기
git push origin [브랜치명]

# 최신 코드 내려받기
git pull origin [브랜치명]
```

### ⚠️ 주의사항
- `google-services.json`, `local.properties` 는 `.gitignore`에 등록되어 있어 **절대 커밋되지 않음**
- 새 기기에서 클론 후 위 파일들을 **수동으로 복사** 해야 함
- `build/` 폴더는 자동 제외됨 (크기가 매우 크므로 절대 커밋 금지)

# Laveln
