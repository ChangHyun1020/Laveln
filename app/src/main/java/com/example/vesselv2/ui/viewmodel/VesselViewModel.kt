package com.example.vesselv2.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vesselv2.data.model.Vessel
import com.example.vesselv2.data.model.VesselDetailInfo
import com.example.vesselv2.data.remote.DgtDataSource
import com.example.vesselv2.data.repository.VesselRepository
import com.example.vesselv2.ui.adapter.TimeCalItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * [ViewModel] VesselViewModel — 앱 전역 상태 관리 (MVVM 패턴)
 *
 * ▶ 역할:
 *   화면 전환(Configuration Change)에도 데이터가 유지되도록 ViewModel에서 상태를 관리합니다.
 *   화면(View)과 데이터(Repository/DataSource) 사이의 중간 계층 역할을 합니다.
 *
 * ▶ 공유 범위:
 *   - MainActivity + VesselCombinedFragment: activityViewModels()로 공유
 *   - FirebaseVesselActivity: 독립적 viewModels()
 *   - DetailActivity, AddPhotoActivity, BulkCompressActivity: 독립적 viewModels()
 *
 * ▶ 주요 LiveData:
 *   [Firebase 모선 목록]
 *   - vessels: 전체 모선 목록 (Firestore 원본)
 *   - filteredVessels: 검색어 적용된 목록 (FirebaseVesselActivity 표시용)
 *   - firebaseSearchQuery: 현재 검색어
 *
 *   [DGT 스케줄 그래프/리스트]
 *   - filteredList: 필터 적용된 TimeCalItem 목록 (그래프+리스트 동시 사용)
 *   - graphStartMs: 그래프 시작 기준 시각 (KST 00:00:00)
 *   - vesselDetail: 선박 상세+QC 현황 (그래프 클릭 시 로드)
 *
 *   [공통]
 *   - isLoading: 로딩 상태 (ProgressBar 제어)
 *   - uiEvent: 성공/오류 토스트 이벤트 (단발성)
 *
 * ▶ 코루틴 사용:
 *   - viewModelScope: ViewModel 생명주기에 자동으로 취소되는 코루틴 스코프
 *   - IO 스레드: 네트워크/DB 작업
 *   - Main 스레드: LiveData 업데이트
 */
class VesselViewModel : ViewModel() {

    // ── 의존성 주입 ──────────────────────────────────────────────────────────
    /** Firebase 데이터 접근 레이어 */
    private val repository = VesselRepository()

    /** DGT 웹 스크래핑/API 호출 레이어 */
    private val dgtDataSource = DgtDataSource()

    // ── Firebase 모선 목록 상태 ──────────────────────────────────────────────

    /** Firestore에서 조회한 전체 모선 목록 (검색 필터 적용 전 원본) */
    private val _vessels = MutableLiveData<List<Vessel>>(emptyList())
    val vessels: LiveData<List<Vessel>> = _vessels

    /** 검색어가 적용된 모선 목록 (FirebaseVesselActivity RecyclerView에 표시) */
    private val _filteredVessels = MutableLiveData<List<Vessel>>(emptyList())
    val filteredVessels: LiveData<List<Vessel>> = _filteredVessels

    /** 현재 비파이어베이스 검색어 — 변경 시 applyFirebaseFilter() 자동 호출 */
    private val _firebaseSearchQuery = MutableLiveData("")
    val firebaseSearchQuery: LiveData<String> = _firebaseSearchQuery

    // ── DGT 스케줄 상태 ─────────────────────────────────────────────────────

    /**
     * DGT API에서 받은 전체 TimeCalItem 목록 (필터 적용 전 원본)
     * private: 외부에서 직접 수정 불가, getOriginalList()로 읽기만 허용
     */
    private val _originalList = MutableLiveData<List<TimeCalItem>>(emptyList())

