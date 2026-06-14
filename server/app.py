"""SubFeed NAS 추출 프록시.

흐름:
  앱 → GET /extract?v=<videoId> (X-SubFeed-Secret 헤더)
        → yt-dlp 가 mweb 클라이언트 + bgutil PO 토큰으로 추출
        → progressive MP4 의 googlevideo URL 을 골라 opaque 토큰에 매핑
        → { streamUrl: <this-host>/media/<token>, ... } 반환
  앱(ExoPlayer) → GET /media/<token> (Range 헤더)
        → NAS 가 googlevideo 바이트를 range-proxy

PO 토큰은 video/client/session 에 바인딩되므로 추출 URL 을 폰이 직접 받으면 (특히 LAN 밖)
깨진다. 그래서 NAS 가 바이트를 중계한다. 가정 업로드 대역폭이 곧 동시 시청 한계.
"""
import hmac
import os
import secrets
import time
from collections import OrderedDict, deque

import httpx
import yt_dlp
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse

SHARED_SECRET = os.environ.get("SUBFEED_SECRET", "")
BGUTIL_BASE_URL = os.environ.get("BGUTIL_BASE_URL", "http://bgutil-provider:4416")
# 추출 클라이언트. 기본 mweb(+bgutil PO토큰). YouTube 정책 변동 시 env 로 교체.
YT_PLAYER_CLIENT = os.environ.get("YT_PLAYER_CLIENT", "mweb")
# bgutil PO토큰 provider 사용 여부. mweb/web 계열은 필요, tv/android_vr 등은 불요.
USE_BGUTIL = os.environ.get("USE_BGUTIL", "true").strip().lower() not in ("0", "false", "no")
# 포맷 선택자. 기본 progressive(muxed) — DASH/HLS 재작성 회피. YouTube 변동 시 env 로 조정.
YT_FORMAT = os.environ.get(
    "YT_FORMAT",
    "best[ext=mp4][acodec!=none][vcodec!=none]/best[acodec!=none][vcodec!=none]",
)
# reverse proxy 뒤(DDNS/HTTPS)면 request.base_url 이 내부 주소가 될 수 있으니 명시 override.
# Tailscale 직접 접속이면 비워둬도 request.base_url 이 정확하다.
PUBLIC_BASE_URL = os.environ.get("PUBLIC_BASE_URL", "")
TOKEN_TTL_SECONDS = int(os.environ.get("TOKEN_TTL_SECONDS", "7200"))
CACHE_MAX = int(os.environ.get("CACHE_MAX", "200"))
RATE_LIMIT_PER_HOUR = int(os.environ.get("RATE_LIMIT_PER_HOUR", "30"))

# 추출에 쓴 것과 동일한 클라이언트(UA)로 chunk 를 받아야 가장 덜 깨진다.
MWEB_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
)

app = FastAPI()

# token -> {"url": str, "exp": float}. 작은 LRU + TTL. 재시작 시 비워짐(허용).
_media_cache: "OrderedDict[str, dict]" = OrderedDict()
# ip -> deque[timestamp]. extract 호출 rate limit.
_rate: "dict[str, deque]" = {}


def _check_secret(provided: str | None) -> None:
    if not SHARED_SECRET:
        return
    if not provided or not hmac.compare_digest(provided, SHARED_SECRET):
        raise HTTPException(status_code=401, detail="bad secret")


def _check_rate(ip: str) -> None:
    now = time.time()
    bucket = _rate.setdefault(ip, deque())
    while bucket and now - bucket[0] > 3600:
        bucket.popleft()
    if len(bucket) >= RATE_LIMIT_PER_HOUR:
        raise HTTPException(status_code=429, detail="rate limited")
    bucket.append(now)


def _store_url(url: str) -> str:
    token = secrets.token_urlsafe(16)
    _media_cache[token] = {"url": url, "exp": time.time() + TOKEN_TTL_SECONDS}
    _media_cache.move_to_end(token)
    while len(_media_cache) > CACHE_MAX:
        _media_cache.popitem(last=False)
    return token


