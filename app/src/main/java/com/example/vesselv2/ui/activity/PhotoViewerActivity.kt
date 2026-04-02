package com.example.vesselv2.ui.activity

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.vesselv2.databinding.ActivityPhotoViewerBinding
import com.example.vesselv2.ui.adapter.PhotoPagerAdapter
import com.example.vesselv2.util.setupEdgeToEdgeInsets

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewerBinding
    private lateinit var pagerAdapter: PhotoPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdgeInsets(binding.topBar, binding.btnRotate)

        val urls = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS) ?: arrayListOf()
        val names = intent.getStringArrayListExtra(EXTRA_FILE_NAMES) ?: arrayListOf()
        val index = intent.getIntExtra(EXTRA_PHOTO_INDEX, 0)

        setupViewPager(urls, names, index)
        setupButtons()
    }

    // ──────────────────────────────────────────────────────────────────
    //  ViewPager2 세팅
    // ──────────────────────────────────────────────────────────────────

    private fun setupViewPager(
        urls: ArrayList<String>,
        names: ArrayList<String>,
        startIndex: Int
    ) {
        pagerAdapter = PhotoPagerAdapter(urls) { toggleBars() }

        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.setCurrentItem(startIndex, false)

        // 초기 페이지 표시
        updatePageInfo(startIndex, names, urls.size)

        binding.viewPager.registerOnPageChangeCallback(
            object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                @SuppressLint("SetTextI18n")
                override fun onPageSelected(position: Int) {
                    updatePageInfo(position, names, urls.size)
                }
            }
        )
    }

    @SuppressLint("SetTextI18n")
    private fun updatePageInfo(position: Int, names: List<String>, total: Int) {
        binding.tvFileName.text = names.getOrElse(position) { "" }
        binding.tvPhotoIndex.text = "${position + 1} / $total"
    }

    // ──────────────────────────────────────────────────────────────────
    //  버튼 처리
    // ──────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        // 닫기
        binding.btnClose.setOnClickListener { finish() }

        // ✅ 회전: 현재 페이지 PhotoView 를 90° 시계 방향 회전
        binding.btnRotate.setOnClickListener {
            pagerAdapter.rotateCurrentItem(binding.viewPager)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  탭 → 상단/하단 바 토글 (몰입 모드)
    // ──────────────────────────────────────────────────────────────────

    private fun toggleBars() {
        val isVisible = binding.topBar.visibility == View.VISIBLE
        val targetVisibility = if (isVisible) View.GONE else View.VISIBLE

        binding.topBar.visibility = targetVisibility
        binding.bottomBar.visibility = targetVisibility
    }

    companion object {
        const val EXTRA_VESSEL_NAME = "EXTRA_VESSEL_NAME"
        const val EXTRA_PHOTO_URL = "EXTRA_PHOTO_URL"
        const val EXTRA_IMAGE_URLS = "EXTRA_IMAGE_URLS"
        const val EXTRA_FILE_NAMES = "EXTRA_FILE_NAMES"
        const val EXTRA_PHOTO_INDEX = "EXTRA_PHOTO_INDEX"
    }
}
