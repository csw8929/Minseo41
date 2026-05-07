package com.minseo41.subfeed.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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

    suspend fun savePosition(videoId: String, positionMs: Long) {
        val now = System.currentTimeMillis()

        val localExisting = runCatching { watchPositionDao.get(videoId) }
            .onFailure { Log.e(TAG, "savePosition local read failed", it) }
            .getOrNull()
        if (positionMs > (localExisting?.positionMs ?: 0L)) {
            runCatching {
                watchPositionDao.upsert(WatchPositionEntity(videoId, positionMs, now))
                pruneIfOver()
            }.onFailure { Log.e(TAG, "savePosition local write failed", it) }
        } else {
            Log.d(TAG, "savePosition local skipped — new=$positionMs <= local=${localExisting?.positionMs}")
        }

        val u = uid
        if (u == null) {
            Log.d(TAG, "savePosition Firestore skipped — uid=null")
            return
        }

        val remoteExisting = runCatching { fetchFromFirestore(u, videoId) }
            .onFailure { Log.e(TAG, "savePosition Firestore read failed", it) }
            .getOrNull()
        if (positionMs <= (remoteExisting?.positionMs ?: 0L)) {
            Log.d(TAG, "savePosition Firestore skipped — new=$positionMs <= remote=${remoteExisting?.positionMs}")
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
        val u = uid
        if (u != null) {
            val remote = runCatching { fetchFromFirestore(u, videoId) }
                .onFailure { Log.e(TAG, "getPosition Firestore read failed (fallback to local)", it) }
                .getOrNull()
            if (remote != null) {
                runCatching {
                    watchPositionDao.upsert(
                        WatchPositionEntity(videoId, remote.positionMs, System.currentTimeMillis())
                    )
                }.onFailure { Log.e(TAG, "getPosition local cache update failed", it) }
                Log.d(TAG, "getPosition Firestore ok: videoId=$videoId, positionMs=${remote.positionMs}")
                return remote
            }
        }

        val local = runCatching { watchPositionDao.get(videoId) }
            .onFailure { Log.e(TAG, "getPosition local read failed", it) }
            .getOrNull() ?: return null
        Log.d(TAG, "getPosition local ok: videoId=$videoId, positionMs=${local.positionMs}")
        return WatchPosition(
            videoId = local.videoId,
            positionMs = local.positionMs,
            updatedAt = Timestamp(local.updatedAt / 1000, 0),
        )
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
                if (local.positionMs > (remote?.positionMs ?: 0L)) {
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
