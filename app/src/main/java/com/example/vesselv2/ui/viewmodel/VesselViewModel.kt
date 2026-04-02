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
 * Vessel 관련 통합 ViewModel.
 * MainActivity, DetailActivity, AddPhotoActivity, BulkCompressActivity 및
 * TimeCal 관련 프래그먼트에서 공유하거나 각각 사용됨.
 */
class VesselViewModel : ViewModel() {

    private val repository = VesselRepository()
    private val dgtDataSource = DgtDataSource()

    // ── Vessel 리스트 (Firebase) ─────────────────────────────────────────
    private val _vessels = MutableLiveData<List<Vessel>>(emptyList())
    val vessels: LiveData<List<Vessel>> = _vessels

    private val _filteredVessels = MutableLiveData<List<Vessel>>(emptyList())
    val filteredVessels: LiveData<List<Vessel>> = _filteredVessels

    private val _firebaseSearchQuery = MutableLiveData("")
    val firebaseSearchQuery: LiveData<String> = _firebaseSearchQuery

    // ── 원본 데이터 (TimeCal용) ─────────────────────────────────────────────
    private val _originalList = MutableLiveData<List<TimeCalItem>>(emptyList())

    // ── 필터 결과 (TimeCal UI 표시용) ────────────────────────────────────────
    private val _filteredList = MutableLiveData<List<TimeCalItem>>(emptyList())
    val filteredList: LiveData<List<TimeCalItem>> = _filteredList

    // ── 그래프 기준 시각 (TimeCal) ───────────────────────────────────────────
    private val _graphStartMs = MutableLiveData<Long?>(null)
    val graphStartMs: LiveData<Long?> = _graphStartMs

    // ── 로딩 상태 ────────────────────────────────────────────────────────────
    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // ── 상세 정보 상태 (TimeCal UI 하단뷰용) ──────────────────────────────
    private val _vesselDetail = MutableLiveData<VesselDetailInfo?>()
    val vesselDetail: LiveData<VesselDetailInfo?> = _vesselDetail

    // ── 검색어 필터링 ──────────────────────────────────────────────────────
    private val _searchQuery = MutableLiveData("")
    val searchQuery: LiveData<String> = _searchQuery

    // ── UI 이벤트 (성공/실패 알림) ──────────────────────────────────────────
    private val _uiEvent = MutableLiveData<UiEvent>()
    val uiEvent: LiveData<UiEvent> = _uiEvent

    // ── 일괄 압축 진행률 (BulkCompressActivity) ──────────────────────────────
    private val _bulkProgress = MutableLiveData<Triple<Int, Int, String>>()
    val bulkProgress: LiveData<Triple<Int, Int, String>> = _bulkProgress

    private val _compressProgress = MutableLiveData<Pair<Int, Int>>()
    val compressProgress: LiveData<Pair<Int, Int>> = _compressProgress

    sealed class UiEvent {
        data class Success(val message: String) : UiEvent()
        data class Error(val message: String) : UiEvent()
    }

    // ── 수동 날짜 필터 상태 (TimeCal) ────────────────────────────────────────
    var filterStartDateMs: Long? = null
    var filterEndDateMs: Long? = null

    // ────────────────────────────────────────────────────────────────────────

    fun setLoading(loading: Boolean) {
        _isLoading.postValue(loading)
    }

    fun setVesselDetail(detail: VesselDetailInfo?) {
        _vesselDetail.postValue(detail)
    }

    /**
     * Firebase: 모선 목록 로드
     */
    fun loadVessels() {
        viewModelScope.launch {
            setLoading(true)
            val list = repository.getVessels()
            _vessels.value = list
            applyFirebaseFilter()
            setLoading(false)
        }
    }

    fun setFirebaseSearchQuery(query: String) {
        _firebaseSearchQuery.value = query
        applyFirebaseFilter()
    }

    private fun applyFirebaseFilter() {
        val query = _firebaseSearchQuery.value?.trim()?.uppercase() ?: ""
        val source = _vessels.value ?: return

        if (query.isEmpty()) {
            _filteredVessels.value = source
        } else {
            _filteredVessels.value = source.filter {
                it.vesselName.uppercase().contains(query) || it.corn.uppercase().contains(query)
            }
        }
    }

