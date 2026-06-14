# SubFeed NAS 추출 프록시 (server/)

SubFeed 앱의 **개발자 옵션** 인앱 추출 엔진. Synology NAS 에서 Docker 로 yt-dlp 를 돌려
YouTube 스트림을 추출하고, 미디어 바이트를 range-proxy 해서 앱(ExoPlayer)에 전달한다.

NewPipe(visionOS 클라이언트)가 깨졌을 때의 **내구성 보험**이다. yt-dlp 가 NewPipe 보다
빨리 업데이트되므로, NAS 쪽 `yt-dlp` / `bgutil` 만 올리면 앱 빌드 없이 복구된다.

> 설계 배경: `docs/20260613_nas-ytdlp-extractor.md`

## 왜 바이트를 중계하나

YouTube PO 토큰은 video/client/session 에 바인딩된다. NAS 에서 `mweb` 클라이언트로 추출한
googlevideo URL 을 폰이 직접 받으면 (특히 LAN 밖) 깨진다. 그래서 NAS 가 추출도 하고
바이트도 중계한다. **가정 업로드 대역폭이 곧 동시 시청 한계** (5Mbps 스트림 ≈ 업로드 5Mbps 상시 점유).

## 구성

| 컨테이너 | 역할 |
|---|---|
| `subfeed-extractor` | FastAPI. `/extract` (yt-dlp 추출) + `/media/{token}` (range-proxy) |
| `bgutil-provider` | `brainicism/bgutil-ytdlp-pot-provider` — mweb GVS 용 PO 토큰 발급 사이드카 |

## API

| 엔드포인트 | 인증 | 설명 |
|---|---|---|
| `GET /health` | 없음 | 연결 테스트용 `{"status":"ok"}` |
| `GET /extract?v=<videoId>` | `X-SubFeed-Secret` 헤더 | progressive MP4 추출 → `streamUrl`(=`/media/{token}`) JSON |
| `GET /media/{token}` | 없음(opaque TTL 토큰) | `Range` 헤더를 googlevideo 로 포워딩, 206 중계 |

`/extract` 응답은 앱의 `StreamInfo(streamUrl, captionTracks, durationSeconds, chapters)` 에 그대로 매핑된다.
v1 은 `captionTracks` 를 비워 둔다.

## 환경 변수

`.env` 또는 compose `environment` 로 지정. YouTube 정책 변동 시 앱 재빌드 없이 NAS 만 조정하는 게 이 기능의 핵심.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SUBFEED_SECRET` | (없음) | `/extract` 인증 헤더 값. 앱 설정과 동일하게. 빈 값이면 인증 생략(비권장) |
| `YT_PLAYER_CLIENT` | `mweb` | yt-dlp InnerTube 클라이언트. mweb 깨지면 `android_vr`/`tv` 등으로 교체 |
| `USE_BGUTIL` | `true` | PO 토큰 provider 사용. `mweb`/`web` 계열은 필요, `android_vr` 등은 불요(`false`) |
| `YT_FORMAT` | `best[ext=mp4]…/best[…]` | 포맷 선택자. progressive muxed 우선. muxed 없으면 추출 실패→앱 NewPipe 폴백 |
| `BGUTIL_BASE_URL` | `http://bgutil-provider:4416` | bgutil HTTP provider 주소 |
| `TOKEN_TTL_SECONDS` | `7200` | `/media` 토큰 유효시간 |
| `RATE_LIMIT_PER_HOUR` | `30` | IP 당 `/extract` 호출 제한 |
| `PUBLIC_BASE_URL` | (없음) | reverse proxy 뒤일 때 `streamUrl` 베이스 강제 지정. Tailscale 직접 접속이면 불필요 |

### 두 가지 운영 모드
- **mweb + bgutil (기본, 권장)** — YouTube 의 현재 PO 강제 모델을 따라가므로 더 견고. bgutil 사이드카 필요.
- **android_vr + `USE_BGUTIL=false` (단순)** — PO 토큰·사이드카 불필요, progressive itag18(360p) 반환. 단 NewPipe 의 visionOS 경로와 같은 클라이언트 계열이라 **내구성 이점은 적다**(같이 깨질 수 있음). bgutil 배포가 막힐 때의 폴백.

## 배포 (Synology + Container Manager)

1. 이 `server/` 디렉토리를 NAS 로 복사 (File Station 또는 git).
2. SSH 접속 후 디렉토리에서:
   ```bash
   cp .env.example .env
   echo "SUBFEED_SECRET=$(openssl rand -base64 24)" > .env   # 긴 랜덤 secret
   docker compose up -d --build
   ```
   (DSM Container Manager GUI 의 "프로젝트" 로 docker-compose.yml 을 올려도 된다.)
3. 동작 확인:
   ```bash
   curl http://localhost:8787/health
   curl -H "X-SubFeed-Secret: <secret>" "http://localhost:8787/extract?v=dQw4w9WgXcQ"
   curl -H "Range: bytes=0-1023" "http://localhost:8787/media/<token>" -o /dev/null -D -
   ```

## 외부 접근 = Tailscale/WireGuard

이 프록시는 인증이 shared-secret 헤더뿐이라 **평문 인터넷에 노출 금지**. 폰과 NAS 를
같은 Tailscale tailnet 에 넣고, 앱 설정의 base URL 에 NAS 의 Tailscale 주소를 쓴다.

```
앱 설정 → 인앱 플레이어 → NAS yt-dlp 추출 ON
  base URL: http://<nas-tailscale-ip>:8787
  shared secret: <.env 의 SUBFEED_SECRET>
  → 연결 테스트
```

집 WiFi(LAN) 에서는 `http://192.168.45.65:8787` 로도 된다. Tailscale 주소를 쓰면 안팎 모두 동작.

## 유지보수

깨지면 (YouTube 정책 변경):
```bash
docker compose pull bgutil-provider      # PO 토큰 provider 갱신
docker compose build --no-cache subfeed-extractor   # yt-dlp 최신으로 재빌드
docker compose up -d
```
앱은 NAS 실패 시 자동으로 NewPipe → YouTube 앱으로 폴백하므로, NAS 가 죽어도 앱은 계속 동작한다.

## 로컬 검증 상태 (2026-06-14)

Docker 없이 venv 로 검증 완료한 범위:
- requirements 핀 전부 클린 설치 (yt-dlp 2026.6.9, bgutil 1.3.1, fastapi 0.115.6 등)
- FastAPI 부팅 / `/health` OK
- `android_vr` + `USE_BGUTIL=false` 로 실제 추출 성공 → `/media` range-proxy 가 실 googlevideo 에 대해
  **206 + 정확한 Content-Range/Content-Length/Accept-Ranges** 반환 (head + mid-file seek 모두), 인증 401/404 정상
- **미검증**: `mweb`+bgutil 경로 (Node/Docker 의 bgutil provider 필요 → NAS 에서 확인)

> 로컬 PC 가 SSL 가로채기(엔드포인트 보안 MITM 루트)가 있으면 venv 의 Python 이 YouTube TLS 검증에
> 실패한다. 그 경우만 `pip install truststore` 후 `truststore.inject_into_ssl()` 로 OS 신뢰저장소를
> 쓰면 된다. NAS Docker(`python:3.12-slim` + ca-certificates)에선 불필요.
