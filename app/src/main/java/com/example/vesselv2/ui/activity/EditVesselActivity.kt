package com.example.vesselv2.ui.activity

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.example.vesselv2.R
import com.example.vesselv2.databinding.ActivityEditVesselBinding
import com.example.vesselv2.util.Constants
import com.google.firebase.firestore.FirebaseFirestore

/**
 * [화면] EditVesselActivity — 모선 정보 수정 화면
 *
 * ▶ 진입 경로:
 *   DetailActivity (FAB 팝업 → "수정" 선택) → EditVesselActivity
 *
 * ▶ 주요 동작:
 *   1. DetailActivity에서 Intent Extra로 전달받은 현재 모선 데이터를 각 입력 필드에 표시
 *   2. 사용자가 값을 수정 후 [수정] 버튼 클릭 시 Firestore 문서 업데이트
 *   3. 수정 완료 후 RESULT_OK를 반환하여 DetailActivity가 화면을 갱신하도록 처리
 *   4. 뒤로가기 시 확인 다이얼로그 표시 (미저장 내용 손실 방지)
 *
 * ▶ Firebase:
 *   - 컬렉션: Constants.VESSEL_COLLECTION ("Lashing")
 *   - 문서 ID: vesselName (대문자)
 *   - 조작: update (set이 아닌 update — 기존 필드 보존)
 *
 * ▶ 보안:
 *   - Firestore 컬렉션명은 Constants.VESSEL_COLLECTION 상수 사용 (하드코딩 금지)
 *   - 수정 권한은 Firebase Security Rules로 별도 제어
 */
class EditVesselActivity : AppCompatActivity() {

    // View Binding — XML 레이아웃과 코드를 안전하게 연결
    private lateinit var binding: ActivityEditVesselBinding

    // Firebase Firestore 인스턴스 (앱 단위 싱글톤)
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditVesselBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 입력 필드 UX 설정 (팝업 메뉴, 키보드 여백 등)
        setupInputFields()
        // Intent Extra에서 기존 데이터를 불러와 필드에 채움
        loadVesselData()
        // 버튼 클릭 리스너 등록
        setupButtonListeners()

