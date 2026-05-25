# NewPipe Extractor 유지보수 가이드

YouTube 스트림 추출이 깨질 때마다 NewPipe Extractor 최신 버전으로 흡수하는 절차와 누적 지식. **현재 SubFeed가 의존하는 버전**: `gradle/libs.versions.toml`의 `newpipe = "v0.26.2"`.

배경 분석은 [`20260524_newpipe_solution_analysis.md`](20260524_newpipe_solution_analysis.md), 도입 과정은 [`20260524_potoken_implementation.md`](20260524_potoken_implementation.md) 참조.

---

## NewPipe Extractor가 SubFeed에서 하는 일

| 영역 | 깨지는가? | 관련 NewPipe 모듈 |
|---|---|---|
| RSS 피드 파싱 (channel feed XML) | 거의 안 깨짐 | XML 파서만, YouTube 외부 API |
| 스트림 URL 추출 (InnerTube API) | 자주 | `YoutubeStreamExtractor`, `YoutubeStreamHelper` |
| n-param deobfuscation (chunk URL throttling 해제) | 자주 | `YoutubeThrottlingParameterUtils`, `YoutubeJavaScriptExtractor` |
| signature deobfuscation (sig cipher) | 가끔 | `YoutubeSignatureUtils` |
| PoToken 통합 (BotGuard 토큰 받기) | 가끔 | `PoTokenProvider` interface — SubFeed에서 직접 구현 |
| DASH manifest 생성 (1080p+ adaptive) | 가끔 | `dashmanifestcreators/` (`YoutubeProgressiveDashManifestCreator` 등) |

**판단 기준**: 재생이 깨지면 99% 스트림 측. RSS는 별개 — 채널 목록 갱신 깨짐은 다른 원인.

---

## 깨짐 사례 기록 (시간순)

### 2026-05 — PoToken 도입 + n-param 통합

- **증상**: 짧은 재생 후 chunk 403, seek 시 거의 항상 403
- **원인**: YouTube 외부 클라이언트 차단 강화 — PoToken(BotGuard) 요구 + n-param JavaScript 난독화
- **NewPipe 해결**: v0.26.x — Rhino JS 인터프리터로 n-param/signature 실행, PoTokenProvider interface로 외부 위임
- **SubFeed 흡수**: PR #21 (`feat/potoken-2026-05`). v1.1.0.0
- **남은 우리 코드**: `PoTokenWebView` (BgUtils JS 실행) — NewPipe의 `PoTokenProvider`에 위임됨

### (다음 깨짐 사례를 여기 추가)

---

## 업데이트 절차

### 사전 점검

- [ ] `gh auth status` — 인증 안 되어 있으면 `gh auth login` 먼저
- [ ] working tree clean (`git status` — 다른 변경 없음 확인)
- [ ] 현재 NewPipe 버전 확인: `grep newpipe gradle/libs.versions.toml` → 현재 `v0.26.2`

### 최신 릴리즈 조회

```bash
gh api repos/TeamNewPipe/NewPipeExtractor/releases/latest --jq '.tag_name'
gh api repos/TeamNewPipe/NewPipeExtractor/releases/latest --jq '.body'
```

### 의사 결정

| 상태 | 행동 |
|---|---|
| 최신 = 현재 | NewPipe도 아직 fix 안 나옴. 다른 원인 의심 (앱 코드 자체 버그? PoToken WebView 문제?). |
| 최신 > 현재 + 노트가 스트림 관련 (n-param, signature, InnerTube, PoToken, DASH) | 업데이트 진행 |
| 최신 > 현재 + 노트가 RSS·UI·다른 서비스만 영향 | 굳이 업데이트 안 해도 됨 |

### 업데이트 적용

1. 새 브랜치: `git checkout -b fix/newpipe-{버전}-{date}` 예: `fix/newpipe-v0.26.3-2026-06`
2. `gradle/libs.versions.toml`의 `newpipe = "v0.26.2"` 한 줄을 새 버전으로 수정
3. 빌드: `./gradlew assembleDebug`
   - **컴파일 깨짐** = NewPipe API breaking change. 이 가이드 마지막의 "API breaking change 대응" 섹션 확인.
   - **빌드 성공** = 다음 단계
4. 설치: `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`
5. 검증 (사용자 직접):
   - 한 영상 재생 시작 — 첫 chunk OK?
   - seek 동작 — 중간 위치로 점프했을 때 chunk 403 안 나는지
   - 화질 전환 — 144p ↔ 1080p 정상?
   - 라이브 방송 (가능하면) — 정상 재생?

### 마무리

- OK면 commit + push + PR (`/ship` 사용 가능)
- 이 문서의 "깨짐 사례 기록" 섹션에 신규 항목 한 줄 추가
- 실패면 `git checkout main && git branch -D fix/newpipe-...` 으로 폐기, 원인 추가 조사

---

## 위험 신호 — 업데이트 전에 한 번 더 보자

다음 항목이 릴리즈 노트에 나오면 **꼼꼼히 확인**하고 업데이트:

- `PoTokenProvider` interface 변경 — `SubFeedPoTokenProvider`/`PoTokenWebView` 수정 필요할 수 있음
- `Downloader` interface 변경 — `OkHttpDownloader`/`SubFeedDownloader` 수정 필요할 수 있음
- `StreamInfo`/`StreamExtractor` API 변경 — `NewPipeVideoExtractor`에서 호출하는 메서드 시그니처 확인
- protobuf 버전 변경 — `app/build.gradle.kts`의 `resolutionStrategy { force("com.google.protobuf:protobuf-javalite:...") }` 와 충돌 가능. Firebase BOM과 매치되는 버전 유지 필수.
- Rhino 버전 점프 — ProGuard 룰 재점검 필요

major 버전 점프 (`v0.26.x → v0.27.0`) 시 위 항목들이 동시에 바뀌었을 가능성이 높음.

---

## API Breaking Change 대응

빌드가 깨지면:

1. 컴파일 에러 위치 확인 — 보통 `NewPipeVideoExtractor.kt`, `SubFeedPoTokenProvider.kt`, `SubFeedDownloader.kt` 중 하나
2. NewPipe Extractor 릴리즈 노트와 [Migration Guide](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/MIGRATION.md) (있다면) 확인
3. 본가 NewPipe 앱 ([github.com/TeamNewPipe/NewPipe](https://github.com/TeamNewPipe/NewPipe))이 동일 버전에서 같은 인터페이스를 어떻게 쓰는지 보고 참고
4. 30분 이상 막히면 일단 롤백: `libs.versions.toml`을 이전 버전으로 되돌리고 다음 release를 기다림

---

## 자주 참고할 외부 링크

- NewPipe Extractor releases: [github.com/TeamNewPipe/NewPipeExtractor/releases](https://github.com/TeamNewPipe/NewPipeExtractor/releases)
- NewPipe 앱 (참조 구현): [github.com/TeamNewPipe/NewPipe](https://github.com/TeamNewPipe/NewPipe)
- yt-dlp 이슈 (YouTube 변화 빠른 추적 채널): [github.com/yt-dlp/yt-dlp/issues](https://github.com/yt-dlp/yt-dlp/issues)
