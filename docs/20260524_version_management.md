# 버전 관리 방식 — VERSION 파일 단일 소스화

## 현황 및 문제

현재 버전 정보가 두 곳에 분산되어 있어 수동으로 맞춰야 한다.

| 파일 | 현재 값 |
|---|---|
| `VERSION` | `1.1.0.0` |
| `app/build.gradle.kts` (`versionName`) | `"1.0"` (동기화 안 됨) |

PR #20, #21 에서 `VERSION` 파일은 업데이트했지만 `build.gradle.kts`는 누락되어 APK에 찍히는 버전이 실제 릴리즈 버전과 다르다.

## 해결 방법 — Gradle에서 VERSION 파일 자동 읽기

`app/build.gradle.kts`를 다음과 같이 수정해 `VERSION` 파일을 단일 소스로 삼는다.

### build.gradle.kts 수정

`android { ... }` 블록 바로 위에 추가:

```kotlin
val appVersion = rootProject.file("VERSION").readText().trim()
val versionParts = appVersion.split(".")
val computedVersionCode =
    versionParts[0].toInt() * 1000 +
    versionParts[1].toInt() * 100 +
    versionParts[2].toInt() * 10 +
    versionParts[3].toInt()
```

`defaultConfig` 블록 수정:

```kotlin
defaultConfig {
    // ...
    versionCode = computedVersionCode
    versionName = appVersion
}
```

### versionCode 인코딩 규칙

`major*1000 + minor*100 + patch*10 + build`

| VERSION | versionCode |
|---|---|
| 1.0.0.0 | 1000 |
| 1.0.1.0 | 1010 |
| 1.1.0.0 | 1100 |

## 이후 버전 올리는 방법

`VERSION` 파일만 수정하면 빌드 시 `versionName`/`versionCode` 자동 반영.

```
# VERSION 파일
1.2.0.0
```

## 검증 방법

```bash
./gradlew assembleDebug
aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep version
# → versionName='1.1.0.0' versionCode='1100'
```
