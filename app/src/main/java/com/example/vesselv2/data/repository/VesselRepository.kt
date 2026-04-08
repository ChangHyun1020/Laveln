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
 * [Repository] VesselRepository — Firebase 데이터 접근 계층
 *
 * ▶ 역할:
 *   VesselViewModel과 Firebase(Firestore, Storage) 사이의 중간 계층입니다.
 *   ViewModel이 데이터 출처를 알 필요 없이 이 Repository를 통해서만 데이터를 요청합니다.
 *   (Repository Pattern — Clean Architecture 기반)
 *
 * ▶ 사용하는 Firebase 서비스:
 *   - Firestore: 모선 메타데이터 저장/조회 (컬렉션: Vessel.COLLECTION = "Lashing")
 *   - Storage: 모선 현장 사진 저장/조회/삭제 (경로: images/{vesselName}/)
 *
 * ▶ 모든 함수는 suspend 함수 — 반드시 코루틴(viewModelScope) 내에서 호출해야 합니다.
 *
 * ▶ 이미지 압축:
 *   사진 업로드 전 ImageCompressor.compressFromUri()로 압축하여
 *   Firebase Storage 사용량과 로딩 시간을 줄입니다.
 *
 * ⚠️ 보안:
 *   - google-services.json은 .gitignore에 포함되어 Git에 업로드되지 않습니다.
 *   - Firebase Security Rules를 통해 인증된 사용자만 접근 가능하도록 설정을 권장합니다.
 */
class VesselRepository {

    // Firebase 인스턴스 (앱 단위 싱글톤)
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    /** Firestore 모선 컬렉션 참조 (Vessel.COLLECTION = "Lashing") */
    private val lashingCol = db.collection(Vessel.COLLECTION)

    /** Storage 이미지 루트 참조 (images/) */
    private val storageImagesRef = storage.reference.child("images")

