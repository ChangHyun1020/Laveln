package com.example.vesselv2.ui.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.example.vesselv2.R
import com.example.vesselv2.data.model.VesselPhoto
import com.example.vesselv2.databinding.ActivityDetailBinding
import com.example.vesselv2.ui.adapter.PhotoAdapter
import com.example.vesselv2.util.Constants
import com.example.vesselv2.util.NavigationHelper
import com.example.vesselv2.util.setupEdgeToEdgeInsets
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage

/**
 * [화면] DetailActivity — 모선 상세 정보 화면
 *
 * ▶ 진입 경로:
 *   - FirebaseVesselActivity (목록에서 모선 클릭) → DetailActivity
 *   - VesselCombinedFragment (롱클릭 → Firebase 조회 성공 시) → DetailActivity
 *
 * ▶ 주요 기능:
 *   1. Firestore에서 선택된 모선의 상세 정보 조회 및 표시
 *   2. Firebase Storage에서 해당 모선의 등록 사진 조회 및 그리드로 표시
 *   3. FAB 팝업 메뉴로 수정(EditVesselActivity) 또는 삭제 기능 제공
 *   4. 삭제 시: Storage 사진 전체 삭제 → Firestore 문서 삭제 순서로 처리
 *
 * ▶ Firebase 구조:
 *   - Firestore: Constants.VESSEL_COLLECTION/{vesselName}
 *   - Storage: images/{vesselName}/{vesselName}_00.jpg, _01.jpg, ...
 *
 * ▶ 중요 패턴:
 *   - cachedDocument: Firestore 조회 결과를 메모리에 보관하여 수정 화면 전달 시 재사용
 *   - editLauncher: EditVesselActivity에서 RESULT_OK 반환 시 자동으로 데이터 재로드
 */
class DetailActivity : AppCompatActivity() {

    // View Binding
    private lateinit var binding: ActivityDetailBinding

    // 사진 그리드 어댑터
    private lateinit var photoAdapter: PhotoAdapter

    // Firebase 인스턴스
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private val TAG = "DetailActivity"

    /**
     * Firestore 조회 결과 캐싱 — EditVesselActivity로 데이터를 전달하기 위해 보관
     * (재조회 없이 수정 화면으로 바로 이동 가능)
     */
    private var cachedDocument: DocumentSnapshot? = null

    companion object {
        // ── Intent Extra 키 상수 ────────────────────────────────────────────
        const val EXTRA_VESSEL_NAME = "vesselName"
        const val EXTRA_VESSEL_DOC_ID = "vesselDocId"
        const val EXTRA_VESSEL_ID = "EXTRA_VESSEL_ID"
        const val EXTRA_VESSEL_DATE = "EXTRA_VESSEL_DATE"
        const val EXTRA_VESSEL_CORN = "EXTRA_VESSEL_CORN"
        const val EXTRA_VESSEL_FLAN = "EXTRA_VESSEL_FLAN"
        const val EXTRA_VESSEL_FLOOR = "EXTRA_VESSEL_FLOOR"
        const val EXTRA_VESSEL_ROW = "EXTRA_VESSEL_ROW"
        const val EXTRA_VESSEL_TWIN = "EXTRA_VESSEL_TWIN"
        const val EXTRA_VESSEL_TURNBURCKLE = "EXTRA_VESSEL_TURNBURCKLE"
        const val EXTRA_VESSEL_NOTES = "EXTRA_VESSEL_NOTES"
        // 팝업 메뉴 아이템 ID
        private const val MENU_EDIT = 1
        private const val MENU_DELETE = 2
    }

