# NAS yt-dlp 추출 프록시 (개발자 옵션)

작성일: 2026-06-13
관련: `20260613_web-potoken-removal.md`, `20260529_youtube-potoken-quality-wall.md`

## 배경 / 결정

codex 자문 결과, SubFeed 의 인앱 추출을 견고하게 만드는 유일하게 가치 있는 투자는 **NAS 에서
yt-dlp 를 돌려 추출하는 개발자 전용 옵션**이었다 (이 PC/사용자가 이미 Synology NAS 운영 중).

핵심 통찰: YouTube PO 토큰은 **video/client/session 바인딩**이라, NAS 에서 `mweb` 클라이언트로
추출한 googlevideo URL 을 폰이 직접 받으면(특히 LAN 밖) 깨진다. 따라서 NAS 가 **추출 + 바이트
range-proxy** 를 모두 한다. 가정 업로드 대역폭이 곧 동시 시청 한계.

이 경로의 가치는 **화질이 아니라 내구성**이다. v1 은 progressive muxed(360~720p 캡)만 다룬다.
NewPipe(visionOS)가 깨졌을 때 yt-dlp 가 더 빨리 업데이트되므로 NAS 쪽만 올리면 앱 빌드 없이 복구.

확정 사항(사용자 결정): 코드는 Minseo41 repo 하위 `server/`, v1 화질 캡 수용, 외부 접근은 Tailscale/WireGuard.

## NAS 측 (`server/`)

Docker Compose 2 컨테이너:
- `subfeed-extractor` — FastAPI. `/health`, `/extract?v=`(yt-dlp 추출), `/media/{token}`(range-proxy).
- `bgutil-provider` — `brainicism/bgutil-ytdlp-pot-provider:latest`, mweb GVS PO 토큰 사이드카(4416).

추출은 `mweb` 클라이언트 + bgutil 플러그인. progressive 단일 format 만 허용(`info["url"]`),
없으면 502 → 앱이 NewPipe 폴백. `/media/{token}` 은 opaque TTL(2h) 토큰으로 upstream URL 을
숨기고, `Range` 를 그대로 포워딩해 206/Content-Range/Accept-Ranges/Content-Length 중계.
배포/유지보수는 `server/README.md` 참고.

## 앱 측

- `data/nas/NasStreamFetcher.kt` — `/extract` 호출(`X-SubFeed-Secret` 헤더, connect 3s/call 20s),
  JSON → `StreamInfo`. 반환 `streamUrl` 은 NAS 미디어 URL(앱 입장 direct muxed). `/health` 연결 테스트.
- `data/DispatchingVideoExtractor.kt` — 새 `VideoExtractor` 진입점. 피드는 항상 NewPipe(RSS).
  `getStreamInfo` 만 분기: NAS 토글 ON + base URL 있으면 NAS 시도 → 실패 시 NewPipe 폴백
  (`CancellationException` 은 rethrow). `di/AppModule` 이 이걸 바인딩.
- 설정 UI — "인앱 플레이어" 선택 시에만 노출되는 개발자 섹션(`SettingsScreen.NasExtractorSection`):
  토글 / base URL / shared secret / 연결 테스트. `PlayerPrefs` 에 `nasExtractorEnabled`,
  `nasBaseUrl`, `nasSecret` 키 추가.

## codex cowork 리뷰 반영

codex review 에서 잡은 실제 버그 수정:
1. `/media` — `client.send()` 예외 시 `AsyncClient` 누수 → try/except 로 aclose.
2. 누락된 `follow_redirects=True` (googlevideo redirect 대응).
3. `aiter_raw()` + `Accept-Encoding: identity` 로 byte-for-byte 중계.
4. `info["formats"]` 휴리스틱 스캔 제거 — progressive `info["url"]` 만 신뢰.
5. `NasStreamFetcher` videoId URL 인코딩(`HttpUrl.Builder`).
6. `DispatchingVideoExtractor` `runCatching` 의 `CancellationException` 삼킴 → rethrow.
7. reverse-proxy 대비 `PUBLIC_BASE_URL` env override 추가(Tailscale 직접 접속이면 불필요).

## 빌드 / 설치

`./gradlew assembleDebug` 성공, 플립(R3CX705W62D) 설치. NAS 컨테이너는 사용자가 직접 배포(아래).

