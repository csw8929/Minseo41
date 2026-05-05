package com.minseo41.subfeed.data

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.minseo41.subfeed.model.WatchPosition
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepo @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) {
    private val uid get() = auth.currentUser?.uid

    // positionMs 저장 — 호출부에서 30초 debounce 적용 후 호출
    suspend fun savePosition(videoId: String, positionMs: Long) {
        val uid = uid ?: return
        val existing = getPosition(videoId)
        // 충돌 정책: 더 큰 positionMs 우선
        if (positionMs <= (existing?.positionMs ?: 0L)) return

        firestore.collection("users")
            .document(uid)
            .collection("positions")
            .document(videoId)
            .set(
                mapOf(
                    "videoId" to videoId,
                    "positionMs" to positionMs,
                    "updatedAt" to Timestamp.now(),
                )
            )
            .await()
    }

    suspend fun getPosition(videoId: String): WatchPosition? {
        val uid = uid ?: return null
        val doc = firestore.collection("users")
            .document(uid)
            .collection("positions")
            .document(videoId)
            .get()
            .await()
        if (!doc.exists()) return null
        return WatchPosition(
            videoId = doc.getString("videoId") ?: videoId,
            positionMs = doc.getLong("positionMs") ?: 0L,
            updatedAt = doc.getTimestamp("updatedAt") ?: Timestamp.now(),
        )
    }
}
