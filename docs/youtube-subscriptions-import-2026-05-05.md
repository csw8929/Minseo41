# YouTube 구독 리스트 가져오기 — SubFeed import 가이드

> 작성일: 2026-05-05 (JSON 전환 갱신: 2026-05-06)
> 대상: SubFeed 처음 셋업 / 단말 추가 / 구독 채널 다수를 한 번에 등록해야 하는 경우.
> 핵심: 한 채널씩 추가하는 단순 경로(Settings의 "채널 URL 직접 추가")부터, Google Takeout → JSON 변환 → 일괄 import까지 단계별로 정리.

---

## 0. import 경로

채널 등록은 **JSON 파일 import 단일 경로**입니다.

> 2026-05-06 이후 SubFeed의 import 형식은 **JSON 만** 받습니다. 이전의 "채널 URL 직접 추가" UI 와 ATOM XML 직접 import 는 모두 제거됐습니다 (Takeout XML은 아래 converter로 JSON 변환 후 import).
>
> 한두 개만 추가/수정하고 싶으면 JSON 파일을 직접 편집하거나 (부록 참고), Settings → "채널 편집" 에서 import 후 행 단위로 수정 / 삭제하세요.

---

## A. JSON 일괄 import

### A.0 — JSON 스키마

```json
[
  {
    "id": "UCxxxxxxxxxxxxxxxxxxxxxx",
    "name": "채널 A",
    "windowDays": 5,
    "maxCount": 10
  },
  {
    "id": "UCyyyyyyyyyyyyyyyyyyyyyy",
    "name": "채널 B"
  }
]
```

| 필드 | 타입 | 필수 | 의미 |
|---|---|---|---|
| `id` | string | ✓ | YouTube channel ID. `UC` + 22자 (총 24자). |
| `name` | string | ✓ | 표시 이름. |
| `windowDays` | int | (선택, default `1`) | 최근 N일 내 영상만 표시. `1` = 오늘만. |
| `maxCount` | int | (선택, default `15`) | 채널당 최대 N개까지 (RSS 15개 한도 안에서). |

`url` 필드는 받지 않습니다 — id로 RSS URL 자동 생성.

### A.1 — Takeout 받기

1. https://takeout.google.com/
2. 모두 해제 → "YouTube와 YouTube Music"만 체크 → "모든 YouTube 데이터 포함됨" 클릭
3. 모달에서 **모두 해제 → "구독"만 체크** → 확인
4. 형식은 가능하면 **OPML** 또는 **CSV** 선택 (HTML은 변환 어려움)
5. 다음 단계 → 이메일로 다운로드 링크 → ZIP 받기
6. ZIP 풀면 `Takeout/YouTube와 YouTube Music/구독/` 폴더에 파일 있음

### A.2 — Takeout XML이 있는 경우 → SubFeed converter 사용

(Takeout이 ATOM-style XML로 들어왔거나, 이전에 만들어 둔 `subscriptions.xml`이 있는 경우)

레포 루트의 `scripts/takeout-xml-to-json.py` 사용 (PowerShell에서는 반드시 `python` 명시):

```bash
# 기본 default (windowDays=1, maxCount=15)
python scripts/takeout-xml-to-json.py subscriptions.xml -o channels.json

# default 값 명시
python scripts/takeout-xml-to-json.py subscriptions.xml \
    --window-days 3 --max-count 10 \
    -o channels.json

# stdout으로 출력
python scripts/takeout-xml-to-json.py subscriptions.xml > channels.json
```

생성된 `channels.json`은 위 A.0 스키마를 그대로 만족.

### A.3 — Takeout이 CSV / OPML / JSON 이면

먼저 ATOM XML로 변환한 뒤 A.2의 converter에 통과시키거나, 직접 SubFeed JSON으로 변환합니다.

#### CSV (가장 흔한 형식)

CSV 구조:
```csv
Channel Id,Channel Url,Channel Title
UCxxx...,https://www.youtube.com/channel/UCxxx...,채널 A
```

직접 SubFeed JSON으로 변환:
```python
# csv_to_channels_json.py
import csv, json

with open('subscriptions.csv', encoding='utf-8') as f:
    reader = csv.DictReader(f)
    channels = [{
        "id":         row['Channel Id'],
        "name":       row['Channel Title'],
        "windowDays": 1,
        "maxCount":   15,
    } for row in reader]

with open('channels.json', 'w', encoding='utf-8') as f:
    json.dump(channels, f, ensure_ascii=False, indent=2)

print(f'{len(channels)}개 채널을 channels.json에 저장')
```

#### OPML

