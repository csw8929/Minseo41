#!/usr/bin/env python3
"""YouTube RSS feed fetcher with exponential backoff retry.

Usage:
  python fetch-youtube-rss.py UCxxxxxxxxxxxxxxxxxxxxxx
  python fetch-youtube-rss.py UCxxxxxxxxxxxxxxxxxxxxxx -o feed.xml
  python fetch-youtube-rss.py UCxxxxxxxxxxxxxxxxxxxxxx --retries 3 --backoff 2
  python fetch-youtube-rss.py --json channels.json --out-dir feeds/

표준 라이브러리만 사용 (requests 의존성 없음).
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_UA = "Mozilla/5.0 (Windows NT 11.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"


def fetch_youtube_rss(channel_id: str, retries: int = 5, backoff: int = 2,
                      timeout: int = 10, user_agent: str = DEFAULT_UA) -> str | None:
    url = f"https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"
    req = urllib.request.Request(url, headers={"User-Agent": user_agent})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as res:
                if res.status == 200:
                    return res.read().decode("utf-8")
                print(f"[{channel_id}] [{attempt+1}/{retries}] HTTP {res.status}, 재시도 중...",
                      file=sys.stderr)
        except urllib.error.HTTPError as e:
            print(f"[{channel_id}] [{attempt+1}/{retries}] HTTP {e.code}, 재시도 중...",
                  file=sys.stderr)
        except Exception as e:
            print(f"[{channel_id}] [{attempt+1}/{retries}] 오류: {e}", file=sys.stderr)
        if attempt < retries - 1:
            time.sleep(backoff ** attempt)
    return None


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
    xml = fetch_youtube_rss(args.channel_id, retries=args.retries,
                            backoff=args.backoff, timeout=args.timeout)
    if xml is None:
        print(f"실패: {args.channel_id}", file=sys.stderr)
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
    for ch in channels:
        cid = ch.get("id") if isinstance(ch, dict) else None
        if not cid:
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

    print(f"성공 {ok} / 실패 {len(failed)}", file=sys.stderr)
    if failed:
        print("실패 채널: " + ", ".join(failed), file=sys.stderr)
    return 0 if not failed else 1


if __name__ == "__main__":
    raise SystemExit(main())