    /**
     * AddPhotoActivity: 사진 업로드
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

    /**
     * BulkCompressActivity: 전체 사진 일괄 압축
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

            folders.forEachIndexed { index, folderName ->
                _bulkProgress.value = Triple(index + 1, totalFolders, folderName)
                repository.compressExistingPhotos(folderName) { current, total ->
                    _compressProgress.value = Pair(current, total)
                }
            }

            setLoading(false)
            _uiEvent.value = UiEvent.Success("모든 사진 최적화 작업이 완료되었습니다.")
        }
    }

    // ── TimeCal 관련 함수 ───────────────────────────────────────────────────

    fun setOriginalData(items: List<TimeCalItem>) {
        _originalList.value = items
        applyDefaultFilter()
    }

    fun getOriginalList(): List<TimeCalItem> = _originalList.value ?: emptyList()

    fun applyManualFilter() {
        val start = filterStartDateMs
        val end = filterEndDateMs
        val query = _searchQuery.value?.trim()?.uppercase() ?: ""
        val source = _originalList.value ?: return

        val filtered = source.filter { item ->
            val etbMs = item.etbDateMs
            val matchStart = start?.let { etbMs >= it } ?: true
            val matchEnd = end?.let { etbMs <= it } ?: true
            val matchSearch =
                if (query.isEmpty()) true else item.vesselName.uppercase().contains(query)
            matchStart && matchEnd && matchSearch
        }
        _filteredList.value = filtered
        _graphStartMs.value = start
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        applyManualFilter()
    }

    fun applyDefaultFilter() {
        if (filterStartDateMs != null || filterEndDateMs != null) {
            applyManualFilter()
            return
        }

        val kst = TimeZone.getTimeZone("Asia/Seoul")
        val todayStart = Calendar.getInstance(kst).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val weekEnd = Calendar.getInstance(kst).apply {
            timeInMillis = todayStart
            add(Calendar.DAY_OF_YEAR, 7)
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }.timeInMillis

        val source = _originalList.value ?: return
        val filtered = source.filter { item ->
            // [수정] 작업 중(WORKING), 접안 중(BERTHED), 또는 미래에 입항 예정(PLANNED)인 모선 표시
            item.vesselStatus == "WORKING" || item.vesselStatus == "BERTHED" ||
                    (item.vesselStatus == "PLANNED" && item.etbDateMs in todayStart..weekEnd)
        }.sortedBy { it.etbDateMs } // ETB 순으로 정렬

        _filteredList.value = filtered
        _graphStartMs.value = todayStart
    }

    fun resetFilter() {
        filterStartDateMs = null
        filterEndDateMs = null
        applyDefaultFilter()
    }

    // ── DGT 데이터 크롤링 (TimeCalActivity에서 이전) ──────────────────────────

    fun fetchDgtData() {
        setLoading(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val kstZone = TimeZone.getTimeZone("Asia/Seoul")
                val cal = Calendar.getInstance(kstZone)
                val sdfParam = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = kstZone }
                val fromDate = sdfParam.format(cal.time)
                cal.add(Calendar.DAY_OF_YEAR, 7)
                val toDate = sdfParam.format(cal.time)

                val newItems = dgtDataSource.fetchBerthSchedules(fromDate, toDate).toMutableList()
                Log.d(
                    "VesselViewModel",
                    "fetchDgtData: received ${newItems.size} items from dataSource"
                )

                if (newItems.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiEvent.value = UiEvent.Error("선박 스케줄을 불러오지 못했거나 데이터가 없습니다.")
                    }
                }

                // Status 및 ETB 순으로 정렬
                newItems.sortWith(compareBy<TimeCalItem> {
                    when (it.vesselStatus) {
                        "WORKING" -> 0
                        "BERTHED" -> 1
                        "PLANNED" -> 2
                        "DEPARTED" -> 3
                        else -> 4
                    }
                }.thenBy { it.etbDateMs })

                withContext(Dispatchers.Main) {
                    setLoading(false)
                    setOriginalData(newItems)
                    Log.d("VesselViewModel", "fetchDgtData: data set to LiveData")
                }
            } catch (e: Exception) {
                Log.e("VesselViewModel", "fetchDgtData error", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    _uiEvent.value = UiEvent.Error("데이터 처리 중 오류가 발생했습니다: ${e.message}")
                }
            }
        }
    }

    /**
     * 특정 선박의 상세 정보 및 QC 작업 현황 조회
     */
    fun fetchVesselWorkStatus(item: TimeCalItem) {
        if (_isLoading.value == true) return
        setLoading(true)
        setVesselDetail(null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = dgtDataSource.fetchVesselDetails(item)
                if (result != null) {
                    val (obj, qcList) = result
                    val detailInfo = VesselDetailInfo(
                        item = item,
                        disQty = obj.optString("dischargeQty", "0"),
                        lodQty = obj.optString("loadQty", "0"),
                        shftQty = obj.optString("shiftQty", "0"),
                        statusStr = obj.optString("status"),
                        qcList = qcList
                    )
                    withContext(Dispatchers.Main) {
                        Log.d(
                            "VesselViewModel",
                            "fetchVesselWorkStatus: success for ${item.vesselName}, QC count: ${qcList.size}"
                        )
                        setVesselDetail(detailInfo)
                        setLoading(false)
                    }
                } else {
                    Log.w(
                        "VesselViewModel",
                        "fetchVesselWorkStatus: failed to get details for ${item.vesselName}"
                    )
                    withContext(Dispatchers.Main) { setLoading(false) }
                }
            } catch (e: Exception) {
                Log.e("VesselViewModel", "fetchStatus error", e)
                withContext(Dispatchers.Main) { setLoading(false) }
            }
        }
    }
}
