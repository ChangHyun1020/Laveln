package com.example.vesselv2.ui.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.vesselv2.ui.viewmodel.VesselViewModel
import com.example.vesselv2.databinding.ActivityBulkCompressBinding

/**
 * [관리자] BulkCompressActivity - 전체 사진 일괄 압축 및 최적화
 *
 * - 기능: Firebase Storage에 저장된 이미지를 다운로드하여 압축 후 다시 업로드
 * - 대상: 'images/' 경로 하위의 모든 선박 폴더 내 이미지
 * - 목적: 전체 저장 공간 절약, 데이터 로딩 속도 향상, 비용 절감
 */
class BulkCompressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBulkCompressBinding
    private val viewModel: VesselViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBulkCompressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "전체 사진 최적화 관리"
            setDisplayHomeAsUpEnabled(true)
        }

        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.btnStart.setOnClickListener {
            showStartConfirmDialog()
        }
    }

    private fun showStartConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("일괄 압축 프로세스 시작")
            .setMessage(
                "주의: 전수 조사 및 압축 작업이므로 시간이 상당히 소요될 수 있습니다.\n\n" +
                        "- 인터넷 속도 및 데이터 레이턴시에 따라 완료 시간은 달라집니다.\n" +
                        "- 작업 도중 앱을 종료하거나 네트워크를 끊지 마십시오.\n" +
                        "- 한 번 압축된 원본은 복구가 불가능합니다.\n\n" +
                        "진행하시겠습니까?"
            )
            .setPositiveButton("예, 진행합니다") { _, _ ->
                binding.btnStart.isEnabled = false
                viewModel.compressAllPhotos()
            }
            .setNegativeButton("아니오", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun observeViewModel() {
        // 전체 진행률 (선박 폴더 단위)
        viewModel.bulkProgress.observe(this) { (current, total, vesselName) ->
            binding.tvStatus.text = "현재 작업 중인 모선: $vesselName"
            binding.tvProgressLabel.text = "전체 진행률: $current / $total"
            binding.pbVessel.max = total
            binding.pbVessel.progress = current
        }

        // 특정 폴더 내 개별 파일 진행률
        viewModel.compressProgress.observe(this) { (current, total) ->
            binding.tvFileProgressLabel.text = "파일 진행률: $current / $total"
            binding.pbFile.max = total
            binding.pbFile.progress = current
        }

        viewModel.isLoading.observe(this) { _ ->
            // 로딩 상태 처리
        }

        viewModel.uiEvent.observe(this) { event ->
            when (event) {
                is VesselViewModel.UiEvent.Success -> {
                    binding.tvStatus.text = "모든 작업이 완료되었습니다."
                    binding.btnStart.isEnabled = true
                    AlertDialog.Builder(this)
                        .setTitle("작업 완료")
                        .setMessage(event.message)
                        .setPositiveButton("확인", null)
                        .show()
                }

                is VesselViewModel.UiEvent.Error -> {
                    binding.tvStatus.text = "오류 발생"
                    binding.btnStart.isEnabled = true
                    Toast.makeText(this, "오류: ${event.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
