#!/usr/bin/env python3
"""Takeout subscriptions.xml -> SubFeed channels.json.

Usage:
  python takeout-xml-to-json.py subscriptions.xml > channels.json
  python takeout-xml-to-json.py subscriptions.xml --window-days 1 --max-count 15 > channels.json
  python takeout-xml-to-json.py subscriptions.xml -o channels.json
"""
import argparse
import json
import sys
import xml.etree.ElementTree as ET

ATOM_NS = {
    "atom": "http://www.w3.org/2005/Atom",
    "yt": "http://www.youtube.com/xml/schemas/2015",
}


def main() -> int:
    ap = argparse.ArgumentParser(description="Takeout subscriptions.xml -> SubFeed channels.json")
    ap.add_argument("xml_path", help="Path to Takeout subscriptions.xml")
    ap.add_argument("--window-days", type=int, default=1,
                    help="Default windowDays applied to every channel (default: 1)")
    ap.add_argument("--max-count", type=int, default=15,
                    help="Default maxCount applied to every channel (default: 15)")
    ap.add_argument("-o", "--output", default=None,
                    help="Output file path (default: stdout)")
    args = ap.parse_args()

    try:
        tree = ET.parse(args.xml_path)
    except (ET.ParseError, FileNotFoundError) as e:
        print(f"XML 파싱 실패: {e}", file=sys.stderr)
        return 1
    root = tree.getroot()

    channels = []
    seen_ids = set()
    for entry in root.findall("atom:entry", ATOM_NS):
        cid_el = entry.find("yt:channelId", ATOM_NS)
        name_el = entry.find("atom:title", ATOM_NS)
        if cid_el is None or cid_el.text is None:
            continue
        cid = cid_el.text.strip()
        if not cid or cid in seen_ids:
            continue
        seen_ids.add(cid)
        name = name_el.text.strip() if (name_el is not None and name_el.text) else cid
        channels.append({
            "id": cid,
            "name": name,
            "windowDays": args.window_days,
            "maxCount": args.max_count,
        })

    output = json.dumps(channels, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(output)
        print(f"{len(channels)}개 채널을 {args.output} 에 저장했습니다.", file=sys.stderr)
    else:
        sys.stdout.write(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