    /**
     * EditVesselActivity 실행기 — 수정 완료(RESULT_OK) 시 데이터 자동 재로드
     * startActivityForResult 대신 ActivityResult API 사용 (Android 권장 방식)
     */
    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 수정 완료 후 돌아오면 최신 데이터로 화면 갱신
        if (result.resultCode == RESULT_OK) {
            val vesselName = intent.getStringExtra(EXTRA_VESSEL_NAME)
                ?.trim()?.uppercase() ?: return@registerForActivityResult
            loadVesselData(vesselName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 시스템 바(상태바/네비게이션바) 영역 확보
        setupEdgeToEdgeInsets(binding.toolbar, binding.fabAddPhoto)

        setupUI()
        setupPhotoRecyclerView()

        // Intent에서 모선명을 받아 데이터 로드 시작
        val vesselName = intent.getStringExtra(EXTRA_VESSEL_NAME)?.trim()?.uppercase()
        if (vesselName.isNullOrEmpty()) {
            Toast.makeText(this, "모선명 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        loadVesselData(vesselName)
    }

    // ──────────────────────────────────────────────────────────────────
    //  UI 초기 설정
    // ──────────────────────────────────────────────────────────────────

    /**
     * UI 기본 설정
     * - 툴바(사이드바 버튼), 네비게이션 뷰, FAB(수정/삭제 팝업) 설정
     */
    private fun setupUI() {
        // 툴바 햄버거 버튼 → 사이드바 열기
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        // 사이드바 네비게이션 메뉴 초기화
        NavigationHelper.setupSidebar(this, binding.navigationView) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        // FAB 클릭 → 수정 / 삭제 팝업 메뉴 표시
        binding.fabAddPhoto.setOnClickListener { view ->
            showVesselMenu(view)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  FAB 팝업 메뉴: 수정 / 삭제
    // ──────────────────────────────────────────────────────────────────

    /**
     * FAB 팝업 메뉴 표시 (수정 / 삭제 선택)
     *
     * @param anchor FAB 버튼 뷰 (팝업 위치 기준점)
     */
    private fun showVesselMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, MENU_EDIT, 0, "✏️  수정")
        popup.menu.add(0, MENU_DELETE, 1, "🗑️  삭제")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> navigateToEdit()
                MENU_DELETE -> showDeleteConfirmDialog()
            }
            true
        }
        popup.show()
    }

    // ──────────────────────────────────────────────────────────────────
    //  수정: 캐싱된 문서 데이터를 EditVesselActivity로 전달
    // ──────────────────────────────────────────────────────────────────

    /**
     * 수정 화면(EditVesselActivity)으로 이동합니다.
     * cachedDocument(Firestore 문서 캐시)를 읽어 Intent Extra로 전달하므로,
     * 추가 네트워크 요청 없이 즉시 이동 가능합니다.
     */
    private fun navigateToEdit() {
        val doc = cachedDocument ?: run {
            // 아직 Firestore 응답을 받지 못한 경우 (로딩 중)
            Toast.makeText(this, "데이터를 불러오는 중입니다. 잠시 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, EditVesselActivity::class.java).apply {
            putExtra(EditVesselActivity.EXTRA_VESSEL_NAME, doc.getString("vesselName") ?: doc.id)
            putExtra(EditVesselActivity.EXTRA_VESSEL_DATE, doc.getString("date") ?: "")
            putExtra(EditVesselActivity.EXTRA_VESSEL_CORN, doc.getString("corn") ?: "")
            putExtra(EditVesselActivity.EXTRA_VESSEL_FLAN, doc.getString("flan") ?: "")
            putExtra(EditVesselActivity.EXTRA_VESSEL_FLOOR, doc.getString("floor") ?: "")
            putExtra(EditVesselActivity.EXTRA_VESSEL_ROW, doc.getString("row") ?: "")
            putExtra(EditVesselActivity.EXTRA_VESSEL_TWIN, doc.getString("twin") ?: "")
            putExtra(EditVesselActivity.EXTRA_VESSEL_TURNBURCKLE, doc.getString("turnburckle") ?: "")
            putExtra(EditVesselActivity.EXTRA_VESSEL_NOTES, doc.getString("Notes") ?: "")
        }
        editLauncher.launch(intent)
    }

    // ──────────────────────────────────────────────────────────────────
    //  삭제: 경고 다이얼로그 → Storage 전체 삭제 → Firestore 삭제 → finish()
    // ──────────────────────────────────────────────────────────────────

    /**
     * 삭제 확인 다이얼로그 표시
     * 실수 방지를 위해 삭제 전 반드시 사용자 확인을 받습니다.
     */
    private fun showDeleteConfirmDialog() {
        val vesselName = intent.getStringExtra(EXTRA_VESSEL_NAME)?.trim()?.uppercase() ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("모선 삭제")
            .setMessage("'$vesselName' 의 모든 정보와 사진이 영구 삭제됩니다.\n정말 삭제하시겠습니까?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                deleteVessel(vesselName)
            }
            .show()
    }

    /**
     * 모선 삭제 실행 — 2단계로 진행:
     *   1단계: Firebase Storage의 해당 모선 폴더 내 사진 전체 삭제
     *   2단계: Firestore 문서 삭제
     *
     * 삭제 순서를 Storage 먼저 하는 이유:
     *   Firestore를 먼저 삭제하면 Storage에 고아 파일이 남을 수 있기 때문
     *
     * @param vesselName 삭제할 모선명 (Firestore 문서 ID이자 Storage 폴더명)
     */
    private fun deleteVessel(vesselName: String) {
        binding.progressBar.visibility = View.VISIBLE

        // 1단계: Storage images/{vesselName}/ 폴더 내 파일 목록 조회
        val folderRef = storage.reference.child("images/$vesselName")
        folderRef.listAll()
            .addOnSuccessListener { listResult ->
                val files = listResult.items
                if (files.isEmpty()) {
                    // 사진이 없으면 바로 Firestore 삭제로 진행
                    deleteFirestoreDocument(vesselName)
                    return@addOnSuccessListener
                }

                // 파일 전체 비동기 삭제 — 모두 완료 후 Firestore 삭제
                var deletedCount = 0
                files.forEach { fileRef ->
                    fileRef.delete()
                        .addOnCompleteListener {
                            // 성공/실패 관계없이 카운트 증가 (일부 실패해도 전체 흐름 유지)
                            deletedCount++
                            if (deletedCount == files.size) {
                                deleteFirestoreDocument(vesselName)
                            }
                        }
                }
            }
            .addOnFailureListener { e ->
                // Storage 목록 조회 실패 → Firestore 삭제는 계속 진행
                // (Storage에 파일이 없을 수도 있음)
                Log.e(TAG, "Storage 목록 조회 실패, Firestore 삭제 진행", e)
                deleteFirestoreDocument(vesselName)
            }
    }

    /**
     * [2단계] Firestore 문서 삭제
     * 삭제 성공 시 RESULT_OK 반환 → 이전 목록 화면이 자동으로 새로고침
     *
     * ⚠️ 보안: Constants.VESSEL_COLLECTION 상수 사용 (하드코딩 금지)
     *
     * @param vesselName 삭제할 문서 ID (= 모선명 대문자)
     */
    private fun deleteFirestoreDocument(vesselName: String) {
        // ⚠️ 보안: 컬렉션명은 Constants 상수 사용
        firestore.collection(Constants.VESSEL_COLLECTION)
            .document(vesselName)
            .delete()
            .addOnSuccessListener {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "'$vesselName' 이(가) 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)   // 목록 화면에서 새로고침 트리거
                finish()
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Log.e(TAG, "Firestore 삭제 실패", e)
                Toast.makeText(this, "삭제 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ──────────────────────────────────────────────────────────────────
    //  사진 RecyclerView 설정
    // ──────────────────────────────────────────────────────────────────

    /**
     * 사진 그리드 RecyclerView 초기화
     * - 사진 클릭 시 PhotoViewerActivity로 이동 (전체화면 보기)
     * - 1행 3열 그리드 레이아웃
     */
    private fun setupPhotoRecyclerView() {
        photoAdapter = PhotoAdapter { photo ->
            val vesselName = intent.getStringExtra(EXTRA_VESSEL_NAME)
                ?.trim()?.uppercase() ?: return@PhotoAdapter
            startActivity(
                Intent(this, PhotoViewerActivity::class.java).apply {
                    putExtra(PhotoViewerActivity.EXTRA_VESSEL_NAME, vesselName)
                    putExtra(PhotoViewerActivity.EXTRA_PHOTO_URL, photo.imageUrl)
                }
            )
        }
        binding.recyclerPhotos.apply {
            adapter = photoAdapter
            layoutManager = GridLayoutManager(this@DetailActivity, 3) // 3열 그리드
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  1단계: Firestore 조회 → 필드 바인딩 + 문서 캐싱
    // ──────────────────────────────────────────────────────────────────

    /**
     * Firestore에서 모선 데이터를 조회하고 화면에 표시합니다.
     *
     * - Source.SERVER: 캐시 무시, 항상 최신 서버 데이터 조회
     * - 조회 성공 시: 필드 바인딩 + 사진 로드 + 문서 캐싱
     *
     * ⚠️ 보안: Constants.VESSEL_COLLECTION 상수 사용
     *
     * @param vesselName 조회할 모선명 (= Firestore 문서 ID)
     */
    private fun loadVesselData(vesselName: String) {
        binding.progressBar.visibility = View.VISIBLE

        // ⚠️ 보안: 컬렉션명은 Constants 상수 사용
        firestore.collection(Constants.VESSEL_COLLECTION)
            .document(vesselName)
            .get(Source.SERVER) // 항상 최신 데이터 요청
            .addOnSuccessListener { document ->
                binding.progressBar.visibility = View.GONE
                if (!document.exists()) {
                    Toast.makeText(this, "모선 데이터를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@addOnSuccessListener
                }
                // 문서를 캐싱하여 수정 화면 이동 시 재사용
                cachedDocument = document
                bindVesselFields(document)
                loadPhotos(vesselName)
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Log.e(TAG, "Firestore 조회 실패", e)
                Toast.makeText(this, "데이터 로딩 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Firestore 문서의 필드를 화면 TextView에 바인딩합니다.
     * 값이 null이면 "-"(대시)로 표시합니다.
     *
     * @param doc Firestore DocumentSnapshot
     */
    @SuppressLint("SetTextI18n")
    private fun bindVesselFields(doc: DocumentSnapshot) {
        binding.tvVesselName.text = doc.getString("vesselName") ?: doc.id
        binding.tvDate.text = doc.getString("date") ?: "-"
        binding.tvCorn.text = doc.getString("corn") ?: "-"
        binding.tvFlan.text = doc.getString("flan") ?: "-"
        binding.tvFloor.text = doc.getString("floor") ?: "-"
        binding.tvRow.text = doc.getString("row") ?: "-"
        binding.tvTwin.text = doc.getString("twin") ?: "-"
        binding.tvTurnburckle.text = doc.getString("turnburckle") ?: "-"
        val notes = doc.getString("Notes")
        // 메모가 없거나 비어 있으면 안내 문구 표시
        binding.tvNotes.text = if (!notes.isNullOrBlank()) notes else "내용 없음"
    }

    // ──────────────────────────────────────────────────────────────────
    //  2단계: Storage 사진 조회 → 순서 보장된 VesselPhoto 리스트 표시
    // ──────────────────────────────────────────────────────────────────

    /**
     * Firebase Storage에서 해당 모선의 사진 목록을 조회합니다.
     *
     * - 경로: images/{vesselName}/
     * - 파일명 기준 오름차순 정렬 (_00, _01, _02, ...)
     * - 각 파일의 downloadUrl을 병렬로 가져와 배열의 원래 순서 보장
     *
     * @param vesselName Storage 폴더명 (= 모선명 대문자)
     */
    private fun loadPhotos(vesselName: String) {
        val folderRef = storage.reference.child("images/$vesselName")
        folderRef.listAll()
            .addOnSuccessListener { listResult ->
                // 파일명 기준 정렬 (업로드 순서 보장)
                val items = listResult.items.sortedBy { it.name }
                if (items.isEmpty()) {
                    showNoPhoto(); return@addOnSuccessListener
                }

                // 인덱스를 보존하는 배열 사용 — 비동기 응답 순서가 달라도 표시 순서 유지
                val photoArray = arrayOfNulls<VesselPhoto>(items.size)
                var fetched = 0

                items.forEachIndexed { index, ref ->
                    ref.downloadUrl
                        .addOnSuccessListener { uri ->
                            photoArray[index] = VesselPhoto(
                                storagePath = ref.path,
                                fileName = ref.name,
                                imageUrl = uri.toString()
                            )
                            fetched++
                            // 모든 다운로드 URL 수신 완료 시 UI 업데이트
                            if (fetched == items.size) updatePhotoList(photoArray.filterNotNull())
                        }
                        .addOnFailureListener {
                            // 개별 사진 URL 실패 시 해당 사진만 건너뜀
                            fetched++
                            if (fetched == items.size) updatePhotoList(photoArray.filterNotNull())
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Storage 조회 실패", e)
                showNoPhoto()
            }
    }

    /**
     * 사진 목록을 어댑터에 전달하고 사진 수를 업데이트합니다.
     *
     * @param photos 로드 완료된 VesselPhoto 리스트
     */
    @SuppressLint("SetTextI18n")
    private fun updatePhotoList(photos: List<VesselPhoto>) {
        if (photos.isEmpty()) {
            showNoPhoto(); return
        }
        photoAdapter.submitList(photos)
        binding.tvPhotoCount.text = "${photos.size}장"
        binding.tvNoPhoto.visibility = View.GONE
    }

    /**
     * 사진이 없을 때 "사진 없음" 안내 텍스트를 표시합니다.
     */
    private fun showNoPhoto() {
        photoAdapter.submitList(emptyList())
        binding.tvPhotoCount.text = "0장"
        binding.tvNoPhoto.visibility = View.VISIBLE
    }

    // ──────────────────────────────────────────────────────────────────
    //  onResume: 외부에서 돌아왔을 때 사진 목록 갱신
    // ──────────────────────────────────────────────────────────────────

    /**
     * onResume: 앱이 포그라운드로 돌아올 때마다 사진 목록 재조회
     * (사진이 추가/삭제된 경우 항상 최신 상태로 표시)
     *
     * ※ 모선 정보 갱신은 editLauncher 콜백에서 처리하므로 여기서는 사진만 갱신
     */
    override fun onResume() {
        super.onResume()
        val vesselName = intent.getStringExtra(EXTRA_VESSEL_NAME)
            ?.trim()?.uppercase() ?: return
        loadPhotos(vesselName)
    }
}
