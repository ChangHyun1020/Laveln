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
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.example.vesselv2.R
import com.example.vesselv2.databinding.ActivityEditVesselBinding
import com.google.firebase.firestore.FirebaseFirestore

class EditVesselActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditVesselBinding
    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditVesselBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInputFields()
        loadVesselData()
        setupButtonListeners()

        onBackPressedDispatcher.addCallback(
            this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPress()
                }
            })
    }

    private fun setupInputFields() {
        binding.editCorn.setOnClickListener { v ->
            showPopupMenu(v, R.menu.corn_item) { binding.editCorn.setText(it) }
        }
        binding.editFlan.setOnClickListener { v ->
            showPopupMenu(v, R.menu.flan_item) { binding.editFlan.setText(it) }
        }
        binding.editTwin.setOnClickListener { v ->
            showPopupMenu(v, R.menu.twin_item) { binding.editTwin.setText(it) }
        }

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

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            binding.nestedScrollView.updatePadding(bottom = imeHeight + 100)
            if (isImeVisible && binding.editNotes.hasFocus()) {
                scrollToNotes(binding.editNotes)
            }
            insets
        }

        // 바텀마진
        val btnMarginBase = (16 * resources.displayMetrics.density).toInt() // 16dp → px
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            // 스크롤 영역: IME 높이 + 여유분
            binding.nestedScrollView.updatePadding(bottom = imeHeight + 100)

            // ✅ 수정 버튼: 네비게이션 바 높이 + 기본 여백 만큼 위로
            binding.btnUpdate.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = navBarHeight + btnMarginBase
            }
            if (isImeVisible && binding.editNotes.hasFocus()) {
                scrollToNotes(binding.editNotes)
            }
            insets
        }


        binding.editNotes.setOnClickListener { scrollToNotes(it) }
        binding.editNotes.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) scrollToNotes(v) }
        binding.editNotes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?, start: Int, count: Int, after: Int
            ) {
            }

            override fun onTextChanged(
                s: CharSequence?, start: Int, before: Int, count: Int
            ) {
                scrollToNotes(binding.editNotes)
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

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

    private fun setupButtonListeners() {
        binding.btnUpdate.setOnClickListener { updateVesselData() }
    }

    private fun updateVesselData() {
        val vesselName = binding.editVesselName.text.toString().trim()
        if (vesselName.isEmpty()) return

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

        db.collection("Lashing").document(vesselName).update(data as Map<String, Any>)
            .addOnSuccessListener {
                Toast.makeText(this, R.string.toast_save_success, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }.addOnFailureListener { e ->
                Toast.makeText(
                    this, getString(R.string.toast_save_fail, e.message), Toast.LENGTH_LONG
                ).show()
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
        AlertDialog.Builder(this).setTitle(R.string.dialog_title_cancel_edit)
            .setMessage(R.string.dialog_msg_cancel_edit)
            .setPositiveButton(R.string.btn_cancel_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.btn_continue_edit, null).show()
    }

    companion object {
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
