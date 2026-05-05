# YouTube 구독 리스트 가져오기 — SubFeed import 가이드

> 작성일: 2026-05-05
> 대상: SubFeed 처음 셋업 / 단말 추가 / 구독 채널 다수를 한 번에 등록해야 하는 경우.
> 핵심: 한 채널씩 추가하는 단순 경로(Settings의 "채널 URL 직접 추가")부터, Google Takeout 일괄 import + XML 변환까지 단계별로 정리.

---

## 0. 두 가지 경로

| 경로 | 언제 쓰나 | 난이도 |
|---|---|---|
| **A. 채널 URL 직접 추가** (Settings UI) | 구독 채널이 5~10개 이내, 또는 한두 개 추가만 필요 | 가장 쉬움 |
| **B. Takeout XML 일괄 import** | 구독 채널이 수십 개 이상 | 변환 단계 포함, 약간 손이 감 |

---

## A. 채널 URL 직접 추가

가장 단순. Takeout 없이 끝나는 경로.

### 절차

1. SubFeed 앱 → Settings 화면
2. "채널 URL 직접 추가" 섹션의 텍스트 입력란
3. YouTube 채널 URL 붙여넣기. 다음 형식 모두 동작:
   - `https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxx`
   - `https://www.youtube.com/@핸들이름` (이건 비추 — 채널 ID 추출이 핸들 그대로 들어감)
   - 권장: **`/channel/UC...` 형식**. 이 부분이 channel ID로 그대로 사용됨.
4. **추가** 버튼

### 채널 ID(`UC...`) 찾는 방법

YouTube에서 핸들(`@channel`)만 보이고 채널 ID가 안 보이는 경우:

1. 해당 채널 페이지 접속 (예: `https://www.youtube.com/@whatever`)
2. 우클릭 → 페이지 소스 보기 (Ctrl+U)
3. `Ctrl+F`로 `"channelId"` 또는 `UC` 검색
4. `"channelId":"UCxxxxxxxxxxxxxxxxxxxxxx"` 형태 발견 → 그 24자 (UC + 22자) 복사

또는 third-party 사이트 (commentpicker.com, streamweasels.com 등)에서 핸들 → channel ID 변환.

### 한계

- 한 번에 한 채널만 추가. 수십 개라면 노가다.
- 그래서 다음 경로 B가 존재.

---

## B. Google Takeout으로 일괄 import

### 핵심 사실

**SubFeed 코드(`SubscriptionRepo.parseYoutubeTakeoutXml`)는 ATOM-style XML을 파싱한다.** 구체적으로 다음 구조를 기대:

```xml
<feed>
  <entry>
    <yt:channelId>UCxxxxxxxxxxxxxxxxxxxxxx</yt:channelId>
    <title>채널 이름</title>
  </entry>
  <entry>
    <yt:channelId>UCyyy...</yt:channelId>
    <title>다른 채널</title>
  </entry>
</feed>
```

- 외곽 태그 이름은 무관 (`<feed>`, `<rss>`, `<opml>`, 어떤 root든 상관없음)
- 각 채널은 `<entry>` 태그로 감싸야 함
- 각 entry 안에 정확히 `<yt:channelId>...</yt:channelId>`와 `<title>...</title>` 두 자식 태그 (XmlPullParser는 namespace-unaware로 사용 중이라 `yt:channelId`는 prefix 포함 그대로 매칭)

**Google Takeout이 직접 주는 형식과 다를 수 있다.** Takeout YouTube subscriptions export는 시기에 따라 다음 중 하나:
- **CSV** (현재 기본 형식): `Channel Id, Channel Url, Channel Title`
- **JSON** (옵션 따라 제공)
- **OPML** (옛 형식, 지금은 잘 안 줌): `<outline xmlUrl="..."/>` 표준

→ 받은 형식이 CSV/OPML이면 **위 ATOM-style XML로 변환**해야 한다. 아래 변환 절차가 그 단계.

### B.1 — Takeout에서 구독 데이터 export

1. https://takeout.google.com/ 접속, 본인 Google 계정으로 로그인
2. **모든 항목 선택 해제** 클릭 (기본은 전부 체크되어 있음)
3. 목록을 스크롤하며 "**YouTube와 YouTube Music**"만 체크
4. 그 항목의 **모든 YouTube 데이터 포함됨** 버튼 클릭
5. 모달이 뜨면 **모두 선택 해제** → "**구독**"만 체크 → 확인
   - 다른 항목(시청 기록, 라이브 채팅 등)은 무관, 용량만 늘어남
6. **여러 형식 (HTML)**이라 표시된 부분 클릭 → 가능하면 **OPML** 또는 **CSV** 선택 (HTML은 파싱 어려움)
7. 페이지 하단 **다음 단계** 버튼
8. 전송 방법: "이메일로 다운로드 링크 보내기" (가장 단순)
9. 빈도: "한 번 내보내기"
10. 파일 형식: `.zip`, 크기 제한 1GB (구독만 export하면 수 KB)
11. **내보내기 만들기** 클릭