def _resolve_token(token: str) -> str:
    entry = _media_cache.get(token)
    if not entry:
        raise HTTPException(status_code=404, detail="unknown token")
    if time.time() > entry["exp"]:
        _media_cache.pop(token, None)
        raise HTTPException(status_code=410, detail="token expired")
    return entry["url"]


def _extract(video_id: str) -> dict:
    url = f"https://www.youtube.com/watch?v={video_id}"
    extractor_args = {"youtube": {"player_client": [YT_PLAYER_CLIENT]}}
    if USE_BGUTIL:
        extractor_args["youtubepot-bgutilhttp"] = {"base_url": [BGUTIL_BASE_URL]}
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        # progressive(muxed) 우선 — DASH/HLS 재작성 회피. v1 은 화질 360~720p 캡.
        # MP4 우선, 없으면 다른 muxed(webm 등). muxed 가 아예 없으면 추출 실패 → 앱이 NewPipe 폴백.
        "format": YT_FORMAT,
        "extractor_args": extractor_args,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)

    # progressive 단일 format 선택 시 top-level url 이 채워진다. 없으면(=merged/분리) v1 미지원 → 실패.
    media_url = info.get("url")
    if not media_url:
        raise HTTPException(status_code=502, detail="no progressive muxed stream")

    chapters = [
        {"timeMs": int((c.get("start_time") or 0) * 1000), "title": c.get("title", "")}
        for c in (info.get("chapters") or [])
    ]
    return {
        "media_url": media_url,
        "durationSeconds": int(info.get("duration") or 0),
        "chapters": chapters,
    }


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/extract")
def extract(
    request: Request,
    v: str,
    x_subfeed_secret: str | None = Header(default=None),
):
    _check_secret(x_subfeed_secret)
    client_ip = request.client.host if request.client else "unknown"
    _check_rate(client_ip)

    try:
        result = _extract(v)
    except HTTPException:
        raise
    except Exception as exc:  # yt-dlp 추출 실패 — 앱은 NewPipe 로 폴백한다.
        raise HTTPException(status_code=502, detail=f"extract failed: {exc}") from exc

    token = _store_url(result["media_url"])
    base = (PUBLIC_BASE_URL or str(request.base_url)).rstrip("/")
    return JSONResponse(
        {
            "type": "streamInfo",
            "streamUrl": f"{base}/media/{token}",
            "durationSeconds": result["durationSeconds"],
            "captionTracks": [],
            "chapters": result["chapters"],
            "expiresAtEpochSeconds": int(time.time()) + TOKEN_TTL_SECONDS,
            "source": "nas-ytdlp",
        }
    )


@app.get("/media/{token}")
async def media(token: str, request: Request):
    upstream = _resolve_token(token)
    # 추출에 쓴 mweb UA + identity 로 byte-for-byte 중계. range 헤더 그대로 포워딩.
    fwd_headers = {"User-Agent": MWEB_UA, "Accept-Encoding": "identity"}
    range_header = request.headers.get("range")
    if range_header:
        fwd_headers["Range"] = range_header

    client = httpx.AsyncClient(timeout=httpx.Timeout(30.0, read=None), follow_redirects=True)
    try:
        upstream_req = client.build_request("GET", upstream, headers=fwd_headers)
        upstream_resp = await client.send(upstream_req, stream=True)
    except Exception:
        await client.aclose()
        raise

    passthrough = {}
    for h in ("content-type", "content-length", "content-range", "accept-ranges"):
        if h in upstream_resp.headers:
            passthrough[h] = upstream_resp.headers[h]

    async def body_iter():
        try:
            async for chunk in upstream_resp.aiter_raw():
                yield chunk
        finally:
            await upstream_resp.aclose()
            await client.aclose()

    return StreamingResponse(
        body_iter(),
        status_code=upstream_resp.status_code,
        headers=passthrough,
    )
