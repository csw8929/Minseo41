#!/usr/bin/env python3
"""YouTube RSS feed fetcher with success-rate hardening.

Usage:
  python3 fetch-youtube-rss.py UCxxxxxxxxxxxxxxxxxxxxxx
  python3 fetch-youtube-rss.py UCxxxxxxxxxxxxxxxxxxxxxx -o feed.xml
  python3 fetch-youtube-rss.py UCxxxxxxxxxxxxxxxxxxxxxx --retries 3 --backoff 2
  python3 fetch-youtube-rss.py --json channels.json --out-dir feeds/

표준 라이브러리만 사용 (requests 의존성 없음).

Applied hardening (docs/investigate report 2026-05-12):
- A1: channel ID validation + auto `UC` prefix 보정
- A2: CONSENT=YES+cb 쿠키 (EU consent redirect 우회)
- A3: Accept-Language 헤더
- A4: 응답 body 검증 (`<?xml` 시작 여부)
- A5: 지수 backoff + jitter
- B1: User-Agent rotation (실제 Chrome/Firefox/Safari pool)
- B3: Sec-Fetch-* fingerprint 헤더
"""
import argparse
import json
import random
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

# B1: 실제 사용 빈도 높은 UA pool. 매 요청마다 rotation.
UA_POOL = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15",
]

# A2/A3/B3: 모든 요청에 붙는 공통 헤더 (UA 만 rotation).
COMMON_HEADERS = {
    "Accept": "application/atom+xml, application/xml, text/xml, */*;q=0.1",
    "Accept-Language": "en-US,en;q=0.9,ko;q=0.8",
    "Accept-Encoding": "gzip, deflate, br",
    "Cookie": "CONSENT=YES+cb",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Sec-Fetch-User": "?1",
    "Upgrade-Insecure-Requests": "1",
    "DNT": "1",
}

CHANNEL_ID_PATTERN = re.compile(r"^UC[A-Za-z0-9_-]{22}$")


def normalize_channel_id(raw: str) -> Optional[str]:
    """A1: 채널 ID 형식 검증 + 자동 보정.

    - "UCxxx...22자" → 그대로 사용 (24자)
    - "Cxxx...22자"  → `U` 붙여 보정 (사용자 오타 케이스)
    - 그 외          → None (호출자가 에러 처리)
    """
    s = raw.strip()
    if CHANNEL_ID_PATTERN.match(s):
        return s
    if len(s) == 23 and s.startswith("C") and not s.startswith("UC"):
        candidate = "U" + s
        if CHANNEL_ID_PATTERN.match(candidate):
            print(f"[hint] channel_id 가 'C' 로 시작합니다. '{s}' → '{candidate}' 로 보정해서 시도합니다.",
                  file=sys.stderr)
            return candidate
    return None


def fetch_youtube_rss(channel_id: str, retries: int = 5, backoff: int = 2,
                      timeout: int = 10) -> Optional[str]:
    url = f"https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"
    for attempt in range(retries):
        ua = random.choice(UA_POOL)
        headers = {"User-Agent": ua, **COMMON_HEADERS}
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as res:
                body = res.read()
                # gzip/br 등 압축은 urllib 가 자동 decode 안 함 → Accept-Encoding 헤더 보내도
                # 서버가 identity 로 응답하는 경우가 많음. 안 되면 decompress 시도.
                text = _decode_body(res, body)
                # A4: 응답 body 가 진짜 XML 인지 검증. 200 이라도 HTML consent 페이지 가능성.
                if res.status == 200 and text.lstrip().startswith("<?xml"):
                    return text
                head_sample = text[:120].replace("\n", " ")
                print(f"[{channel_id}] [{attempt+1}/{retries}] HTTP {res.status} but body not XML: {head_sample!r}",
                      file=sys.stderr)
        except urllib.error.HTTPError as e:
            print(f"[{channel_id}] [{attempt+1}/{retries}] HTTP {e.code} {e.reason}", file=sys.stderr)
            # 404 는 채널 자체가 없는 영구 에러 → 재시도 무의미.
            if e.code == 404:
                return None
        except Exception as e:
            print(f"[{channel_id}] [{attempt+1}/{retries}] 오류: {e}", file=sys.stderr)
        if attempt < retries - 1:
            # A5: 지수 backoff + 0~1초 jitter (thundering herd 방지).
            delay = backoff ** attempt + random.uniform(0, 1)
            time.sleep(delay)
    return None


