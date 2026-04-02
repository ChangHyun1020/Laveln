package com.example.vesselv2.ui.adapter

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.vesselv2.databinding.ItemPhotoBinding
import com.example.vesselv2.data.model.VesselPhoto
import com.example.vesselv2.ui.activity.PhotoViewerActivity

/**
 * PhotoAdapter - 사진 목록 어댑터
 */
class PhotoAdapter(
    private val onDeleteClick: (VesselPhoto) -> Unit
) : ListAdapter<VesselPhoto, PhotoAdapter.PhotoViewHolder>(DiffCallback()) {

    inner class PhotoViewHolder(
        private val binding: ItemPhotoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(photo: VesselPhoto, position: Int) {
            // 이미지 로드 (Glide)
            Glide.with(binding.ivPhoto.context)
                .load(photo.imageUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_close_clear_cancel)
                .centerCrop()
                .into(binding.ivPhoto)

            // 파일 이름 표시
            binding.tvMemo.text = photo.fileName

            // 번호 표시
            val number = photo.fileName
                .substringAfterLast("_")
                .substringBefore(".")
            binding.tvDate.text = if (number.isNotEmpty()) "No.$number" else ""

            // 삭제 버튼
            binding.btnDelete.setOnClickListener { onDeleteClick(photo) }

            // 클릭 시 상세보기
            binding.root.setOnClickListener {
                val context = binding.root.context
                val allPhotos = currentList

                val intent = Intent(context, PhotoViewerActivity::class.java).apply {
                    putStringArrayListExtra(
                        PhotoViewerActivity.EXTRA_IMAGE_URLS,
                        ArrayList(allPhotos.map { it.imageUrl })
                    )
                    putStringArrayListExtra(
                        PhotoViewerActivity.EXTRA_FILE_NAMES,
                        ArrayList(allPhotos.map { it.fileName })
                    )
                    putExtra(PhotoViewerActivity.EXTRA_PHOTO_INDEX, position)
                }
                context.startActivity(intent)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    class DiffCallback : DiffUtil.ItemCallback<VesselPhoto>() {
        override fun areItemsTheSame(old: VesselPhoto, new: VesselPhoto) =
            old.storagePath == new.storagePath
        override fun areContentsTheSame(old: VesselPhoto, new: VesselPhoto) =
            old == new
    }
}
