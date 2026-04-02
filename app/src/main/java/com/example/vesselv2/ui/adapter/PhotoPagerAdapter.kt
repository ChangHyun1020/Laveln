package com.example.vesselv2.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.vesselv2.databinding.ItemPhotoPageBinding

/**
 * PhotoPagerAdapter
 *  - ViewPager2 전체화면 사진 뷰어용 어댑터
 *  - PhotoView: 핀치줌/더블탭줌 기본 지원
 *  - rotateCurrentItem(): 현재 페이지를 90° 애니메이션 회전
 */
class PhotoPagerAdapter(
    private val urls: List<String>,
    private val onTap: () -> Unit          // 탭 → 상단/하단 바 토글
) : RecyclerView.Adapter<PhotoPagerAdapter.PhotoPageViewHolder>() {

    // ── ViewHolder ────────────────────────────────────────────────────

    inner class PhotoPageViewHolder(
        private val binding: ItemPhotoPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /** 현재 축적된 회전 각도 (0 / 90 / 180 / 270) */
        private var currentRotation = 0f

        fun bind(url: String) {
            // PhotoView 핀치줌/더블탭 충돌 방지 → ViewPager2 터치 위임 차단
            binding.photoView.setOnMatrixChangeListener {
                // 줌 상태에 따라 ViewPager2 스크롤 활성 여부 제어
                val suppressed = binding.photoView.scale > 1.01f
                binding.photoView.parent?.requestDisallowInterceptTouchEvent(suppressed)
            }

            // 탭 → 바 토글 콜백 전달
            binding.photoView.setOnPhotoTapListener { _, _, _ -> onTap() }
            binding.photoView.setOnOutsidePhotoTapListener { onTap() }

            // Glide 로드
            Glide.with(binding.photoView.context)
                .load(url)
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .into(binding.photoView)

            binding.progressBar.animate()
                .alpha(0f)
                .setStartDelay(300)
                .setDuration(300)
                .withEndAction { binding.progressBar.visibility = android.view.View.GONE }
                .start()
        }

        /**
         * 현재 페이지를 시계 방향으로 90° 회전 (애니메이션 포함)
         * 회전은 View의 rotation 속성으로 처리 → PhotoView 터치 응답 유지
         */
        fun rotate() {
            currentRotation = (currentRotation + 90f) % 360f
            binding.photoView.animate()
                .rotation(currentRotation)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    // ── Adapter 오버라이드 ────────────────────────────────────────────

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoPageViewHolder {
        val binding = ItemPhotoPageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PhotoPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoPageViewHolder, position: Int) {
        holder.bind(urls[position])
    }

    override fun getItemCount() = urls.size

    // ── 외부 공개 메서드 ──────────────────────────────────────────────

    /**
     * ViewPager2 현재 페이지의 PhotoView 를 90° 회전
     * Activity 에서 btnRotate 클릭 시 호출
     */
    fun rotateCurrentItem(viewPager: ViewPager2) {
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return
        val holder = recyclerView.findViewHolderForAdapterPosition(viewPager.currentItem)
                as? PhotoPageViewHolder ?: return
        holder.rotate()
    }
}