5분~수 시간 안에 메일이 옴 → 다운로드 링크 → zip 받기.

### B.2 — 받은 파일 형식 확인

zip 압축 풀면 `Takeout/YouTube와 YouTube Music/구독/` 폴더 안에 파일이 있다. 확장자로 구분:
- **`subscriptions.csv`** — CSV 형식 (가장 흔함)
- **`subscriptions.json`** — JSON 형식
- **`subscriptions.opml`** — OPML 형식 (옛 export)

각 형식별 처리:

#### B.2.1 — OPML로 받은 경우

OPML은 다음 같은 구조:
```xml
<opml version="1.1">
  <body>
    <outline title="구독 채널들" text="구독 채널들">
      <outline xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=UCxxx..." text="채널 A" title="채널 A" />
      <outline xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=UCyyy..." text="채널 B" title="채널 B" />
    </outline>
  </body>
</opml>
```

→ ATOM-style로 변환 필요. PowerShell 한 줄:

```powershell
# subscriptions.opml → subscriptions.xml
[xml]$opml = Get-Content subscriptions.opml -Encoding UTF8
$entries = $opml.opml.body.outline.outline | ForEach-Object {
    $url = $_.xmlUrl
    $channelId = ($url -split 'channel_id=')[1]
    $title = $_.title -replace '&', '&amp;' -replace '<', '&lt;' -replace '>', '&gt;'
    "  <entry>`n    <yt:channelId>$channelId</yt:channelId>`n    <title>$title</title>`n  </entry>"
}
@"
<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015">
$($entries -join "`n")
</feed>
"@ | Out-File -Encoding utf8 subscriptions.xml
```

#### B.2.2 — CSV로 받은 경우

CSV 구조:
```csv
Channel Id,Channel Url,Channel Title
UCxxx...,https://www.youtube.com/channel/UCxxx...,채널 A
UCyyy...,https://www.youtube.com/channel/UCyyy...,채널 B
```

→ ATOM-style로 변환. PowerShell:

```powershell
# subscriptions.csv → subscriptions.xml
$rows = Import-Csv subscriptions.csv -Encoding UTF8
$entries = $rows | ForEach-Object {
    $channelId = $_.'Channel Id'
    $title = $_.'Channel Title' -replace '&', '&amp;' -replace '<', '&lt;' -replace '>', '&gt;'
    "  <entry>`n    <yt:channelId>$channelId</yt:channelId>`n    <title>$title</title>`n  </entry>"
}
@"
<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015">
$($entries -join "`n")
</feed>
"@ | Out-File -Encoding utf8 subscriptions.xml
```

또는 Python:

```python
# csv2xml.py — subscriptions.csv → subscriptions.xml
import csv
import html

with open('subscriptions.csv', encoding='utf-8') as f:
    reader = csv.DictReader(f)
    entries = []
    for row in reader:
        cid = row['Channel Id']
        title = html.escape(row['Channel Title'])
        entries.append(f'  <entry>\n    <yt:channelId>{cid}</yt:channelId>\n    <title>{title}</title>\n  </entry>')

xml = (
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015">\n'
    + '\n'.join(entries) + '\n'
    '</feed>\n'
)

with open('subscriptions.xml', 'w', encoding='utf-8') as f:
    f.write(xml)

print(f'Wrote {len(entries)} entries to subscriptions.xml')
```

실행:
```bash
python csv2xml.py
```

#### B.2.3 — JSON으로 받은 경우

JSON 구조 (구글이 시기마다 변형하므로 받은 파일 직접 열어보고 키 이름 확인):
```json
[
  { "snippet": { "resourceId": { "channelId": "UCxxx..." }, "title": "채널 A" } },
  ...
]
```

→ Python 변환 (위와 비슷, JSON parsing만 다름):
```python
import json, html
data = json.load(open('subscriptions.json', encoding='utf-8'))
entries = []
for item in data:
    cid = item['snippet']['resourceId']['channelId']
    title = html.escape(item['snippet']['title'])
    entries.append(f'  <entry>\n    <yt:channelId>{cid}</yt:channelId>\n    <title>{title}</title>\n  </entry>')

with open('subscriptions.xml', 'w', encoding='utf-8') as f:
    f.write(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015">\n'
        + '\n'.join(entries) + '\n</feed>\n'
    )