    /** 상태/날짜 필터가 적용된 TimeCalItem 목록 (그래프 + 리스트 동시 사용) */
    private val _filteredList = MutableLiveData<List<TimeCalItem>>(emptyList())
    val filteredList: LiveData<List<TimeCalItem>> = _filteredList

    /** 그래프 시작 기준 시각 (KST 00:00:00) — BerthScheduleView의 x축 시작점 */
    private val _graphStartMs = MutableLiveData<Long?>(null)
    val graphStartMs: LiveData<Long?> = _graphStartMs

    // ── 공통 상태 ───────────────────────────────────────────────────────────

    /** 전역 로딩 상태 — true이면 ProgressBar 표시 */
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    /**
     * 선박 상세 정보 (그래프에서 선박 클릭 시 채워짐)
     * null로 초기화, 다이얼로그 표시 후 다시 null로 초기화 (재클릭 대비)
     */
    private val _vesselDetail = MutableLiveData<VesselDetailInfo?>()
    val vesselDetail: LiveData<VesselDetailInfo?> = _vesselDetail

    /** DGT 리스트용 검색어 (현재 비활성화 — VesselCombinedFragment 검색에서 사용) */
    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    /**
     * UI 단발성 이벤트 — 성공/오류 토스트 메시지
     * ※ 향후 SingleLiveEvent 또는 SharedFlow로 개선 권고
     *   (현재 구조에서는 화면 복귀 시 중복 발생 가능성 있음)
     */
    private val _uiEvent = MutableLiveData<UiEvent>()
    val uiEvent: LiveData<UiEvent> = _uiEvent

    // ── 일괄 압축 진행률 상태 (BulkCompressActivity) ──────────────────────

    /** 일괄 압축 전체 진행률: Triple(현재 폴더, 전체 폴더 수, 현재 모선명) */
    private val _bulkProgress = MutableLiveData<Triple<Int, Int, String>>()
    val bulkProgress: LiveData<Triple<Int, Int, String>> = _bulkProgress

    /** 개별 폴더 내 파일 진행률: Pair(현재 파일 수, 전체 파일 수) */
    private val _compressProgress = MutableLiveData<Pair<Int, Int>>()
    val compressProgress: LiveData<Pair<Int, Int>> = _compressProgress

    /**
     * UI 이벤트 타입 정의 (sealed class)
     * - Success: 작업 성공 메시지
     * - Error: 오류 메시지
     */
    sealed class UiEvent {
        data class Success(val message: String) : UiEvent()
        data class Error(val message: String) : UiEvent()
    }

    // ── 수동 날짜 필터 (선택적 사용) ────────────────────────────────────────
    /** 수동 필터 시작일 (null이면 기본 필터 적용) */
    var filterStartDateMs: Long? = null
    /** 수동 필터 종료일 (null이면 기본 필터 적용) */
    var filterEndDateMs: Long? = null

    // ────────────────────────────────────────────────────────────────────────
    //  공통 헬퍼 함수
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 로딩 상태를 업데이트합니다.
     * postValue를 사용하여 IO 스레드에서도 안전하게 호출 가능합니다.
     */
    fun setLoading(loading: Boolean) {
        _isLoading.postValue(loading)
    }

