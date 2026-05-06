# SubFeed

YouTube 구독 채널의 최근 영상을 광고 없이 재생하고, 여러 Android 기기 간 시청 위치를 동기화하는 개인용 Android 앱.

- 패키지: `com.minseo41.subfeed`
- min SDK 26 / target SDK 36 / Kotlin / Compose

## 빌드 & 설치

```bash
./gradlew assembleDebug
./gradlew installDebug

# 특정 단말에 설치
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

## 채널 import (JSON)

채널 등록은 **JSON 파일 import 단일 경로**입니다.

**앱 메뉴 경로**: 메인 화면 우측 상단 ⚙ (설정) 아이콘 → **설정** 화면 → "**구독 채널 import**" 섹션 → **"JSON 파일 import"** 버튼 → 시스템 파일 선택 dialog에서 `channels.json` 선택 → "N개 채널 import 됨" 메시지 확인.

import 후 채널별 `windowDays` / `maxCount` 수정은 **설정 → "채널 편집"** (구독 채널이 1개 이상일 때 활성화)에서 행 단위로 ✏ 편집 / 🗑 삭제.

### JSON 스키마

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

| 필드 | 타입 | 필수 | 기본값 | 의미 |
|---|---|---|---|---|
| `id` | string | ✓ | — | YouTube channel ID. `UC` + 22자 (총 24자). |
| `name` | string | ✓ | — | 표시 이름. |
| `windowDays` | int | ✗ | `1` | 최근 N일 내 영상만 표시. `1` = 오늘만. |
| `maxCount` | int | ✗ | `15` | 채널당 최대 노출 개수 (RSS 15개 한도 안에서). |

- `url` 필드는 받지 않습니다 — `id`로 RSS URL 자동 생성.
- `id` / `name` 누락 시 해당 항목 import 실패.
- 알 수 없는 필드는 무시됩니다 (`ignoreUnknownKeys = true`).

### 동작

- **채널 교체**: import 시 기존 `channels` 테이블이 모두 삭제되고 JSON 내용으로 교체됩니다.
- **즐겨찾기 보존**: `favorites` 테이블은 손대지 않습니다.
- **순서**: JSON 배열 순서가 그대로 표시 순서로 저장됩니다 (`sortOrder`).

### 샘플 (5개 채널)

```json
[
  { "id": "UCBR8-60-B28hp2BmDPdntcQ", "name": "YouTube",       "windowDays": 1, "maxCount": 5  },
  { "id": "UCYO_jab_esuFRV4b17AJtAw", "name": "3Blue1Brown",   "windowDays": 7, "maxCount": 3  },
  { "id": "UC4eYXhJI4-7wSWc8UNRwD4A", "name": "NPR",           "windowDays": 1, "maxCount": 10 },
  { "id": "UCJiQbwDjdkV4Q5XGWgYdrkw", "name": "유튜브 한국",   "windowDays": 1, "maxCount": 5  },
  { "id": "UCsT0YIqwnpJCM-mx7-gSA4Q", "name": "TEDx Talks",    "windowDays": 7, "maxCount": 3  }
]
```

### YouTube 구독 리스트 가져오기 (Google Takeout)

1. https://takeout.google.com/ 접속 (구독 채널을 보유한 Google 계정으로 로그인)
2. **"선택 안함"** 클릭 → 목록에서 **"YouTube 및 YouTube Music"** 만 체크
3. "모든 YouTube 데이터 포함됨" 클릭 → 모달에서 **"선택 안함"** → **"구독"** 만 체크 → 확인
4. 형식: 가능하면 **OPML** 또는 **CSV** 선택 (HTML은 변환이 어려움)
5. "다음 단계" → 내보내기 1회 / ZIP / 2GB → **"내보내기 만들기"**
6. 이메일로 도착하는 다운로드 링크에서 ZIP 받기 → 압축 해제
7. `Takeout/YouTube와 YouTube Music/구독/` 폴더의 파일 사용
   - `subscriptions.csv` (CSV 선택 시)
   - `subscriptions.opml` (OPML 선택 시)
   - `subscriptions.xml` (이전 Takeout의 ATOM XML)

> SubFeed는 **JSON만** import 받습니다. 위 파일 형식은 다음 단계에서 JSON으로 변환합니다.

### Takeout 파일 → JSON 변환

#### ATOM XML → JSON (레포의 converter 사용)

Google Takeout에서 받은 `subscriptions.xml`을 한 번에 변환:

```bash
python scripts/takeout-xml-to-json.py subscriptions.xml -o channels.json

# 기본 windowDays / maxCount 명시
python scripts/takeout-xml-to-json.py subscriptions.xml \
    --window-days 3 --max-count 10 -o channels.json
```

#### CSV / OPML → JSON

CSV / OPML 변환 Python 스니펫은 `docs/youtube-subscriptions-import-2026-05-05.md` §A.3 참고.

### 단말로 파일 전송 후 import

```bash
# adb로 push
adb -s <serial> push channels.json /sdcard/Download/
```

또는 Google Drive / 메일 / USB 복사 등 어떤 방법으로든 단말에 `channels.json`을 올려두고:

1. SubFeed 실행 → 메인 화면 우측 상단 ⚙ → **설정**
2. "구독 채널 import" 섹션 → **"JSON 파일 import"** 탭
3. 시스템 파일 선택 dialog에서 `channels.json` 선택
4. "**N개 채널 import 됨**" 메시지 확인 → 메인 화면 새로고침 시 영상 노출

> import 시 기존 채널 목록은 모두 교체됩니다. 즐겨찾기 영상은 보존됩니다.

## 디렉토리

```
app/src/main/java/com/minseo41/subfeed/
├── data/                       # Repo + DB + VideoExtractor
│   └── db/                     # Room (channels, favorites)
├── service/                    # MediaSessionService (백그라운드 재생)
├── ui/                         # Compose 화면 + ViewModel
└── di/                         # Hilt 모듈
scripts/takeout-xml-to-json.py  # Takeout XML → JSON converter
docs/                           # 설계/구현 기록
```

## 관련 문서

| 문서 | 내용 |
|---|---|
| `docs/design.md` | MVP 설계 |
| `docs/player-screen-renewal-2026-05-05.md` | PlayerScreen 리뉴얼 설계 |
| `docs/channel-filters-favorites-2026-05-06.md` | 채널 필터 + 즐겨찾기 + Room 설계 |
| `docs/firebase-setup-2026-05-05.md` | Firebase 셋업 가이드 |
| `docs/youtube-subscriptions-import-2026-05-05.md` | 구독 import 상세 가이드 |
| `docs/development-log.md` | 시간순 구현/디버깅 로그 |
| `CLAUDE.md` | Claude Code용 작업 가이드 |
