package com.minseo41.subfeed.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.minseo41.subfeed.data.db.WatchPositionDao
import com.minseo41.subfeed.data.db.WatchPositionEntity
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
    private val watchPositionDao: WatchPositionDao,
) {
    private val uid get() = auth.currentUser?.uid

    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastUid: String? = auth.currentUser?.uid

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val newUid = firebaseAuth.currentUser?.uid
            val prevUid = lastUid
            lastUid = newUid
            if (prevUid == null && newUid != null) {
                Log.d(TAG, "AuthState transition null -> $newUid, scheduling local->Firestore sync")
                detachedScope.launch { syncLocalToFirestoreOnSignIn() }
            }
        }
    }

    fun savePositionDetached(videoId: String, positionMs: Long) {
        detachedScope.launch { savePosition(videoId, positionMs) }
    }

    fun recordExternalLaunchDetached(videoId: String) {
        detachedScope.launch {
            val u = uid ?: return@launch
            runCatching {
                firestore.collection("users")
                    .document(u)
                    .collection("positions")
                    .document(videoId)
                    .set(mapOf("launchedAt" to Timestamp.now()), SetOptions.merge())
                    .await()
            }.onSuccess {
                Log.d(TAG, "recordExternalLaunch ok: videoId=$videoId")
            }.onFailure { e ->
                Log.e(TAG, "recordExternalLaunch failed: videoId=$videoId", e)
            }
        }
    }

    suspend fun fetchWatchedVideoIds(videoIds: List<String>): Set<String> {
        val u = uid ?: return emptySet()
        val result = mutableSetOf<String>()
        for (chunk in videoIds.chunked(30)) {
            runCatching {
                firestore.collection("users")
                    .document(u)
                    .collection("positions")
                    .whereIn(FieldPath.documentId(), chunk)
                    .get()
                    .await()
            }.onSuccess { snapshot ->
                for (doc in snapshot.documents) {
                    if (doc.contains("launchedAt") || (doc.getLong("positionMs") ?: 0L) > 0L) {
                        result.add(doc.id)
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "fetchWatchedVideoIds chunk failed", e)
            }
        }
        return result
    }

    // 충돌 해소: "최신 updatedAt이 이긴다" — 큰 값 우선이 아닌 시각 우선.
    // 재시청으로 작은 값을 써도 정상 반영되며, 다른 기기가 더 최신 상태일 땐 덮어쓰지 않음.
    suspend fun savePosition(videoId: String, positionMs: Long) {
        val now = System.currentTimeMillis()

        // 로컬은 이 기기의 가장 최근 의도 — 항상 덮어씀.
        runCatching {
            watchPositionDao.upsert(WatchPositionEntity(videoId, positionMs, now))
            pruneIfOver()
        }.onFailure { Log.e(TAG, "savePosition local write failed", it) }

        val u = uid
        if (u == null) {
            Log.d(TAG, "savePosition Firestore skipped — uid=null")
            return
        }

        val remoteExisting = runCatching { fetchFromFirestore(u, videoId) }
            .onFailure { Log.e(TAG, "savePosition Firestore read failed", it) }
            .getOrNull()
        val remoteUpdatedAtMs = remoteExisting?.updatedAt?.toDate()?.time ?: 0L
        if (now <= remoteUpdatedAtMs) {
            Log.d(TAG, "savePosition Firestore skipped — now=$now <= remote updatedAt=$remoteUpdatedAtMs")
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
            Log.d(TAG, "savePosition Firestore ok: videoId=$videoId, positionMs=$positionMs")
        }.onFailure { e ->
            Log.e(TAG, "savePosition Firestore write failed", e)
        }
    }

    suspend fun getPosition(videoId: String): WatchPosition? {
        val local = runCatching { watchPositionDao.get(videoId) }
            .onFailure { Log.e(TAG, "getPosition local read failed", it) }
            .getOrNull()
        val localAsModel = local?.let {
            WatchPosition(
                videoId = it.videoId,
                positionMs = it.positionMs,
                updatedAt = Timestamp(it.updatedAt / 1000, 0),
            )
        }

        val u = uid
        if (u == null) {
            if (localAsModel != null) {
                Log.d(TAG, "getPosition local-only (uid=null): videoId=$videoId, positionMs=${local!!.positionMs}")
            }
            return localAsModel
        }

        val remote = runCatching { fetchFromFirestore(u, videoId) }
            .onFailure { Log.e(TAG, "getPosition Firestore read failed (fallback to local)", it) }
            .getOrNull()

        if (remote == null) return localAsModel

        // 최신 updatedAt이 이긴다. 로컬이 더 최신이면 로컬 사용, 아니면 Firestore + 로컬 캐시 갱신.
        val remoteUpdatedAtMs = remote.updatedAt.toDate().time
        val localUpdatedAtMs = local?.updatedAt ?: -1L

        return if (localUpdatedAtMs > remoteUpdatedAtMs) {
            Log.d(TAG, "getPosition: local newer — videoId=$videoId, local=$localUpdatedAtMs > remote=$remoteUpdatedAtMs")
            localAsModel
        } else {
            Log.d(TAG, "getPosition: remote newer/tied — videoId=$videoId, remote=$remoteUpdatedAtMs >= local=$localUpdatedAtMs")
            runCatching {
                watchPositionDao.upsert(
                    WatchPositionEntity(videoId, remote.positionMs, remoteUpdatedAtMs)
                )
            }.onFailure { Log.e(TAG, "getPosition local cache update failed", it) }
            remote
        }
    }

    private suspend fun fetchFromFirestore(uid: String, videoId: String): WatchPosition? {
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

    private suspend fun pruneIfOver() {
        val n = runCatching { watchPositionDao.count() }.getOrNull() ?: return
        if (n > LOCAL_MAX_ROWS) {
            val toDelete = n - LOCAL_MAX_ROWS
            runCatching { watchPositionDao.pruneOldest(toDelete) }
                .onSuccess { Log.d(TAG, "pruned $toDelete oldest local watch_positions (count was $n)") }
                .onFailure { Log.e(TAG, "pruneOldest failed", it) }
        }
    }

    private suspend fun syncLocalToFirestoreOnSignIn() {
        val u = uid ?: return
        val locals = runCatching { watchPositionDao.getAll() }
            .onFailure { Log.e(TAG, "syncLocalToFirestoreOnSignIn local read failed", it) }
            .getOrNull() ?: return
        Log.d(TAG, "syncLocalToFirestoreOnSignIn start: ${locals.size} local rows")
        var pushed = 0
        for (local in locals) {
            runCatching {
                val remote = fetchFromFirestore(u, local.videoId)
                val remoteUpdatedAtMs = remote?.updatedAt?.toDate()?.time ?: 0L
                if (local.updatedAt > remoteUpdatedAtMs) {
                    firestore.collection("users")
                        .document(u)
                        .collection("positions")
                        .document(local.videoId)
                        .set(
                            mapOf(
                                "videoId" to local.videoId,
                                "positionMs" to local.positionMs,
                                "updatedAt" to Timestamp.now(),
                            )
                        )
                        .await()
                    pushed++
                }
            }.onFailure { Log.e(TAG, "syncLocalToFirestoreOnSignIn upload failed for ${local.videoId}", it) }
        }
        Log.d(TAG, "syncLocalToFirestoreOnSignIn done: pushed=$pushed of ${locals.size}")
    }

    companion object {
        private const val TAG = "SubFeedSync"
        private const val LOCAL_MAX_ROWS = 5000
    }
}
