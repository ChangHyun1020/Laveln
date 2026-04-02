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
import com.example.vesselv2.util.NavigationHelper
import com.example.vesselv2.util.setupEdgeToEdgeInsets
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var photoAdapter: PhotoAdapter

    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val TAG = "DetailActivity"

    /** Firestore 로드 후 수정 화면에 전달하기 위해 문서 캐싱 */
    private var cachedDocument: DocumentSnapshot? = null

    companion object {
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

        private const val VESSEL_COLLECTION = "Lashing"
    }

    // EditVesselActivity 에서 RESULT_OK 로 돌아오면 화면 새로고침
    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val vesselName = intent.getStringExtra(EXTRA_VESSEL_NAME)
                ?.trim()?.uppercase() ?: return@registerForActivityResult
            loadVesselData(vesselName)   // 수정 완료 → 데이터 재로드
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdgeInsets(binding.toolbar, binding.fabAddPhoto)

        setupUI()
        setupPhotoRecyclerView()

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

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        NavigationHelper.setupSidebar(this, binding.navigationView) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        // FAB 클릭 → 수정 / 삭제 팝업 메뉴
        binding.fabAddPhoto.setOnClickListener { view ->
            showVesselMenu(view)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  FAB 팝업 메뉴: 수정 / 삭제
    // ──────────────────────────────────────────────────────────────────

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

    private val MENU_EDIT = 1
    private val MENU_DELETE = 2

    // ──────────────────────────────────────────────────────────────────
    //  수정: 캐싱된 문서 데이터를 EditVesselActivity 로 전달
    // ──────────────────────────────────────────────────────────────────

    private fun navigateToEdit() {
        val doc = cachedDocument ?: run {
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
            putExtra(
                EditVesselActivity.EXTRA_VESSEL_TURNBURCKLE,
                doc.getString("turnburckle") ?: ""
            )
            putExtra(EditVesselActivity.EXTRA_VESSEL_NOTES, doc.getString("Notes") ?: "")
        }
        editLauncher.launch(intent)
    }

    // ──────────────────────────────────────────────────────────────────
    //  삭제: 경고 다이얼로그 → Storage 전체 삭제 → Firestore 삭제 → finish()
    // ──────────────────────────────────────────────────────────────────

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

    private fun deleteVessel(vesselName: String) {
        binding.progressBar.visibility = View.VISIBLE

        // 1단계: Storage images/{vesselName}/ 폴더 전체 삭제
        val folderRef = storage.reference.child("images/$vesselName")
        folderRef.listAll()
            .addOnSuccessListener { listResult ->
                val files = listResult.items
                if (files.isEmpty()) {
                    // 사진 없으면 바로 Firestore 삭제
                    deleteFirestoreDocument(vesselName)
                    return@addOnSuccessListener
                }

                // 파일 전부 비동기 삭제 후 Firestore 삭제
                var deletedCount = 0
                files.forEach { fileRef ->
                    fileRef.delete()
                        .addOnCompleteListener {
                            // 성공/실패 무관하게 카운트 (일부 실패해도 진행)
                            deletedCount++
                            if (deletedCount == files.size) {
                                deleteFirestoreDocument(vesselName)
                            }
                        }
                }
            }
            .addOnFailureListener { e ->
                // Storage 조회 실패 → Firestore 는 삭제 진행 (사진 없을 수도 있음)
                Log.e(TAG, "Storage 목록 조회 실패, Firestore 삭제 진행", e)
                deleteFirestoreDocument(vesselName)
            }
    }

    private fun deleteFirestoreDocument(vesselName: String) {
        // 2단계: Firestore Lashing/{vesselName} 문서 삭제
        firestore.collection(VESSEL_COLLECTION)
            .document(vesselName)
            .delete()
            .addOnSuccessListener {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "'$vesselName' 이(가) 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)   // 목록 화면에서 새로고침 트리거용
                finish()
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Log.e(TAG, "Firestore 삭제 실패", e)
                Toast.makeText(this, "삭제 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ──────────────────────────────────────────────────────────────────
    //  PhotoRecyclerView 세팅
    // ──────────────────────────────────────────────────────────────────

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
            layoutManager = GridLayoutManager(this@DetailActivity, 3)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  1단계: Firestore 조회 → 필드 바인딩 + 문서 캐싱
    // ──────────────────────────────────────────────────────────────────

    private fun loadVesselData(vesselName: String) {
        binding.progressBar.visibility = View.VISIBLE

        firestore.collection(VESSEL_COLLECTION)
            .document(vesselName)
            .get(Source.SERVER)
            .addOnSuccessListener { document ->
                binding.progressBar.visibility = View.GONE
                if (!document.exists()) {
                    Toast.makeText(this, "모선 데이터를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@addOnSuccessListener
                }
                cachedDocument = document       // 수정 화면 전달용 캐싱
                bindVesselFields(document)
                loadPhotos(vesselName)
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                Log.e(TAG, "Firestore 조회 실패", e)
                Toast.makeText(this, "데이터 로딩 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

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
        binding.tvNotes.text = if (!notes.isNullOrBlank()) notes else "내용 없음"
    }

    // ──────────────────────────────────────────────────────────────────
    //  2단계: Storage 이미지 조회 → 순서 보장된 VesselPhoto 리스트
    // ──────────────────────────────────────────────────────────────────

    private fun loadPhotos(vesselName: String) {
        val folderRef = storage.reference.child("images/$vesselName")
        folderRef.listAll()
            .addOnSuccessListener { listResult ->
                val items = listResult.items.sortedBy { it.name }
                if (items.isEmpty()) {
                    showNoPhoto(); return@addOnSuccessListener
                }

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
                            if (fetched == items.size) updatePhotoList(photoArray.filterNotNull())
                        }
                        .addOnFailureListener {
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

    @SuppressLint("SetTextI18n")
    private fun updatePhotoList(photos: List<VesselPhoto>) {
        if (photos.isEmpty()) {
            showNoPhoto(); return
        }
        photoAdapter.submitList(photos)
        binding.tvPhotoCount.text = "${photos.size}장"
        binding.tvNoPhoto.visibility = View.GONE
    }

    private fun showNoPhoto() {
        photoAdapter.submitList(emptyList())
        binding.tvPhotoCount.text = "0장"
        binding.tvNoPhoto.visibility = View.VISIBLE
    }

    // ──────────────────────────────────────────────────────────────────
    //  onResume: 수정 완료 후 자동 갱신 (editLauncher 로 처리하므로
    //            사진 새로고침만 담당)
    // ──────────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        val vesselName = intent.getStringExtra(EXTRA_VESSEL_NAME)
            ?.trim()?.uppercase() ?: return
        loadPhotos(vesselName)
    }
}