        // 뒤로가기 버튼 — 미저장 내용 손실 경고 다이얼로그
        onBackPressedDispatcher.addCallback(
            this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPress()
                }
            })
    }

    /**
     * 입력 필드 UX 초기화
     * - corn / flan / twin: 팝업 메뉴로 선택 입력
     * - 메모(notes) 필드: 포커스 시 자동 스크롤
     * - 저장 버튼: 시스템 네비게이션 바 위로 여백 조정
     */
    private fun setupInputFields() {
        // 팝업 메뉴: 코너 캐스팅(corn) 선택
        binding.editCorn.setOnClickListener { v ->
            showPopupMenu(v, R.menu.corn_item) { binding.editCorn.setText(it) }
        }
        // 팝업 메뉴: 플랜지(flan) 선택
        binding.editFlan.setOnClickListener { v ->
            showPopupMenu(v, R.menu.flan_item) { binding.editFlan.setText(it) }
        }
        // 팝업 메뉴: 트윈(twin) 선택
        binding.editTwin.setOnClickListener { v ->
            showPopupMenu(v, R.menu.twin_item) { binding.editTwin.setText(it) }
        }

        /**
         * 메모(Notes) 입력 시 자동 스크롤 — 키보드에 의해 가려지지 않도록 처리
         * @param notesView 스크롤 대상 뷰
         */
        fun scrollToNotes(notesView: View) {
            binding.nestedScrollView.postDelayed(
                {
                    val rect = android.graphics.Rect()
                    val parentLayout = notesView.parent.parent as? View ?: return@postDelayed
                    parentLayout.getDrawingRect(rect)
                    binding.nestedScrollView.offsetDescendantRectToMyCoords(parentLayout, rect)
                    val scrollY = rect.top - (binding.nestedScrollView.height / 5)
                    binding.nestedScrollView.smoothScrollTo(0, 0.coerceAtLeast(scrollY))
                }, 100
            )
        }

        // 시스템 인셋 적용: IME(키보드) 높이 및 네비게이션 바 높이에 따라 여백 동적 조정
        val btnMarginBase = (16 * resources.displayMetrics.density).toInt() // 16dp → px
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            // 스크롤 뷰 하단 패딩: 키보드 높이 + 여유분 (내용이 키보드 뒤로 숨지 않도록)
            binding.nestedScrollView.updatePadding(bottom = imeHeight + 100)

            // 수정 버튼 bottomMargin = 네비게이션 바 높이 + 기본 여백(16dp)
            binding.btnUpdate.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = navBarHeight + btnMarginBase
            }
            // 메모 필드에 포커스 + 키보드가 표시된 경우 스크롤
            if (isImeVisible && binding.editNotes.hasFocus()) {
                scrollToNotes(binding.editNotes)
            }
            insets
        }

        // 메모 필드 클릭/포커스/텍스트 변경 시 자동 스크롤
        binding.editNotes.setOnClickListener { scrollToNotes(it) }
        binding.editNotes.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) scrollToNotes(v) }
        binding.editNotes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scrollToNotes(binding.editNotes)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    /**
     * Intent Extra에서 기존 모선 데이터를 읽어 입력 필드에 채웁니다.
     * DetailActivity에서 cachedDocument를 통해 전달받은 값을 표시합니다.
     */
    private fun loadVesselData() {
        val vesselName = intent.getStringExtra(EXTRA_VESSEL_NAME) ?: return
        binding.editVesselName.setText(vesselName)
        binding.editCal.setText(intent.getStringExtra(EXTRA_VESSEL_DATE))
        binding.editCorn.setText(intent.getStringExtra(EXTRA_VESSEL_CORN))
        binding.editFlan.setText(intent.getStringExtra(EXTRA_VESSEL_FLAN))
        binding.editFloor.setText(intent.getStringExtra(EXTRA_VESSEL_FLOOR))
        binding.editRow.setText(intent.getStringExtra(EXTRA_VESSEL_ROW))
        binding.editTwin.setText(intent.getStringExtra(EXTRA_VESSEL_TWIN))
        binding.editTurnburckle.setText(intent.getStringExtra(EXTRA_VESSEL_TURNBURCKLE))
        binding.editNotes.setText(intent.getStringExtra(EXTRA_VESSEL_NOTES))
    }

    /**
     * 버튼 클릭 리스너 등록
     */
    private fun setupButtonListeners() {
        // [수정] 버튼 클릭 → Firestore 문서 업데이트
        binding.btnUpdate.setOnClickListener { updateVesselData() }
    }

    /**
     * Firestore에 수정된 모선 데이터를 저장합니다.
     *
     * - 컬렉션 경로: Constants.VESSEL_COLLECTION / {vesselName}
     * - vesselName은 모선명을 ID로 사용 (문서 ID = 모선명 대문자)
     * - update()를 사용하므로 명시되지 않은 필드(예: 사진 URL)는 보존됩니다.
     * - 저장 성공 시: RESULT_OK 반환 → DetailActivity에서 화면을 자동 갱신
     */
    private fun updateVesselData() {
        val vesselName = binding.editVesselName.text.toString().trim()
        // 모선명이 비어 있으면 저장하지 않음
        if (vesselName.isEmpty()) return

        // 수정할 필드 Map 구성 (vesselName 필드는 변경 불가 — ID와 동일)
        val data = hashMapOf(
            "corn" to binding.editCorn.text.toString(),
            "row" to binding.editRow.text.toString(),
            "turnburckle" to binding.editTurnburckle.text.toString(),
            "floor" to binding.editFloor.text.toString(),
            "flan" to binding.editFlan.text.toString(),
            "twin" to binding.editTwin.text.toString(),
            "Notes" to binding.editNotes.text.toString(),
            "date" to binding.editCal.text.toString()
        )

        // ⚠️ 보안: 컬렉션명은 Constants 상수 사용 (하드코딩 금지)
        db.collection(Constants.VESSEL_COLLECTION).document(vesselName).update(data as Map<String, Any>)
            .addOnSuccessListener {
                // 수정 완료 토스트 + 결과 반환 후 종료
                Toast.makeText(this, R.string.toast_save_success, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }.addOnFailureListener { e ->
                // 저장 실패 — 오류 메시지 표시
                Toast.makeText(
                    this, getString(R.string.toast_save_fail, e.message), Toast.LENGTH_LONG
                ).show()
            }
    }

    /**
     * 팝업 메뉴를 표시하고 선택된 항목의 텍스트를 콜백으로 반환합니다.
     *
     * @param anchor 팝업이 붙을 기준 뷰
     * @param menuRes 표시할 메뉴 리소스 ID
     * @param onSelected 항목 선택 시 호출되는 콜백 (선택된 텍스트 전달)
     */
    private fun showPopupMenu(anchor: View, menuRes: Int, onSelected: (String) -> Unit) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(menuRes, menu)
            setOnMenuItemClickListener { item ->
                onSelected(item.title.toString())
                true
            }
            show()
        }
    }

    /**
     * 뒤로가기 처리 — 미저장 내용 손실 경고 다이얼로그
     * 사용자가 실수로 뒤로가기를 눌러 변경 내용을 잃지 않도록 확인을 요청합니다.
     */
    private fun handleBackPress() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_cancel_edit)
            .setMessage(R.string.dialog_msg_cancel_edit)
            .setPositiveButton(R.string.btn_cancel_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.btn_continue_edit, null)
            .show()
    }

    companion object {
        // ── Intent Extra 키 상수 ────────────────────────────────────────────
        // DetailActivity → EditVesselActivity 데이터 전달 시 사용
        const val EXTRA_VESSEL_NAME = "EXTRA_VESSEL_NAME"
        const val EXTRA_VESSEL_DATE = "EXTRA_VESSEL_DATE"
        const val EXTRA_VESSEL_CORN = "EXTRA_VESSEL_CORN"
        const val EXTRA_VESSEL_FLAN = "EXTRA_VESSEL_FLAN"
        const val EXTRA_VESSEL_FLOOR = "EXTRA_VESSEL_FLOOR"
        const val EXTRA_VESSEL_ROW = "EXTRA_VESSEL_ROW"
        const val EXTRA_VESSEL_TWIN = "EXTRA_VESSEL_TWIN"
        const val EXTRA_VESSEL_TURNBURCKLE = "EXTRA_VESSEL_TURNBURCKLE"
        const val EXTRA_VESSEL_NOTES = "EXTRA_VESSEL_NOTES"
    }
}
