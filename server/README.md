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