```

### B.3 — 변환된 `subscriptions.xml` 검증

파일 열어서 다음 두 조건 만족 확인:
- 모든 채널이 `<entry>...</entry>` 블록으로 감싸져 있음
- 각 entry 안에 `<yt:channelId>` 와 `<title>`이 둘 다 있음

채널 수 카운트 (PowerShell):
```powershell
([regex]::Matches((Get-Content subscriptions.xml -Raw), '<entry>')).Count
```

### B.4 — 단말로 파일 전송

세 가지 옵션:

#### 옵션 a) adb push (USB / WiFi 디버깅)

```bash
# Windows / macOS / Linux
adb -s R3CT70FY0ZP push subscriptions.xml /sdcard/Download/

# Git Bash on Windows (워크스페이스 경로 변환 회피)
MSYS_NO_PATHCONV=1 adb -s R3CT70FY0ZP push subscriptions.xml /sdcard/Download/
```

`/sdcard/Download/`은 단말의 표준 다운로드 폴더. 앱의 파일 선택 dialog에서 가장 쉽게 접근.

#### 옵션 b) Google Drive 경유

1. PC에서 Google Drive에 `subscriptions.xml` 업로드
2. 단말에서 Drive 앱 → 같은 파일 → "다운로드"
3. 단말 다운로드 폴더에 자동 저장

#### 옵션 c) 메일 첨부

1. 본인 메일로 첨부 전송
2. 단말 메일 앱에서 첨부 → "다운로드" 또는 "저장"

### B.5 — 앱에서 import

1. SubFeed 앱 → Settings 화면
2. "**YouTube Takeout XML 불러오기**" 버튼 클릭
3. 시스템 파일 선택 dialog 뜸 → `/sdcard/Download/subscriptions.xml` 선택
4. 결과 메시지: "**N개 채널 추가됨**"
   - 이미 등록된 채널은 자동 skip (channel ID 기준 중복 제거)
   - 빈 결과: "채널을 찾지 못했습니다. XML 파일을 확인해주세요." → XML 형식이 잘못된 경우. B.3 다시 검증.

### B.6 — 검증

Settings 화면 하단에 "구독 채널: N개" 표시가 갱신됐는지 확인.

Feed 화면으로 돌아가 새로고침 → 오늘 영상이 있으면 표시됨. 없으면 "오늘 새 영상이 없습니다" 메시지.

---

## 자주 발생하는 문제

| 증상 | 원인 | 해결 |
|---|---|---|
| 파일 선택 dialog에 .xml 파일이 안 보임 | 일부 단말의 파일 매니저가 MIME 필터를 엄격히 적용 | SubFeed의 파일 launcher는 `text/xml`, `application/xml`, `*/*`를 모두 허용. `*/*` 모드로 보이는 파일에서 `subscriptions.xml`을 직접 선택 |
| "채널을 찾지 못했습니다" | XML 구조가 다름 | B.3 검증. `<entry>` 태그 안에 `<yt:channelId>`와 `<title>`이 둘 다 있는지 확인 |
| 한글 채널명이 깨져서 표시됨 | 파일 인코딩이 UTF-8이 아님 | PowerShell 변환 시 `-Encoding utf8` 명시. Python 변환 시 `encoding='utf-8'` 명시 |
| `0개 채널 추가됨` | 모두 이미 등록된 채널 (중복) | 정상. 새 채널만 추가됨 |
| Feed 화면이 비어있음 | 구독은 등록됐지만 오늘 새 영상이 없음 | 채널이 활성인지 YouTube에서 직접 확인 |

---

## 부록 — XML 직접 작성 (예시 5개 채널)

소수 채널만 등록하는데 변환 스크립트 돌리기 귀찮으면 텍스트 에디터로 직접:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015">
  <entry>
    <yt:channelId>UCBR8-60-B28hp2BmDPdntcQ</yt:channelId>
    <title>YouTube</title>
  </entry>
  <entry>
    <yt:channelId>UCYO_jab_esuFRV4b17AJtAw</yt:channelId>
    <title>3Blue1Brown</title>
  </entry>
  <entry>
    <yt:channelId>UC4eYXhJI4-7wSWc8UNRwD4A</yt:channelId>
    <title>NPR</title>
  </entry>
  <entry>
    <yt:channelId>UCJiQbwDjdkV4Q5XGWgYdrkw</yt:channelId>
    <title>유튜브 공식 한국어</title>
  </entry>
  <entry>
    <yt:channelId>UCsT0YIqwnpJCM-mx7-gSA4Q</yt:channelId>
    <title>TEDx Talks</title>
  </entry>
</feed>
```

`<title>` 안에 `&`, `<`, `>`가 들어가면 각각 `&amp;`, `&lt;`, `&gt;`로 escape.

---

## 참고

- Google Takeout: https://takeout.google.com/
- YouTube channel ID 형식: `UC` + 22자 base64-like 문자열 (총 24자)
- 코드 위치: `app/src/main/java/com/minseo41/subfeed/data/SubscriptionRepo.kt#parseYoutubeTakeoutXml`
- 단말 다운로드 경로: `/sdcard/Download/` (모든 표준 단말)