## 로컬 검증 (2026-06-14, Docker 없이 venv)

이 PC 엔 Docker 가 없어 venv 로 server 를 직접 띄워 검증:
- requirements 핀 전부 클린 설치 (yt-dlp 2026.6.9, bgutil 1.3.1, fastapi 0.115.6 …)
- FastAPI 부팅 / `/health` OK
- 실제 추출 성공 → `/media` range-proxy 가 실 googlevideo 에 대해 **206 + 정확한
  Content-Range/Content-Length/Accept-Ranges** 반환 (head `bytes=0-2047`, mid-file `bytes=5000000-…` 둘 다),
  인증 401(secret 없음/틀림)·404(잘못된 토큰) 정상
- **미검증**: `mweb`+bgutil (Node/Docker provider 필요 → NAS 에서 확인)

이 검증 과정에서 운영 유연성 env 추가(아래):
- `YT_PLAYER_CLIENT`(기본 mweb), `USE_BGUTIL`(기본 true), `YT_FORMAT`(기본 progressive muxed).
  YouTube 변동 시 NAS env 만 바꿔 대응 (앱 재빌드 불필요). 상세: `server/README.md`.

발견: `android_vr` + `USE_BGUTIL=false` 는 PO 토큰·사이드카 없이 progressive itag18(360p) 반환.
bgutil 배포가 막힐 때 단순 폴백으로 쓸 수 있으나, NewPipe visionOS 와 같은 클라이언트 계열이라 내구성 이점은 적음.

## 확인 리스트

### NAS (사용자 직접)
1. `server/` 를 NAS 로 복사 → `.env` 에 `SUBFEED_SECRET` 생성 → `docker compose up -d --build`
2. `curl http://localhost:8787/health` → `{"status":"ok"}`
3. `curl -H "X-SubFeed-Secret: <secret>" "http://localhost:8787/extract?v=<id>"` → streamUrl JSON
4. `curl -H "Range: bytes=0-1023" "<streamUrl>" -D -` → 206 + Content-Range
5. 폰·NAS 를 같은 Tailscale tailnet 에

### 앱
6. 설정 → 재생 방식 "인앱 플레이어" → "NAS yt-dlp 추출(개발자)" 토글 ON
7. base URL(`http://<nas-tailscale-ip>:8787`) + secret 입력 → 연결 테스트 "연결 성공"
8. 영상 재생 → NAS 경로로 재생되는지 (logcat `SubFeedNasStream` / `SubFeedDispatchExtractor`)
9. NAS 끄고 재생 → NewPipe 로 자동 폴백되는지
10. LAN 밖(셀룰러)에서 Tailscale 통해 재생되는지 (대역폭 한계 체감)

## 실기기 동작 확인 (2026-06-14, v1.4.0.0)

시놀로지 NAS(Container Manager, `docker-compose` v1 하이픈)에 배포 → 플립(R3CX705W62D) end-to-end 재생 확인.

배포/검증 중 확정된 사실 두 가지:
- **mweb 은 v1 에서 못 쓴다** — mweb+bgutil 추출은 되지만(PO토큰 OK) **progressive muxed 가 없고 adaptive 만** 반환 → `/extract` 가 "Requested format is not available" 502. 그래서 **v1 기본을 `android_vr`(progressive itag18 360p, PO토큰 불요) 로 변경.** mweb 견고 경로는 DASH 조립(v2) 후로 미룸. compose 의 `bgutil-provider` 도 기본 주석 처리.
- **cleartext HTTP 필수** — 앱이 NAS 를 `http://` 로 부르므로 `AndroidManifest` 에 `usesCleartextTraffic="true"` 없으면 "연결 실패". 추가함(다른 NAS 앱들과 동일 관례).

## 한계 / 다음

- v1 화질 360p 캡 (android_vr progressive). 고화질은 **v2: mweb+bgutil + DASH 분리 스트림 조립(manifest 재작성)** 필요.
- 동시 시청은 가정 업로드 대역폭에 묶임 (개인용 전제).
- 자막(`captionTracks`)은 v1 미지원(빈 배열).
- android_vr 은 NewPipe visionOS 와 같은 계열이라 내구성 이점은 제한적 — 같이 깨지면 v2(mweb)로 전환.