    // ────────────────────────────────────────────────────────────────────────
    //  모선 목록 조회
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Firestore에서 등록된 모선 목록을 모선명 오름차순으로 조회합니다.
     *
     * - 정렬 기준: vesselName 오름차순 (알파벳 순)
     * - 오류 시: 빈 리스트 반환 (앱 크래시 방지)
     *
     * @return 모선 목록 (오류 시 emptyList())
     */
    suspend fun getVessels(): List<Vessel> {
        return try {
            val snapshot = lashingCol
                .orderBy(Vessel.FIELD_VESSEL_NAME, Query.Direction.ASCENDING)
                .get()
                .await() // Firebase Task → suspend 변환

            // Firestore 문서를 Vessel 데이터 클래스로 매핑
            // id 필드는 Firestore 문서 ID (= vesselName 대문자)
            snapshot.documents.mapNotNull { doc ->
                doc.toObject(Vessel::class.java)?.copy(id = doc.id)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  사진 목록 조회
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Firebase Storage에서 특정 모선의 사진 목록을 조회합니다.
     *
     * - 경로: images/{vesselName}/
     * - 파일명 오름차순 정렬 (_00, _01, _02, ... 순서 보장)
     * - 각 파일의 downloadUrl을 병렬로 조회 (coroutineScope + async)
     * - 오류 시: 해당 사진은 null로 처리 후 filterNotNull()로 제외
     *
     * @param vesselName Storage 폴더명 (= 모선명 대문자)
     * @return VesselPhoto 리스트 (오류 시 emptyList())
     */
    suspend fun getPhotos(vesselName: String): List<VesselPhoto> {
        return try {
            val folderRef = storageImagesRef.child(vesselName)
            val listResult = folderRef.listAll().await()

            coroutineScope {
                listResult.items
                    .sortedBy { it.name } // 파일명으로 정렬 (삽입 순서 보장)
                    .map { ref ->
                        async {
                            try {
                                // 각 파일의 HTTPS 다운로드 URL 취득
                                val url = ref.downloadUrl.await().toString()
                                VesselPhoto(
                                    storagePath = ref.path,
                                    fileName = ref.name,
                                    imageUrl = url
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null // 개별 사진 실패 시 null 반환 → 이후 filterNotNull()로 제외
                            }
                        }
                    }
                    .awaitAll()       // 모든 async 블록이 완료될 때까지 대기
                    .filterNotNull()  // 조회 실패한 사진 제외
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  사진 업로드 (단건)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 새 사진을 Firebase Storage에 업로드합니다.
     *
     * ▶ 파일명 규칙: {vesselName}_{인덱스 2자리}.jpg (예: OCEANBIRD_02.jpg)
     *   - 기존 파일 수를 카운트하여 다음 인덱스 사용
     *   - 겹침 방지: 기존 파일명을 확인하여 순차 증가
     *
     * ▶ 이미지 압축:
     *   ImageCompressor.compressFromUri()로 압축 후 업로드
     *   (원본 그대로 업로드 시 용량 과다 발생 방지)
     *
     * @param context 이미지 URI 읽기에 필요한 Context
     * @param vesselName Storage 경로 (= 모선명 대문자)
     * @param uri 선택된 이미지 URI (갤러리 또는 카메라)
     * @return 업로드된 파일의 HTTPS 다운로드 URL, 실패 시 null
     */
    suspend fun uploadPhoto(context: Context, vesselName: String, uri: Uri): String? {
        return try {
            val folderRef = storageImagesRef.child(vesselName)
            // 현재 폴더에 있는 파일 수 조회 (실패 시 0으로 처리)
            val existingCount = try {
                folderRef.listAll().await().items.size
            } catch (_: Exception) {
                0
            }

            // 파일명 생성: {vesselName}_{인덱스 2자리}.jpg
            val nextIndex = existingCount.toString().padStart(2, '0')
            val fileName = "${vesselName}_${nextIndex}.jpg"
            val fileRef = folderRef.child(fileName)

            // 이미지 압축 (실패 시 예외 발생 → null 반환)
            val compressedData = ImageCompressor.compressFromUri(context, uri)
                ?: throw Exception("이미지 압축 실패")

            // 압축된 바이트 배열 업로드
            fileRef.putBytes(compressedData).await()
            // 업로드 완료 후 다운로드 URL 반환
            fileRef.downloadUrl.await().toString()

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  사진 삭제 (단건)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Firebase Storage에서 특정 사진을 삭제합니다.
     *
     * @param storagePath 삭제할 파일의 Storage 전체 경로 (예: "images/OCEANBIRD/OCEANBIRD_00.jpg")
     * @return 삭제 성공 여부 (true: 성공, false: 실패)
     */
    suspend fun deletePhoto(storagePath: String): Boolean {
        return try {
            storage.reference.child(storagePath).delete().await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  일괄 압축 관련
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Storage images/ 경로 하위의 모든 모선 폴더명 목록을 반환합니다.
     * BulkCompressActivity에서 압축 대상 폴더 목록을 가져오는 데 사용합니다.
     *
     * @return 모선명 목록 (폴더명만 반환, 오류 시 emptyList())
     */
    suspend fun getAllVesselFolderNames(): List<String> {
        return try {
            storageImagesRef.listAll().await().prefixes.map { it.name }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 특정 모선의 사진을 다운로드하여 압축 후 재업로드합니다. (일괄 압축)
     *
     * ▶ 처리 방식:
     *   1. 각 파일을 다운로드 (최대 10MB)
     *   2. ImageCompressor.compressFromBytes()로 압축
     *   3. 압축본이 원본보다 작은 경우에만 재업로드 (용량 역전 방지)
     *   4. onProgress 콜백으로 진행률 실시간 전달
     *
     * @param vesselName 압축할 모선의 폴더명
     * @param onProgress 진행률 콜백: (현재 파일 번호, 전체 파일 수)
     * @return 실제 압축이 적용된 파일 수 (원본보다 커진 파일은 제외)
     */
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
                    // 다운로드 (최대 10MB 제한)
                    val originalData = ref.getBytes(10L * 1024 * 1024).await()
                    val compressedData = ImageCompressor.compressFromBytes(originalData)

                    // 압축 결과가 원본보다 작을 때만 재업로드
                    if (compressedData != null && compressedData.size < originalData.size) {
                        ref.putBytes(compressedData).await()
                        successCount++
                    }
                } catch (e: Exception) {
                    // 개별 파일 오류는 로깅하고 계속 진행
                    e.printStackTrace()
                }
                onProgress(index + 1, total) // 진행률 콜백 호출
            }
            successCount
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }
}