def _decode_body(res, body: bytes) -> str:
    enc = res.headers.get("Content-Encoding", "").lower()
    try:
        if enc == "gzip":
            import gzip
            body = gzip.decompress(body)
        elif enc == "deflate":
            import zlib
            body = zlib.decompress(body)
        elif enc == "br":
            try:
                import brotli  # type: ignore
                body = brotli.decompress(body)
            except ImportError:
                pass
    except Exception:
        pass
    charset = res.headers.get_content_charset() or "utf-8"
    return body.decode(charset, errors="replace")


def main() -> int:
    ap = argparse.ArgumentParser(description="YouTube RSS fetcher with retries")
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("channel_id", nargs="?", help="YouTube channel ID (UC...)")
    src.add_argument("--json", dest="json_path",
                     help="SubFeed channels.json — fetch every channel listed")
    ap.add_argument("-o", "--output", default=None,
                    help="Single channel mode: output file (default: stdout)")
    ap.add_argument("--out-dir", default=None,
                    help="JSON mode: directory to write {channelId}.xml files")
    ap.add_argument("--retries", type=int, default=5)
    ap.add_argument("--backoff", type=int, default=2)
    ap.add_argument("--timeout", type=int, default=10)
    args = ap.parse_args()

    if args.json_path:
        return run_batch(args)
    return run_single(args)


def run_single(args) -> int:
    cid = normalize_channel_id(args.channel_id)
    if cid is None:
        print(f"잘못된 채널 ID 형식: {args.channel_id!r} "
              f"(UC + 22자 영숫자/_-/ 형식이어야 합니다)", file=sys.stderr)
        return 2
    xml = fetch_youtube_rss(cid, retries=args.retries,
                            backoff=args.backoff, timeout=args.timeout)
    if xml is None:
        print(f"실패: {cid}", file=sys.stderr)
        return 1
    if args.output:
        Path(args.output).write_text(xml, encoding="utf-8")
        print(f"저장: {args.output}", file=sys.stderr)
    else:
        sys.stdout.write(xml)
    return 0


def run_batch(args) -> int:
    data = json.loads(Path(args.json_path).read_text(encoding="utf-8"))
    channels = data.get("channels") if isinstance(data, dict) else data
    if not isinstance(channels, list):
        print("JSON 구조 오류: channels 배열이 필요합니다", file=sys.stderr)
        return 2

    out_dir = Path(args.out_dir) if args.out_dir else None
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)

    ok = 0
    failed = []
    skipped = []
    for idx, ch in enumerate(channels):
        raw_id = ch.get("id") if isinstance(ch, dict) else None
        if not raw_id:
            continue
        cid = normalize_channel_id(raw_id)
        if cid is None:
            skipped.append(raw_id)
            continue
        xml = fetch_youtube_rss(cid, retries=args.retries,
                                backoff=args.backoff, timeout=args.timeout)
        if xml is None:
            failed.append(cid)
            continue
        ok += 1
        if out_dir:
            (out_dir / f"{cid}.xml").write_text(xml, encoding="utf-8")
        else:
            sys.stdout.write(f"<!-- {cid} -->\n")
            sys.stdout.write(xml)
            sys.stdout.write("\n")
        # E2: 채널 간 200~500ms 랜덤 sleep — rate-limit 회피.
        if idx < len(channels) - 1:
            time.sleep(random.uniform(0.2, 0.5))

    print(f"성공 {ok} / 실패 {len(failed)} / 형식오류 {len(skipped)}", file=sys.stderr)
    if failed:
        print("실패 채널: " + ", ".join(failed), file=sys.stderr)
    if skipped:
        print("형식오류 채널: " + ", ".join(skipped), file=sys.stderr)
    return 0 if not failed and not skipped else 1


if __name__ == "__main__":
    raise SystemExit(main())