    /**
     * 선박 상세 정보를 업데이트합니다.
     * VesselCombinedFragment에서 QC 다이얼로그를 표시한 후 null로 초기화하는 데 사용합니다.
     */
    fun setVesselDetail(detail: VesselDetailInfo?) {
        _vesselDetail.postValue(detail)
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Firebase 모선 목록 관련
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Firestore에서 모선 목록을 조회합니다. (IO 스레드)
     *
     * 조회 완료 후 applyFirebaseFilter()를 호출하여 현재 검색어에 맞게 필터링합니다.
     * FirebaseVesselActivity가 처음 실행되거나 삭제 후 목록 갱신 시 호출됩니다.
     */
    fun loadVessels() {
        viewModelScope.launch {
            setLoading(true)
            val list = repository.getVessels() // IO 스레드 → suspend 함수
            _vessels.value = list
            applyFirebaseFilter()
            setLoading(false)
        }
    }

    /**
     * 검색어를 업데이트하고 필터를 재적용합니다.
     * SearchView 텍스트 변경 시 호출됩니다.
     *
     * @param query 검색어 (대/소문자 무관 — 내부에서 uppercase 처리)
     */
    fun setFirebaseSearchQuery(query: String) {
        _firebaseSearchQuery.value = query
        applyFirebaseFilter()
    }

    /**
     * 현재 검색어를 기준으로 모선 목록을 필터링합니다.
     * - 검색어 비어 있음: 전체 목록 표시
     * - 검색어 있음: vesselName 또는 corn(코너 캐스팅)에 포함된 모선만 표시
     */
    private fun applyFirebaseFilter() {
        val query = _firebaseSearchQuery.value?.trim()?.uppercase() ?: ""
        val source = _vessels.value ?: return

        _filteredVessels.value = if (query.isEmpty()) {
            source
        } else {
            source.filter {
                it.vesselName.uppercase().contains(query) || it.corn.uppercase().contains(query)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  사진 업로드 (AddPhotoActivity)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Firebase Storage에 사진을 업로드합니다.
     *
     * 업로드 전 ImageCompressor로 이미지를 압축하여 저장 용량을 절감합니다.
     * 성공/실패 결과는 uiEvent LiveData로 전달합니다.
     *
     * @param context 이미지 압축에 필요한 Context
     * @param vesselName Storage 폴더명 (= 모선명 대문자)
     * @param uri 선택된 이미지 URI
     */
    fun addPhoto(context: Context, vesselName: String, uri: Uri) {
        viewModelScope.launch {
            setLoading(true)
            val downloadUrl = repository.uploadPhoto(context, vesselName, uri)
            setLoading(false)
            if (downloadUrl != null) {
                _uiEvent.value = UiEvent.Success("사진이 업로드되었습니다.")
            } else {
                _uiEvent.value = UiEvent.Error("업로드에 실패했습니다.")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  일괄 사진 압축 (BulkCompressActivity)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Firebase Storage의 images/ 경로에 있는 모든 모선 사진을 일괄 압축합니다.
     *
     * 압축 진행 상황은 bulkProgress, compressProgress LiveData를 통해
     * BulkCompressActivity에 실시간으로 전달됩니다.
     *
     * ⚠️ 주의: 이 작업은 시간이 오래 걸리며, 중단 시 일부 사진만 압축될 수 있습니다.
     */
    fun compressAllPhotos() {
        viewModelScope.launch {
            setLoading(true)
            val folders = repository.getAllVesselFolderNames()
            val totalFolders = folders.size

            if (totalFolders == 0) {
                setLoading(false)
                _uiEvent.value = UiEvent.Success("압축할 대상 폴더가 없습니다.")
                return@launch
            }

            // 폴더(모선)별 순서대로 압축 진행
            folders.forEachIndexed { index, folderName ->
                // 전체 진행률 업데이트
                _bulkProgress.value = Triple(index + 1, totalFolders, folderName)
                // 해당 모선의 파일별 압축 진행률 콜백
                repository.compressExistingPhotos(folderName) { current, total ->
                    _compressProgress.value = Pair(current, total)
                }
            }

            setLoading(false)
            _uiEvent.value = UiEvent.Success("모든 사진 최적화 작업이 완료되었습니다.")
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  DGT 스케줄 필터링 (TimeCalItem)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * DGT API에서 받은 전체 원본 데이터를 저장하고 기본 필터를 적용합니다.
     * fetchDgtData() 호출 완료 후 메인 스레드에서 호출됩니다.
     *
     * @param items DGT API에서 파싱된 TimeCalItem 전체 목록
     */
    fun setOriginalData(items: List<TimeCalItem>) {
        _originalList.value = items
        applyDefaultFilter() // 기본 필터(오늘~7일 이내) 즉시 적용
    }

    /** DGT 원본 데이터 반환 (수동 필터 적용 전 전체 목록) */
    fun getOriginalList(): List<TimeCalItem> = _originalList.value ?: emptyList()

    /**
     * 수동 날짜 필터를 적용합니다.
     * filterStartDateMs, filterEndDateMs, searchQuery를 조합하여 필터링합니다.
     */
    fun applyManualFilter() {
        val start = filterStartDateMs
        val end = filterEndDateMs
        val query = _searchQuery.value?.trim()?.uppercase() ?: ""
        val source = _originalList.value ?: return

        val filtered = source.filter { item ->
            val etbMs = item.etbDateMs
            val matchStart = start?.let { etbMs >= it } ?: true
            val matchEnd = end?.let { etbMs <= it } ?: true
            val matchSearch = if (query.isEmpty()) true else item.vesselName.uppercase().contains(query)
            matchStart && matchEnd && matchSearch
        }
        _filteredList.value = filtered
        _graphStartMs.value = start
    }

    /**
     * DGT 리스트 검색어를 업데이트하고 필터를 재적용합니다.
     *
     * @param query 검색어
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyManualFilter()
    }

    /**
     * 기본 필터를 적용합니다.
     *
     * ▶ 기본 필터 조건:
     *   - WORKING (현재 작업 중): 항상 포함
     *   - BERTHED (접안 완료): 항상 포함
     *   - PLANNED (입항 예정): ETB가 오늘부터 7일 이내인 경우만 포함
     *   - DEPARTED (출항 완료): 제외
     *
     * 수동 필터가 설정된 경우 applyManualFilter()로 위임합니다.
     */
    fun applyDefaultFilter() {
        // 수동 필터가 설정된 경우 기본 필터 대신 수동 필터 적용
        if (filterStartDateMs != null || filterEndDateMs != null) {
            applyManualFilter()
            return
        }

        val kst = TimeZone.getTimeZone("Asia/Seoul")
        // 오늘 KST 00:00:00
        val todayStart = Calendar.getInstance(kst).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // 오늘 + 7일 KST 23:59:59
        val weekEnd = Calendar.getInstance(kst).apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, 7)
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }.timeInMillis

        val source = _originalList.value ?: return
        val filtered = source.filter { item ->
            // 작업 중/접안 중은 항상 표시, 예정은 7일 이내만 표시
            item.vesselStatus == "WORKING" || item.vesselStatus == "BERTHED" ||
                    (item.vesselStatus == "PLANNED" && item.etbDateMs in todayStart..weekEnd)
        }.sortedBy { it.etbDateMs } // ETB 오름차순 정렬

        _filteredList.value = filtered
        _graphStartMs.value = todayStart
    }

    /**
     * 모든 필터를 초기화하고 기본 필터를 재적용합니다.
     */
    fun resetFilter() {
        filterStartDateMs = null
        filterEndDateMs = null
        applyDefaultFilter()
    }

    // ────────────────────────────────────────────────────────────────────────
    //  DGT 데이터 크롤링
    // ────────────────────────────────────────────────────────────────────────

    /**
     * DGT API에서 선석 스케줄 데이터를 비동기로 가져옵니다.
     *
     * ▶ 실행 흐름:
     *   IO 스레드: DgtDataSource.fetchBerthSchedules() 호출
     *   → 상태 정렬 (WORKING > BERTHED > PLANNED > DEPARTED)
     *   → Main 스레드: setOriginalData() → applyDefaultFilter() → UI 업데이트
     *
     * ▶ 호출 시점:
     *   - MainActivity.onResume(): 화면 복귀 시마다 자동 갱신
     *   - VesselCombinedFragment swipe-to-refresh: 사용자 수동 갱신
     *
     * ⚠️ DgtDataSource는 내부적으로 네트워크 요청을 수행하므로 반드시 IO 스레드에서 실행
     */
    fun fetchDgtData() {
        setLoading(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // KST 기준 오늘 ~ +7일 범위로 스케줄 조회
                val kstZone = TimeZone.getTimeZone("Asia/Seoul")
                val cal = Calendar.getInstance(kstZone)
                val sdfParam = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = kstZone }
                val fromDate = sdfParam.format(cal.time)
                cal.add(Calendar.DAY_OF_YEAR, 7)
                val toDate = sdfParam.format(cal.time)

                val newItems = dgtDataSource.fetchBerthSchedules(fromDate, toDate).toMutableList()
                Log.d("VesselViewModel", "fetchDgtData: ${newItems.size}개 수신")

                if (newItems.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiEvent.value = UiEvent.Error("선박 스케줄을 불러오지 못했거나 데이터가 없습니다.")
                    }
                }

                // 상태 우선순위 + ETB 오름차순 정렬
                newItems.sortWith(compareBy<TimeCalItem> {
                    when (it.vesselStatus) {
                        "WORKING" -> 0  // 최상단: 현재 작업 중
                        "BERTHED" -> 1  // 접안 완료
                        "PLANNED" -> 2  // 입항 예정
                        "DEPARTED" -> 3 // 출항 완료 (최하단)
                        else -> 4
                    }
                }.thenBy { it.etbDateMs }) // 같은 상태 내에서 ETB 빠른 순

                withContext(Dispatchers.Main) {
                    setLoading(false)
                    setOriginalData(newItems)
                    Log.d("VesselViewModel", "fetchDgtData: LiveData 업데이트 완료")
                }
            } catch (e: Exception) {
                Log.e("VesselViewModel", "fetchDgtData 오류", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    _uiEvent.value = UiEvent.Error("데이터 처리 중 오류가 발생했습니다: ${e.message}")
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  선박 상세 작업 현황 조회 (그래프 클릭 → QC 다이얼로그)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 그래프에서 특정 선박을 클릭했을 때 실시간 QC 작업 현황을 조회합니다.
     *
     * ▶ 실행 흐름:
     *   IO 스레드: DgtDataSource.fetchVesselDetails() 호출 (선박 코드 기반 매칭 + QC 조회)
     *   → Main 스레드: VesselDetailInfo 구성 → vesselDetail LiveData 업데이트
     *   → VesselCombinedFragment: vesselDetail 관찰 → QC 다이얼로그 표시
     *
     * ▶ 중복 호출 방지: isLoading이 true인 경우 새 요청 무시
     *
     * @param item 클릭된 선박의 TimeCalItem (vesselCode, voyageSeq, voyageYear 포함)
     */
    fun fetchVesselWorkStatus(item: TimeCalItem) {
        // 이미 로딩 중이면 중복 호출 방지
        if (_isLoading.value == true) return
        setLoading(true)
        setVesselDetail(null) // 이전 결과 초기화

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = dgtDataSource.fetchVesselDetails(item)
                if (result != null) {
                    val (obj, qcList) = result
                    // DGT 원시 JSON에서 필요한 필드 추출하여 VesselDetailInfo 구성
                    val detailInfo = VesselDetailInfo(
                        item = item,
                        disQty = obj.optString("dischargeQty", "0"),    // 양하 수량
                        lodQty = obj.optString("loadQty", "0"),          // 적하 수량
                        shftQty = obj.optString("shiftQty", "0"),        // 이동(Shift) 수량
                        statusStr = obj.optString("status"),
                        qcList = qcList
                    )
                    withContext(Dispatchers.Main) {
                        Log.d("VesselViewModel", "QC 현황 조회 성공: ${item.vesselName}, QC ${qcList.size}개")
                        setVesselDetail(detailInfo)
                        setLoading(false)
                    }
                } else {
                    Log.w("VesselViewModel", "QC 현황 조회 실패: ${item.vesselName}")
                    withContext(Dispatchers.Main) { setLoading(false) }
                }
            } catch (e: Exception) {
                Log.e("VesselViewModel", "fetchVesselWorkStatus 오류", e)
                withContext(Dispatchers.Main) { setLoading(false) }
            }
        }
    }
}
