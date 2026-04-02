package com.example.vesselv2.ui.activity

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.vesselv2.databinding.ActivityAddPhotoBinding
import com.example.vesselv2.ui.viewmodel.VesselViewModel

class AddPhotoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddPhotoBinding
    private val viewModel: VesselViewModel by viewModels()
    private var vesselName: String? = null

    private var selectedUri: android.net.Uri? = null

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                selectedUri = it
                binding.ivPreview.setImageURI(it)
                binding.tvSelectHint.visibility = android.view.View.GONE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddPhotoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vesselName = intent.getStringExtra(EXTRA_VESSEL_NAME)
        if (vesselName == null) {
            Toast.makeText(this, "선박 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.btnSelectImage.setOnClickListener { galleryLauncher.launch("image/*") }

        binding.btnSave.setOnClickListener {
            val name = vesselName ?: return@setOnClickListener
            val uri = selectedUri
            if (uri != null) {
                viewModel.addPhoto(this, name, uri)
            } else {
                Toast.makeText(this, "사진을 먼저 선택해주세요.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            binding.btnSelectImage.isEnabled = !isLoading
            binding.btnSave.isEnabled = !isLoading
            binding.progressBar.visibility =
                if (isLoading) android.view.View.VISIBLE else android.view.View.GONE
        }

        viewModel.uiEvent.observe(this) { event ->
            when (event) {
                is VesselViewModel.UiEvent.Success -> {
                    Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
                    finish()
                }

                is VesselViewModel.UiEvent.Error -> {
                    Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_VESSEL_NAME = "EXTRA_VESSEL_NAME"
    }
}
