package com.minseo41.subfeed.data.potoken

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

// Create endpoint raw challenge → JavaScript snippet 에 embed 가능한 JSON 문자열로 변환.
fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JSONArray(rawChallengeData)

    val challengeData: JSONArray = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
        val descrambled = descramble(scrambled.getString(1))
        JSONArray(descrambled)
    } else {
        scrambled.getJSONArray(0)
    }

    val messageId = challengeData.optString(0)
    val interpreterHash = challengeData.optString(3)
    val program = challengeData.optString(4)
    val globalName = challengeData.optString(5)
    val clientExperimentsStateBlob = challengeData.optString(7)

    val safeScript = challengeData.optJSONArray(1)?.let { findFirstString(it) }
    val trustedUrl = challengeData.optJSONArray(2)?.let { findFirstString(it) }

    val interpreter = JSONObject()
        .put("privateDoNotAccessOrElseSafeScriptWrappedValue", safeScript)
        .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", trustedUrl)

    return JSONObject()
        .put("messageId", messageId)
        .put("interpreterJavascript", interpreter)
        .put("interpreterHash", interpreterHash)
        .put("program", program)
        .put("globalName", globalName)
        .put("clientExperimentsStateBlob", clientExperimentsStateBlob)
        .toString()
}

// GenerateIT 응답 → (JS Uint8Array 리터럴, expirationSec) 페어
fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val arr = JSONArray(rawIntegrityTokenData)
    return base64ToU8(arr.getString(0)) to arr.getLong(1)
}

// identifier 를 JS Uint8Array 리터럴로 변환
fun stringToU8(identifier: String): String =
    newUint8Array(identifier.toByteArray())

// JS 가 toString() 으로 돌려준 콤마구분 바이트 배열 → YouTube PoToken base64 표현
fun u8ToBase64(poToken: String): String {
    val bytes = poToken.split(",").map { it.toUByte().toByte() }.toByteArray()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
        .replace("+", "-")
        .replace("/", "_")
        .trimEnd('=')
}

private fun findFirstString(arr: JSONArray): String? {
    for (i in 0 until arr.length()) {
        val v = arr.opt(i)
        if (v is String) return v
    }
    return null
}

// scrambled challenge 를 base64 디코딩 후 각 바이트에 97 더해 원본 복원
private fun descramble(scrambledChallenge: String): String {
    return base64ToByteArray(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()
}

private fun base64ToU8(base64: String): String =
    newUint8Array(base64ToByteArray(base64))

private fun newUint8Array(contents: ByteArray): String =
    "new Uint8Array([" + contents.joinToString(",") { it.toUByte().toString() } + "])"

// YouTube 의 url-safe base64 (- _ . padding) → ByteArray
private fun base64ToByteArray(base64: String): ByteArray {
    val mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')
    return try {
        Base64.decode(mod, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        throw PoTokenException("Cannot base64 decode")
    }
}
