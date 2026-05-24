# NewPipe 솔루션 도입 방안 — 심층 조사 보고서

날짜: 2026-05-24
브랜치: `feat/potoken-2026-05`
조사 배경: PoToken 발급 성공 후에도 seek 시 chunk 403 발생. NewPipe 가 완전히 동작하는 이유 확인 및 도입 방안 검토.

---

## 1. 우리 솔루션이 안 되는 진짜 원인 — Full Picture

YouTube 외부 클라이언트 재생을 안정적으로 하려면 **3개 레이어** 가 모두 필요합니다:

| 레이어 | 우리 상태 | NewPipe 상태 |
|---|---|---|
| **1. PoToken (BotGuard)** — 봇 아님 증명 | ✅ WebView 구현 완료 | ✅ WebView 구현 (NewPipe 가 원조) |
| **2. n-param deobfuscation** — chunk URL `&n=<obfuscated>` 변환 | ❌ 없음 | ✅ base.js → Rhino JS 실행 |
| **3. signature deobfuscation** — `&sig=` cipher 일부 클라이언트 | ❌ 없음 | ✅ base.js → Rhino JS 실행 |

`n` 파라미터가 deobfuscated 안 되면:
- YouTube가 **throttling** 적용 (수 KB/s 로 떨어짐)
- 일부 chunk 요청 → **403**
- seek (mid-video chunk) → 거의 항상 403

이게 우리가 보는 정확한 증상입니다.

---

## 2. NewPipe 전체 아키텍처 — 의존성 & 컴포넌트

```
NewPipe 앱 (GPL-3.0)
├─ util/potoken/                        ← 우리가 이미 포팅 완료
│   ├─ PoTokenWebView.kt                  WebView에서 BotGuard JS 실행
│   ├─ JavaScriptUtil.kt                  challenge 파싱
│   ├─ PoTokenProviderImpl.kt             singleton facade
│   └─ assets/po_token.html               BgUtils JS
│
└─ NewPipeExtractor 라이브러리 (com.github.TeamNewPipe:NewPipeExtractor, GPL-3.0)
    ├─ extractors/YoutubeStreamExtractor.java    1649 lines — 메인 로직
    ├─ YoutubeStreamHelper.java                  268 lines — InnerTube 요청 빌더
    ├─ YoutubeParsingHelper.java                 1580 lines — visitor data, JSON context
    ├─ YoutubeJavaScriptExtractor.java           164 lines — base.js URL 추출
    ├─ YoutubeJavaScriptPlayerManager.java       ── caching layer
    ├─ YoutubeThrottlingParameterUtils.java      280 lines — n-param regex + Rhino 실행
    ├─ YoutubeSignatureUtils.java                169 lines — sig regex + Rhino 실행
    ├─ ClientsConstants.java                     ── UA, version 상수
    ├─ PoTokenProvider.java (interface)          ── 우리 PoTokenWebView를 여기 꽂으면 됨
    ├─ PoTokenResult.java                        ── (visitorData, playerPot, streamingPot)
    └─ dashmanifestcreators/                     ── DASH MPD 생성 (Otf, Progressive 등)
        ├─ YoutubeOtfDashManifestCreator.java        264 lines
        ├─ YoutubeProgressiveDashManifestCreator.java 234 lines
        └─ YoutubeDashManifestCreatorsUtils.java

NewPipeExtractor 의 외부 의존성:
- mozilla-rhino 1.8.1 (JS 인터프리터, ~1.5MB) — n-param/sig 실행
- com.google.protobuf:protobuf-javalite 4.35.0 ← Firebase Firestore 와 충돌 지점
- jsoup 1.22.2 (HTML 파싱)
- nanojson (TeamNewPipe fork)
- jsr305
```

---

## 3. Protobuf 충돌 — 해결 방법 확인됨

CLAUDE.md 에 "NewPipe Extractor 는 protobuf 가 Firestore 와 충돌해서 제거" 라고 적혀 있는데, 이제는 표준 해결책이 있습니다:

```kotlin
// app/build.gradle.kts
configurations.all {
    resolutionStrategy {
        force("com.google.protobuf:protobuf-javalite:4.27.5") // Firebase BOM 33.7 매치
    }
}
```

