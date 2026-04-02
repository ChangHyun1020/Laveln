package com.example.vesselv2.ui.activity

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vesselv2.R
import com.example.vesselv2.databinding.ActivityAddVesselBinding
import com.example.vesselv2.ui.adapter.ImagePreviewAdapter
import com.example.vesselv2.util.Constants
import com.example.vesselv2.util.setupEdgeToEdgeInsets
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.util.*

class AddVesselActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddVesselBinding
    private val store = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val selectedUris = mutableListOf<android.net.Uri>()
    private lateinit var imageAdapter: ImagePreviewAdapter

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            val availableCount = MAX_IMAGES - selectedUris.size
            if (availableCount <= 0) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_image_limit, MAX_IMAGES),
                    Toast.LENGTH_SHORT
                )
                    .show()
                return@registerForActivityResult
            }
            val toAdd = uris.take(availableCount)
            selectedUris.addAll(toAdd)
            if (uris.size > availableCount) {
                Toast.makeText(
                    this,
                    getString(
                        R.string.toast_image_max_info,
                        MAX_IMAGES,
                        toAdd.size
                    ),
                    Toast.LENGTH_LONG
                )
                    .show()
            }
            refreshImageUI()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddVesselBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 시스템 바 영역 확보
        setupEdgeToEdgeInsets(binding.appBarLayout, binding.addBtn)

        setupRecyclerView()
        setupInputFields()
        setupButtonListeners()
        setTodayDate()

        // ── Constants 에서 정의된 키를 사용하여 모선명 자동 입력 ──────────────
        val prefillName = intent.getStringExtra(Constants.EXTRA_PREFILL_VESSEL_NAME)
        if (!prefillName.isNullOrEmpty()) {
            binding.vesselName.setText(prefillName)
            // TextWatcher가 즉시 반응하지만, setText는 afterTextChanged 타이밍이
            // 늦을 수 있으므로 명시적으로 버튼도 활성화
            binding.addBtn.isEnabled = true
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPress()
                }
            }
        )
    }

    private fun setupRecyclerView() {
        imageAdapter = ImagePreviewAdapter { position ->
            if (position in 0 until selectedUris.size) {
                selectedUris.removeAt(position)
                refreshImageUI()
            }
        }
        binding.rvImages.apply {
            adapter = imageAdapter
            layoutManager =
                LinearLayoutManager(
                    this@AddVesselActivity,
                    LinearLayoutManager.HORIZONTAL,
                    false
                )
        }
    }

    private fun setupInputFields() {
        binding.addCal.setOnClickListener { showDatePicker() }
        binding.addCorn.setOnClickListener { v ->
            showPopupMenu(v, R.menu.corn_item) { binding.addCorn.setText(it) }
        }
        binding.addFlan.setOnClickListener { v ->
            showPopupMenu(v, R.menu.flan_item) { binding.addFlan.setText(it) }
        }
        binding.addTwin.setOnClickListener { v ->
            showPopupMenu(v, R.menu.twin_item) { binding.addTwin.setText(it) }
        }

        // ── Fix 1: 모선명 입력 여부에 따라 저장 버튼 활성/비활성 ─────────
        binding.vesselName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.addBtn.isEnabled = s?.trim()?.isNotEmpty() == true
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        // ─────────────────────────────────────────────────────────────────

        fun scrollToNotes(notesView: View) {
            binding.nestedScrollView.postDelayed(
                {
                    val rect = android.graphics.Rect()
                    val parentLayout = notesView.parent.parent as? View ?: return@postDelayed
                    parentLayout.getDrawingRect(rect)
                    binding.nestedScrollView.offsetDescendantRectToMyCoords(parentLayout, rect)
                    val scrollY = rect.top - (binding.nestedScrollView.height / 5)
                    binding.nestedScrollView.smoothScrollTo(0, 0.coerceAtLeast(scrollY))
                },
                100
            )
        }

        // Fix 2: 저장 버튼을 시스템 네비게이션 바 위에 위치
        val btnMarginBase = (16 * resources.displayMetrics.density).toInt() // 16dp → px
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            // 스크롤 영역: IME 높이 + 여유분
            binding.nestedScrollView.updatePadding(bottom = imeHeight + 100)

            // 저장 버튼 bottomMargin = 네비게이션 바 높이 + 기본 여백(16dp)
            binding.addBtn.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = navBarHeight + btnMarginBase
            }

            if (isImeVisible && binding.addNotes.hasFocus()) {
                scrollToNotes(binding.addNotes)
            }
            insets
        }

        binding.addNotes.setOnClickListener { scrollToNotes(it) }
        binding.addNotes.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) scrollToNotes(v) }
        binding.addNotes.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    scrollToNotes(binding.addNotes)
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )
    }

    private fun setupButtonListeners() {
        binding.btnSelectImages.setOnClickListener { galleryLauncher.launch("image/*") }
        binding.addBtn.setOnClickListener { saveVessel() }
    }

    private fun setTodayDate() {
        binding.addCal.setText("")
    }

    @SuppressLint("SetTextI18n")
    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                binding.addCal.setText("%d-%02d-%02d".format(year, month + 1, day))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshImageUI() {
        imageAdapter.submitList(selectedUris.toList())
        binding.tvImageCount.text =
            getString(R.string.image_count_format, selectedUris.size, MAX_IMAGES)
        val hasImages = selectedUris.isNotEmpty()
        binding.tvNoImage.visibility = if (hasImages) View.GONE else View.VISIBLE
        binding.rvImages.visibility = if (hasImages) View.VISIBLE else View.GONE
        binding.btnSelectImages.isEnabled = selectedUris.size < MAX_IMAGES
    }

    private fun saveVessel() {
        val vesselNameText = binding.vesselName.text.toString().trim().uppercase()
        if (vesselNameText.isEmpty()) {
            Toast.makeText(this, "모선명을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val data =
            hashMapOf(
                "vesselName" to vesselNameText,
                "corn" to binding.addCorn.text.toString(),
                "row" to binding.addRow.text.toString(),
                "turnburckle" to binding.addTurnburckle.text.toString(),
                "floor" to binding.addFloor.text.toString(),
                "flan" to binding.addFlan.text.toString(),
                "twin" to binding.addTwin.text.toString(),
                "Notes" to binding.addNotes.text.toString(),
                "date" to binding.addCal.text.toString()
            )
        setUploadingState(true)
        store.collection(Constants.VESSEL_COLLECTION)
            .document(vesselNameText)
            .set(data)
            .addOnSuccessListener {
                if (selectedUris.isEmpty()) {
                    onAllUploadsComplete()
                } else {
                    uploadAllImages(vesselNameText)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    getString(R.string.toast_save_fail, e.message),
                    Toast.LENGTH_LONG
                )
                    .show()
                setUploadingState(false)
            }
    }

    private fun uploadAllImages(vesselName: String) {
        val total = selectedUris.size
        var done = 0
        updateUploadProgress(done, total)
        selectedUris.forEachIndexed { i, uri ->
            val compressedData =
                com.example.vesselv2.util.ImageCompressor.compressFromUri(this, uri)
            if (compressedData == null) {
                done++
                updateUploadProgress(done, total)
                if (done == total) onAllUploadsComplete()
                return@forEachIndexed
            }
            val fileName = "${vesselName}_0${i}.jpg"
            storage.reference
                .child("images/$vesselName")
                .child(fileName)
                .putBytes(compressedData)
                .addOnSuccessListener {
                    done++
                    updateUploadProgress(done, total)
                    if (done == total) onAllUploadsComplete()
                }
                .addOnFailureListener {
                    done++
                    updateUploadProgress(done, total)
                    if (done == total) onAllUploadsComplete()
                }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUploadProgress(done: Int, total: Int) {
        val percent = if (total > 0) (done * 100 / total) else 0
        binding.uploadProgressBar.setProgressCompat(percent, true)
        binding.tvUploadCount.text = getString(R.string.upload_progress_format, done, total)
    }

    private fun onAllUploadsComplete() {
        setUploadingState(false)
        Toast.makeText(this, R.string.toast_save_success, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun setUploadingState(isUploading: Boolean) {
        binding.uploadProgressBar.visibility = if (isUploading) View.VISIBLE else View.GONE
        binding.uploadStatusLayout.visibility = if (isUploading) View.VISIBLE else View.GONE
        binding.addBtn.isEnabled = !isUploading && binding.vesselName.text?.isNotBlank() == true
        binding.btnSelectImages.isEnabled = !isUploading
        binding.vesselName.isEnabled = !isUploading
        if (isUploading) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
    }

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

    private fun handleBackPress() {
        val hasInput =
            binding.vesselName.text?.isNotBlank() == true ||
                    binding.addNotes.text?.isNotBlank() == true ||
                    selectedUris.isNotEmpty()
        if (!hasInput) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_cancel_add)
            .setMessage(R.string.dialog_msg_cancel_add)
            .setPositiveButton(R.string.btn_cancel_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.btn_continue_input, null)
            .show()
    }

    companion object {
        private const val MAX_IMAGES = 4
    }
}
