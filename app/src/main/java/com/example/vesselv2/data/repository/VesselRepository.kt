package com.example.vesselv2.data.repository

import android.content.Context
import android.net.Uri
import com.example.vesselv2.data.model.Vessel
import com.example.vesselv2.data.model.VesselPhoto
import com.example.vesselv2.util.ImageCompressor
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/**
 * VesselRepository
 */
class VesselRepository {

    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private val lashingCol = db.collection(Vessel.COLLECTION)
    private val storageImagesRef = storage.reference.child("images")

    suspend fun getVessels(): List<Vessel> {
        return try {
            val snapshot = lashingCol
                .orderBy(Vessel.FIELD_VESSEL_NAME, Query.Direction.ASCENDING)
                .get()
                .await()

            snapshot.documents.mapNotNull { doc ->
                doc.toObject(Vessel::class.java)?.copy(id = doc.id)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getPhotos(vesselName: String): List<VesselPhoto> {
        return try {
            val folderRef = storageImagesRef.child(vesselName)
            val listResult = folderRef.listAll().await()

            coroutineScope {
                listResult.items
                    .sortedBy { it.name }
                    .map { ref ->
                        async {
                            try {
                                val url = ref.downloadUrl.await().toString()
                                VesselPhoto(
                                    storagePath = ref.path,
                                    fileName = ref.name,
                                    imageUrl = url
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun uploadPhoto(context: Context, vesselName: String, uri: Uri): String? {
        return try {
            val folderRef = storageImagesRef.child(vesselName)
            val existingCount = try {
                folderRef.listAll().await().items.size
            } catch (_: Exception) {
                0
            }

            val nextIndex = existingCount.toString().padStart(2, '0')
            val fileName = "${vesselName}_${nextIndex}.jpg"
            val fileRef = folderRef.child(fileName)

            val compressedData = ImageCompressor.compressFromUri(context, uri)
                ?: throw Exception("Image compression failed")

            fileRef.putBytes(compressedData).await()
            fileRef.downloadUrl.await().toString()

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun deletePhoto(storagePath: String): Boolean {
        return try {
            storage.reference.child(storagePath).delete().await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun getAllVesselFolderNames(): List<String> {
        return try {
            storageImagesRef.listAll().await().prefixes.map { it.name }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun compressExistingPhotos(
        vesselName: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Int {
        return try {
            val folderRef = storageImagesRef.child(vesselName)
            val listResult = folderRef.listAll().await()
            val items = listResult.items
            val total = items.size
            var successCount = 0

            items.forEachIndexed { index, ref ->
                try {
                    val originalData = ref.getBytes(10L * 1024 * 1024).await()
                    val compressedData = ImageCompressor.compressFromBytes(originalData)

                    if (compressedData != null && compressedData.size < originalData.size) {
                        ref.putBytes(compressedData).await()
                        successCount++
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                onProgress(index + 1, total)
            }
            successCount
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }
}