```python
# opml_to_channels_json.py
import json
import xml.etree.ElementTree as ET

tree = ET.parse('subscriptions.opml')
channels = []
for outline in tree.findall('.//outline[@xmlUrl]'):
    url = outline.get('xmlUrl', '')
    if 'channel_id=' not in url:
        continue
    cid = url.split('channel_id=')[1].split('&')[0]
    title = outline.get('title') or outline.get('text') or cid
    channels.append({
        "id":         cid,
        "name":       title,
        "windowDays": 1,
        "maxCount":   15,
    })

with open('channels.json', 'w', encoding='utf-8') as f:
    json.dump(channels, f, ensure_ascii=False, indent=2)

print(f'{len(channels)}개 채널을 channels.json에 저장')
```

#### YouTube Data API JSON (구글 시기에 따라)

```python
# youtube_json_to_channels_json.py
import json

data = json.load(open('subscriptions.json', encoding='utf-8'))
channels = [{
    "id":         item['snippet']['resourceId']['channelId'],
    "name":       item['snippet']['title'],
    "windowDays": 1,
    "maxCount":   15,
} for item in data]

json.dump(channels, open('channels.json', 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)
print(f'{len(channels)}개 채널을 channels.json에 저장')
```

### A.4 — 단말로 파일 전송

```bash
# adb (USB / WiFi 디버깅)
adb -s R3CT70FY0ZP push channels.json /sdcard/Download/

# Git Bash on Windows
MSYS_NO_PATHCONV=1 adb -s R3CT70FY0ZP push channels.json /sdcard/Download/
```

또는 Google Drive / 메일 첨부 후 단말에서 다운로드.

### A.5 — 앱에서 import

1. SubFeed 앱 → Settings 화면
2. "**JSON 파일 import**" 버튼
3. 시스템 파일 선택 dialog → `channels.json` 선택
4. 결과 메시지: "**N개 채널 import 됨**"
   - **기존 채널 목록은 모두 삭제되고 JSON 내용으로 교체됩니다.**
   - 즐겨찾기는 영향 받지 않습니다 (`favorites` 테이블은 별도).

### A.6 — 검증

- Settings 화면의 "구독 채널: N개" 갱신 확인
- Settings → "채널 편집" 진입 → 각 채널의 `windowDays` / `maxCount` 확인
- Feed 화면 새로고침 → 오늘의 영상 노출

---

## 채널 편집 (import 후)

Settings → "채널 편집" 진입 → 각 행:

- ✏ **편집**: 채널명 / windowDays / maxCount 변경
- 🗑 **삭제**: 단일 채널 제거 (즐겨찾기는 영향 없음)

---

## 자주 발생하는 문제

| 증상 | 원인 | 해결 |
|---|---|---|
| 파일 선택 dialog에 `.json` 파일이 안 보임 | 일부 단말의 파일 매니저가 MIME 필터를 엄격히 적용 | SubFeed의 launcher는 `application/json` / `*/*` 모두 허용. `*/*` 모드로 보이는 파일에서 `channels.json` 직접 선택 |
| "JSON import 실패: ..." 메시지 | JSON 형식이 깨졌거나 schema 불일치 | 첫 번째 항목이 `id`, `name`을 모두 갖는지 확인. JSON validator 통과 여부 확인 |
| 한글 채널명 깨짐 | 파일이 UTF-8이 아님 | converter 모두 `encoding='utf-8'` 명시. Windows 메모장으로 저장 시 BOM 주의 — UTF-8 (BOM 없음)으로 저장 |
| `0개 채널 import 됨` | JSON이 빈 배열 `[]` | 변환 단계 재확인 |
| Feed 화면이 비어있음 | 구독은 등록됐지만 windowDays 안에 영상이 없음 | "채널 편집"에서 windowDays 늘려보기 |

---

## 부록 — JSON 직접 작성 (예시 5개 채널)

```json
[
  { "id": "UCBR8-60-B28hp2BmDPdntcQ", "name": "YouTube",        "windowDays": 1, "maxCount": 5  },
  { "id": "UCYO_jab_esuFRV4b17AJtAw", "name": "3Blue1Brown",    "windowDays": 7, "maxCount": 3  },
  { "id": "UC4eYXhJI4-7wSWc8UNRwD4A", "name": "NPR",            "windowDays": 1, "maxCount": 10 },
  { "id": "UCJiQbwDjdkV4Q5XGWgYdrkw", "name": "유튜브 한국",    "windowDays": 1, "maxCount": 5  },
  { "id": "UCsT0YIqwnpJCM-mx7-gSA4Q", "name": "TEDx Talks",     "windowDays": 7, "maxCount": 3  }
]
```

---

## 참고

- Google Takeout: https://takeout.google.com/
- YouTube channel ID 형식: `UC` + 22자 base64-like 문자열 (총 24자)
- 코드 위치:
  - JSON 파서: `app/src/main/java/com/minseo41/subfeed/data/SubscriptionRepo.kt#importFromJson`
  - 채널 default 값: `app/src/main/java/com/minseo41/subfeed/model/SubscribedChannel.kt`
- Converter: `scripts/takeout-xml-to-json.py`
- 단말 다운로드 경로: `/sdcard/Download/`
