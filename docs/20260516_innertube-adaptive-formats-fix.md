# InnerTube adaptive formats 재생 불가 문제 수정

## 증상

피드에서 영상 탭 시 재생이 안 되고, 몇 번 시도하면 되는 경우가 있었음.
특정 영상(예: `pjXARMgACCs`, `JlOF9_Z9UgQ`)은 항상 실패.

## 근본 원인 (3개)

### 1. iOS 클라이언트가 `adaptiveFormats`만 반환

`streamingData-diag` 진단 로그:
```
videoId=pjXARMgACCs client=IOS
hasHls=false hasDash=false formats=0 hasCipher=false
adaptive=32 keys=[expiresInSeconds, adaptiveFormats, aspectRatio, serverAbrStreamingUrl]
```

YouTube iOS InnerTube API가 일부 영상에 `hlsManifestUrl`, `dashManifestUrl`, `formats` 없이 `adaptiveFormats`(개별 비디오/오디오 스트림 32개)만 반환함. 기존 코드는 이를 완전히 무시해서 항상 "스트림 URL 없음" 에러로 실패.

`adaptiveFormats`의 URL은 비암호화(`hasCipher=false`) — 별도 signature 해독 불필요.

### 2. ANDROID 클라이언트 400 에러

```
400 Precondition check failed
```

`contentCheckOk: true`, `racyCheckOk: true` 필드 없이 InnerTube ANDROID 클라이언트를 호출하면 400 에러 발생.

### 3. TVHTML5_SIMPLY_EMBEDDED_PLAYER 영구 차단

```
playabilityStatus=ERROR reason=이 애플리케이션 또는 기기에서 YouTube가 더 이상 지원되지 않습니다.
```

2026-05 기준 완전히 막힘.

## 수정 내용

### `NewPipeVideoExtractor.kt`

1. **TVHTML5 → ANDROID 클라이언트 교체**
   - `clientName: ANDROID`, version `19.44.38`
   - body에 `contentCheckOk: true, racyCheckOk: true` 추가

2. **`dashManifestUrl` 파싱 추가** (HLS 다음 2순위)
   - ANDROID 클라이언트가 DASH manifest 반환

3. **`adaptiveFormats` → 인라인 DASH MPD 생성** (4순위)
   - iOS가 adaptive-only 응답 줄 때 사용
   - 최고화질 video(≤1080p) + 최고 bitrate audio 선택
   - DASH MPD XML 빌드 → base64 → `dash:data:application/dash+xml;base64,...`
   - **XML escape 필수**: YouTube URL의 `&` → `&amp;` (이스케이프 누락 시 `XmlPullParserException: unterminated entity ref`)

4. **자동 재시도 1회** (1.5초 후)
   - YouTube 봇 검사(LOGIN_REQUIRED)는 간헐적 → 재시도로 해결

### 인라인 DASH 생성 시 주의점

YouTube CDN URL에는 `&expire=...&sig=...` 등 쿼리 파라미터가 포함되어 XML에 넣기 전 반드시 이스케이프 필요:
- `&` → `&amp;`
- `<` → `&lt;`
- `>` → `&gt;`
- `"` → `&quot;`

## 검증 결과

```
videoId=pjXARMgACCs client=IOS type=ADAPTIVE-DASH adaptive=32 ✅
videoId=JlOF9_Z9UgQ client=IOS type=ADAPTIVE-DASH adaptive=38 ✅
videoId=4eUqijMx6g8 client=IOS type=HLS ✅
```

`savePositionNow` 로그 발생 → 실제 재생 확인됨.

## PlayerScreen.kt 변경 없음

`dash:` 접두사 분기가 이미 있었고, ExoPlayer DashMediaSource가 `data:` URI를 기본 지원하므로 플레이어 측 코드 변경 불필요.
