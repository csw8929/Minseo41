# YouTube RSS Outage 분석 + 개선 방향

작성일: 2026-05-08
세션: /investigate
브랜치: feat/settings-stabilize-2026-05

## 1. 증상

- SubFeed 새로고침 시 모든 21개 채널이 **InnerTube fallback** 경로로 진입.
- logcat에 매번 `RSS blocked, falling back to InnerTube browse — channelId=...` 로그 21회.
- 단순 응답 지연 외에 사용자 가시적 오류는 없음 (영상 리스트는 정상). 다만 노이즈 큼.

## 2. Root Cause — YouTube RSS endpoint platform-wide 404

코드/헤더/UA 문제가 아니라 **YouTube 서비스 측 deprecation/outage**.

### 단말 PC에서 직접 curl 검증 (2026-05-08 13:34 KST)

| 테스트 | 결과 |
|---|---|
| `curl/8.0` UA | HTTP/2 **404** |
| `okhttp/4.12.0` UA | HTTP/2 **404** |
| 데스크탑 Chrome UA | HTTP/2 **404** |
| 모바일 Chrome UA + `Cookie: CONSENT=YES+cb` + `Accept: atom+xml` | HTTP/2 **404** |
| 응답 body | `<!DOCTYPE html><html lang=en>...<title>Error 404 (Not Found)!!1</title>` (Google 표준 404 페이지) |
| 응답 헤더 | `server: YouTube RSS Feeds server` — 서버는 살아있지만 의도적 404 |
| 채널 다양화 (Google Developers / 다른 한국 채널) | 모두 HTTP/2 **404** |
| `feeds/videos.xml?playlist_id=UU...` 형식 | HTTP/2 **404** |
| `feeds/videos.xml?user=...` 형식 | HTTP/2 **404** |
| base `feeds/videos.xml` (no params) | HTTP/2 **400** |

→ **endpoint 자체가 채널/형식/파라미터 무관하게 죽었음**. 헤더 우회 불가.

### 외부 검증 — 같은 이슈 보고

다른 RSS reader / 라이브러리 / 서비스 모두 동일 증상 보고:

- [Google AI Developers Forum: YouTube RSS feed endpoint returns 404 errors](https://discuss.ai.google.dev/t/youtube-rss-feed-endpoint-returns-404-errors/113379)
- [ocaml/ocaml.org#3512 — YouTube RSS feeds returning 404 (platform-wide outage)](https://github.com/ocaml/ocaml.org/issues/3512)
- [RSS-Bridge#2113 — videos.xml endpoint occasionally returns 404](https://github.com/RSS-Bridge/rss-bridge/issues/2113)
- [miniflux/v2#4261 — YouTube getting 404 and never updating](https://github.com/miniflux/v2/issues/4261)
- [NewsBlur Forum — Cannot add youtube channel](https://forum.newsblur.com/t/cannot-add-youtube-channel/10419)

→ **2026-02 이후 RSS endpoint platform-wide outage 진행 중**. 우리 케이스는 일관됨.

### 어제 fbf0156 commit과의 관계

- 어제(2026-05-07) `fix: RSS endpoint 404 — InnerTube browse API 로 채널 피드 전환` commit으로 **이미 정확히 진단**했음.
- 16:05 시점에 잠깐 RSS가 회복돼 보여서 reset --hard로 InnerTube 전환을 폐기 → "transient outage"로 misdiagnosis.
- 지금은 다시 platform-wide 404 → 어제 진단이 맞았고, 그 사이 transient 회복은 일시적 blip이었던 것.

## 3. 현재 동작 (영향)

`NewPipeVideoExtractor.getChannelFeed`:

```
1차: RSS GET (no headers)              ← 항상 404 HTML
2차: InnerTube POST browse API          ← 정상 동작, 영상 리스트 반환
```

매 새로고침 21채널 = **21회의 RSS 404 round-trip + 21회의 InnerTube round-trip**. RSS 호출 자체가 0.3-1초씩 wait → 노이즈 6-20초.

## 4. 개선 방향 (3안)

### Approach A — InnerTube를 1차로 (RSS 시도 제거)

- `getChannelFeed` 를 InnerTube 단일 path로 단순화.
- RSS 호출 자체 제거 → 매 새로고침 6-20초 절감.
- RSS 회복 시 feature flag 또는 코드 한 줄로 다시 1차 시도 가능하도록 구조 유지.
- **Effort: S** (NewPipeVideoExtractor 한 함수, ~10줄 변경).

### Approach B — fetch 결과 캐시 (5-10분)

- `SubscriptionRepo.fetchTodayVideos` 결과를 메모리에 짧은 시간 캐시.
- 사용자 새로고침 spam 시 캐시 유효 동안 외부 호출 0회 → YouTube 측 rate limit 회피.
- 실 사용 시나리오에서는 5분에 한 번 새로고침이 자연스러움.
- **Effort: S-M** (SubscriptionRepo + invalidation 정책).

### Approach C — InnerTube client rotation

- WEB/IOS/ANDROID/TVHTML5 client를 라운드 로빈으로 교체.
- 단일 client throttle 회피. (6번 연속 재진입 stress test에서 본 chunk 403 같은 케이스 분산 가능성.)
- 다만 throttle 패턴이 명확히 알려져 있지 않아 효과 측정 어려움.
- **Effort: M** (extractor + state).

## 5. 추천 조합

**A + B**:
- A: 매 새로고침 RSS 404 wait + 21번의 무의미한 round-trip 사라짐.
- B: 사용자 새로고침 spam 시 자연스럽게 외부 호출 횟수 절감 + rate limit 위험 감소.

C는 실 throttle 패턴 불명확 + 작업량 대비 효과 불확실 → 우선순위 낮음.

## 6. 결정 보류 — 향후 확인 사항

- [ ] InnerTube API 자체의 안정성 — yt-dlp / NewPipe 모두 의존하는 영역이라 일반적으로 stable. 다만 YouTube의 internal API라 SLA 없음.
- [ ] InnerTube 응답에서 channelName 빈 값 케이스 — 현재 우리 코드는 그대로 빈 값 사용. UI에서 빈 채널명 보일 가능성 있음. SubscriptionRepo 단에서 DB의 `channel.name` 으로 fallback 처리하면 깔끔.
- [ ] InnerTube 응답의 publishedTimeText 영문 ("3 hours ago") 파싱 — 이미 `parseRelativeEnglishTime` 으로 처리. shorts/live 영상은 publishedTimeText 부재로 자연스럽게 cutoff 필터에서 제외됨.
- [ ] RSS endpoint 회복 모니터링 — 향후 YouTube가 endpoint 복구하면 1차 path로 되돌릴 수 있도록 코드 구조 유지.

## 7. 다음 액션

본 문서를 검토 후 A/B/C 또는 다른 조합 선택. 결정 시 별도 PR로 진행 (현재 `feat/settings-stabilize-2026-05` 브랜치는 본 라운드 변경분에 집중).
