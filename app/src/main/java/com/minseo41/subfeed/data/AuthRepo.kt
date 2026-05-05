package com.minseo41.subfeed.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
) {
    // google-services plugin이 자동 생성하는 default_web_client_id를 런타임 lookup.
    // google-services.json이 없으면 빈 문자열 — sign-in 시도 시 실패하지만 빌드는 통과.
    private val webClientId: String by lazy {
        val resId = context.resources.getIdentifier(
            "default_web_client_id", "string", context.packageName,
        )
        val value = if (resId != 0) context.getString(resId) else ""
        Log.d(TAG, "webClientId resolved: empty=${value.isEmpty()}, prefix=${value.take(12)}")
        value
    }

    private val signInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .apply { if (webClientId.isNotEmpty()) requestIdToken(webClientId) }
            .requestEmail()
            .build()
        Log.d(TAG, "GoogleSignInClient built (requestIdToken=${webClientId.isNotEmpty()})")
        GoogleSignIn.getClient(context, gso)
    }

    val currentUser: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    fun signInIntent(): Intent {
        Log.d(TAG, "signInIntent requested")
        return signInClient.signInIntent
    }

    suspend fun handleSignInResult(data: Intent?): Result<FirebaseUser> {
        Log.d(TAG, "handleSignInResult: data=$data")
        val result = runCatching {
            val account = try {
                GoogleSignIn.getSignedInAccountFromIntent(data).await()
            } catch (e: ApiException) {
                Log.e(TAG, "GoogleSignIn ApiException statusCode=${e.statusCode}", e)
                throw e
            } ?: error("Google 계정 정보를 가져올 수 없습니다 (account=null)")
            Log.d(
                TAG,
                "Google account: email=${account.email}, " +
                    "hasIdToken=${account.idToken != null}, " +
                    "displayName=${account.displayName}",
            )
            val idToken = account.idToken ?: error("ID 토큰이 없습니다 — webClientId 설정 확인")
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            val user = authResult.user ?: error("Firebase 사용자가 null입니다")
            Log.d(TAG, "Firebase signIn ok: uid=${user.uid}, email=${user.email}")
            user
        }
        result.onFailure { Log.e(TAG, "handleSignInResult failed", it) }
        return result
    }

    suspend fun signOut() {
        Log.d(TAG, "signOut start")
        auth.signOut()
        runCatching { signInClient.signOut().await() }
            .onFailure { Log.w(TAG, "GoogleSignInClient.signOut failed", it) }
        Log.d(TAG, "signOut done")
    }

    companion object {
        private const val TAG = "SubFeedAuth"
    }
}