Firebase BOM 33.7 의 Firestore 는 protobuf-javalite 4.27.5 를 씁니다. NewPipeExtractor 가 4.35 를 들고 오지만 위 force 로 통일하면 충돌 사라집니다. ([참고](https://medium.com/@kayushi07/resolving-protobuf-duplicate-class-conflicts-in-android-applications-ef8a357b733f))

APK 크기 증가 예상: **약 2MB** (Rhino 1.5MB + jsoup/nanojson 0.5MB).

---

## 4. 세 가지 도입 옵션

### Option A: NewPipeExtractor 라이브러리 전체 도입 + 우리 PoToken 연결 (권장)

**작업 내용:**
1. `libs.versions.toml` 에 NewPipeExtractor 추가
2. `app/build.gradle.kts` 에 protobuf force + dependency
3. `Downloader` 인터페이스 구현 (OkHttp wrapper, ~60줄)
4. NewPipeExtractor 의 `PoTokenProvider` 구현 — 우리 `PoTokenWebView` 위임 (~40줄)
5. `SubFeedApp.onCreate()` 에서 `NewPipe.init(downloader, localization)` + `YoutubeStreamExtractor.setPoTokenProvider(...)`
6. `NewPipeVideoExtractor.getStreamInfo()` 를 NewPipe extractor 호출로 교체 (~80줄)
7. RSS 채널 피드는 기존 코드 유지 (단순함)

**총 변경**: 새 코드 ~200줄, 기존 코드 ~150줄 삭제, Gradle 변경.

**Pros:**
- ✅ PoToken + n-param + signature + DASH manifest 모두 해결
- ✅ YouTube 정책 변경 시 라이브러리 업데이트로 대응 (`./gradlew assembleDebug` 만 하면 됨)
- ✅ 우리 PoTokenWebView 그대로 재사용 (이미 작동 확인됨)
- ✅ 코드량 오히려 줄어듦

**Cons:**
- ❌ APK 약 +2MB
- ❌ **GPL-3.0 라이센스** — 앱 배포 시 GPL 의무 (개인 사용 전제이므로 무관)
- ❌ protobuf 버전 force 필요 (한 번 설정하면 끝)

### Option B: n-param/signature 직접 포팅

**작업 내용:**
1. base.js fetcher (~50줄)
2. n-function regex 추출 (~150줄, NewPipe 코드 복제)
3. JavaScript 실행기:
   - 옵션 B-1: Rhino 의존성 추가 (~1.5MB, GPL-호환 BSD/MPL)
   - 옵션 B-2: 우리 PoTokenWebView 재사용해 JS eval (메모리 efficient 하지만 await 처리 복잡)
4. URL 후처리 파이프라인 (~80줄)
5. DASH manifest builder (현재 코드 유지/개선)

**총 변경**: 새 코드 ~500~800줄, Rhino 추가 시 ~1.5MB.

**Pros:**
- ✅ GPL 의무 없음 (라이센스 자유)
- ✅ 우리 코드 통제

**Cons:**
- ❌ YouTube 가 regex 패턴 바꾸면 우리가 직접 추적/수정 (yt-dlp 보면 거의 매월 발생)
- ❌ 유지 보수 부담 큼
- ❌ 코드량 더 많음
- ❌ APK 크기 절감도 미미 (Rhino 도 ~1.5MB)

### Option C: 하이브리드 (Option A 의 변형)

NewPipeExtractor 만 도입, 우리 PoToken 구현은 폐기하고 NewPipe 가 제공하는 PoTokenProviderImpl 도 그대로 사용.

작업량은 Option A 와 동일하지만 우리가 이미 디버그 끝낸 PoTokenWebView 를 버리는 게 아까움. **Option A 가 더 합리적.**

---

## 5. 추천: Option A — NewPipeExtractor 도입 + 우리 PoToken 연결

### 근거

1. **사용자의 안정성 우선 요구사항과 일치** — 라이브러리 메인테이너가 YouTube 변경 추적
2. **이미 한 작업 보존** — 우리 PoTokenWebView 가 `PoTokenProvider` 인터페이스에 그대로 들어감
3. **개인 사용** — GPL-3.0 무관
4. **2MB APK 증가** — 일반 영상앱 대비 미미한 수준
5. **코드량 감소** — 직접 InnerTube 호출 코드 ~300줄, DASH 빌더 ~150줄 모두 제거 가능

### 구현 단계 (TODO)

- [ ] **B1**. `app/build.gradle.kts` 에 protobuf resolution force 추가
- [ ] **B2**. `libs.versions.toml` + `app/build.gradle.kts` 에 NewPipeExtractor JitPack 의존성 추가
- [ ] **B3**. `OkHttpDownloader` → NewPipe `Downloader` 어댑터 작성 (`SubFeedDownloader.kt`)
- [ ] **B4**. NewPipe `PoTokenProvider` 구현체 작성 — 우리 `PoTokenWebView` 위임 (`SubFeedPoTokenProvider.kt`)
- [ ] **B5**. `SubFeedApp.onCreate()` 에 `NewPipe.init(...)` + `YoutubeStreamExtractor.setPoTokenProvider(...)` 호출
- [ ] **B6**. `NewPipeVideoExtractor.getStreamInfo()` 를 NewPipe extractor 호출로 교체
- [ ] **B7**. ProGuard 룰 추가 (Rhino reflection 대비)
- [ ] **B8**. 플립 단말 검증 (재생 + seek + 시간 경과 + 자막)
- [ ] **B9**. 기존 PoTokenWebView 코드 정리 (인터페이스만 남기고 호환 wrapper 작성)

### 리스크

- 첫 빌드에서 protobuf duplicate class 에러 — force 로 해결
- ProGuard/R8 가 Rhino 의 reflection 사용 클래스 제거 — `consumer-rules.pro` 룰 추가 필요
- NewPipe 최신 버전과 호환되는 PoToken interface 변경 가능 — `v0.26.x` 명시적 핀

---

## 6. 다음 액션

사용자 결정 필요:

1. **A 진행 (라이브러리 도입)** — 권장. 2~3시간 작업, 추후 안정.
2. **B 진행 (전체 포팅)** — 1~2일 작업, 매월 유지 보수.
3. **현재 PoToken만 보존, 다른 길 모색** — 예를 들어 YouTube 로그인 OAuth 도입.

조사 결과상 **A** 가 가장 합리적입니다. 진행 결정해 주세요.
