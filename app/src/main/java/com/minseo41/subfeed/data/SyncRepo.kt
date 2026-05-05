package com.minseo41.subfeed.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.minseo41.subfeed.model.WatchPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepo @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) {
    private val uid get() = auth.currentUser?.uid

    // ViewModel scope이 cancel되어도 살아남는 detached scope.
    // 화면 이탈/dispose 시 savePositionNow가 호출되는 시점은 ViewModel이 곧 cleared되므로
    // viewModelScope에서 launch하면 await가 cancel되어 Firestore write가 도중에 끊긴다.
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ViewModel 생명주기와 독립적으로 저장 — 화면 이탈 시 즉시 commit 보장.
    fun savePositionDetached(videoId: String, positionMs: Long) {
        detachedScope.launch { savePosition(videoId, positionMs) }
    }

    // positionMs 저장 — 호출부에서 30초 debounce 적용 후 호출
    suspend fun savePosition(videoId: String, positionMs: Long) {
        val u = uid
        if (u == null) {
            Log.w(TAG, "savePosition skipped — uid=null (sign-in 안 됨)")
            return
        }
        Log.d(TAG, "savePosition start: uid=$u, videoId=$videoId, positionMs=$positionMs")
        val existing = runCatching { getPosition(videoId) }
            .onFailure { Log.e(TAG, "savePosition: getPosition failed", it) }
            .getOrNull()
        // 충돌 정책: 더 큰 positionMs 우선
        if (positionMs <= (existing?.positionMs ?: 0L)) {
            Log.d(TAG, "savePosition skipped — new=$positionMs <= existing=${existing?.positionMs}")
            return
        }
        runCatching {
            firestore.collection("users")
                .document(u)
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
        }.onSuccess {
            Log.d(TAG, "savePosition ok: videoId=$videoId, positionMs=$positionMs")
        }.onFailure { e ->
            Log.e(TAG, "savePosition write failed", e)
        }
    }

    suspend fun getPosition(videoId: String): WatchPosition? {
        val u = uid
        if (u == null) {
            Log.w(TAG, "getPosition skipped — uid=null")
            return null
        }
        Log.d(TAG, "getPosition start: uid=$u, videoId=$videoId")
        val doc = runCatching {
            firestore.collection("users")
                .document(u)
                .collection("positions")
                .document(videoId)
                .get()
                .await()
        }.onFailure { Log.e(TAG, "getPosition read failed", it) }
            .getOrNull() ?: return null
        if (!doc.exists()) {
            Log.d(TAG, "getPosition: doc not found for videoId=$videoId")
            return null
        }
        val pos = WatchPosition(
            videoId = doc.getString("videoId") ?: videoId,
            positionMs = doc.getLong("positionMs") ?: 0L,
            updatedAt = doc.getTimestamp("updatedAt") ?: Timestamp.now(),
        )
        Log.d(TAG, "getPosition ok: videoId=$videoId, positionMs=${pos.positionMs}")
        return pos
    }

    companion object {
        private const val TAG = "SubFeedSync"
    }
}
