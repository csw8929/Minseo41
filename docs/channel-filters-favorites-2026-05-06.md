# Design: 채널 필터 + 즐겨찾기 + Room DB 도입

Generated 2026-05-06
Branch: main
Repo: Minseo41 (com.minseo41.subfeed)
Status: APPROVED 2026-05-06

## Problem Statement

현재 SubFeed의 한계:

1. **채널 import는 XML(Takeout)만 지원** — 형식이 ATOM이라 직접 작성하기 번거롭고, 채널별 옵션을 함께 넣을 곳이 없음.
2. **필터가 "오늘"로 하드코딩** — `SubscriptionRepo.fetchTodayVideos`에서 `uploadDate == today` 비교. 채널마다 업로드 빈도가 다른데 모든 채널을 같은 창으로 봄.
3. **채널 편집 UI 없음** — Settings에서 추가/삭제만 가능, name/필터 수정 불가.
4. **즐겨찾기 없음** — 영상 다시 보고 싶으면 RSS 15개 한도 안에서 찾아야 함. 며칠 지난 영상은 사라져 접근 불가.
5. **저장 포맷 한계** — `SharedPreferences("subscriptions")`에 `id;;name;;url|...` 평문 직렬화. 필드 추가하려면 형식 깨짐.

## What Makes This Cool

- 채널별 `windowDays` / `maxCount` — 매일 1편씩 올리는 채널은 1일/15개, 가끔 올리는 채널은 7일/3개 식으로 노이즈/관심도 균형
- ⭐ 즐겨찾기 — RSS 15개 한도 너머의 영상도 메타데이터를 DB에 박아두면 영구 접근 가능
- JSON import — 사람이 읽고 직접 편집 가능한 포맷 + 향후 sync/export 자연스러움
- Room DB — schema가 코드로 명시되어 마이그레이션/관계가 깔끔. 이후 동기화/통계 등 확장에도 유리

## Constraints

- 개인 사용 / 배포 없음
- Android-only, min SDK 26 / target 36
- Compose-only 일관성 유지
- Firestore는 시청위치만 sync (즐겨찾기는 로컬 only — user 결정)
- Shorts 제외는 이번 범위에서 제외 (별도 PR — InnerTube 호출 비용 검토 필요)
- 기존 사용자 데이터는 user 본인 1대뿐이므로 마이그레이션 코드 생략 — JSON import로 재투입

## Premises

1. **채널 ID로 RSS URL 복원 가능** — `https://www.youtube.com/feeds/videos.xml?channel_id={id}`. JSON 스키마에서 `url` 필드 제거.
2. **windowDays는 "최근 N일"** — `today.minusDays(N-1)` 부터 today까지. `1` 이면 오늘만.
3. **maxCount는 windowDays 필터 후의 상한** — 한 채널이 하루에 5개 올렸어도 maxCount=3이면 3개만 표시. RSS 15개 상한 안에서 동작.
4. **즐겨찾기는 영상 단위(videoId)** — title/썸네일/channelName/uploadedAt 메타를 ⭐ 시점에 함께 DB에 저장. 재생은 `PlayerScreen.streamUrl` 추출이 성공하는 한 가능.
5. **JSON import는 channels 테이블 wipe + insert** — favorites 테이블은 보존. import에서 빠진 채널은 사라짐(=삭제 의도).
6. **TabRow는 FeedScreen 안에 배치** — 라우트 분리 대신 단일 route + 두 탭. ViewModel은 `Tab.Today` / `Tab.Favorites` state로 분기.

## JSON 스키마

```json
[
  {
    "id": "UCxxxxxxxxxxxxxxxxxxxxxx",
    "name": "채널명",
    "windowDays": 5,
    "maxCount": 10
  }
]
```

- `id`: YouTube channel ID (UC… 24자). 필수.
- `name`: 표시명. 필수.
- `windowDays`: 1 이상 정수. 누락 시 default `1`.
- `maxCount`: 1 이상 정수. 누락 시 default `15`.
- `url` 필드는 의도적으로 없음 — id에서 RSS URL 자동 생성.

## Approaches Considered

### Approach A: Room DB로 전면 이전 (선택)

- 새 `data/db/SubFeedDatabase.kt` + `ChannelDao` + `FavoriteDao` + `ChannelEntity` + `FavoriteEntity`
- Hilt `DatabaseModule` 추가
- `SubscriptionRepo`가 Dao 사용, SharedPreferences "subscriptions" 키는 폐기
- Effort: 인간 ~3-4시간 / CC ~30-45분
- Pros: schema 명시, type-safe, 즐겨찾기 + 채널 한 DB에 깔끔하게 분리, Flow 노출로 UI 자동 갱신
- Cons: 의존성 추가(`androidx.room`), KSP 컴파일 시간 약간 증가

### Approach B: SharedPreferences 위에 JSON 직렬화

- `Gson` / `kotlinx.serialization`으로 `List<SubscribedChannel>` 직렬화
- 즐겨찾기는 별도 SharedPreferences 키
- Effort: 인간 ~1-2시간 / CC ~20분
- Pros: 의존성 추가 거의 없음
- Cons: user가 명시적으로 DB 선호. 즐겨찾기 N개 row 검색/정렬 시 매번 전체 파싱. 향후 동기화/통계 시 다시 갈아엎어야 함

### Approach C: DataStore (Proto / Preferences)

- `androidx.datastore`
- Effort: 비슷
- Pros: SharedPreferences보다 modern
- Cons: schema 변화 자유도는 Room보다 낮음, user가 DB를 직접 선호

## Recommended Approach

**Approach A — Room DB.** user가 명시적으로 DB 선호 + 즐겨찾기/채널 두 entity가 동시에 늘어나는 변경이라 schema 명시의 가치가 큼.

## 새 / 변경 파일

```
app/src/main/
├── java/com/minseo41/subfeed/
│   ├── data/
│   │   ├── db/                                # 신규 패키지
│   │   │   ├── SubFeedDatabase.kt             # Room DB (entities + version)
│   │   │   ├── ChannelEntity.kt               # @Entity tableName="channels"
│   │   │   ├── ChannelDao.kt                  # Flow<List<ChannelEntity>>, insertAll, deleteAll, update, deleteById
│   │   │   ├── FavoriteEntity.kt              # @Entity tableName="favorites"
│   │   │   └── FavoriteDao.kt                 # Flow<List<FavoriteEntity>>, insert, deleteByVideoId, exists
│   │   ├── SubscriptionRepo.kt                # 수정 — SharedPreferences 제거, Dao 사용, parseJson 추가, fetchTodayVideos가 채널별 windowDays/maxCount 적용
│   │   └── FavoriteRepo.kt                    # 신규 — toggleFavorite(VideoItem), favoritesFlow, isFavorite
│   ├── di/
│   │   ├── DatabaseModule.kt                  # 신규 — Room 빌드, Dao provide
│   │   └── AppModule.kt                       # 수정 — FavoriteRepo provide
│   ├── model/
│   │   └── SubscribedChannel.kt               # 수정 — windowDays, maxCount 필드 추가
│   ├── ui/
│   │   ├── FeedScreen.kt                      # 수정 — TabRow, ⭐ 버튼, 즐겨찾기 탭 분기
│   │   ├── FeedViewModel.kt                   # 수정 — selectedTab state, favoritesFlow, toggleFavorite, isFavorite map
│   │   ├── SettingsScreen.kt                  # 수정 — JSON import 버튼, 채널 편집 진입점
│   │   ├── SettingsViewModel.kt               # 수정 — JSON parse + import
│   │   └── settings/
│   │       ├── ChannelEditScreen.kt           # 신규 — 채널 리스트 + 행별 편집/삭제
│   │       └── ChannelEditViewModel.kt        # 신규
│   └── AppNavigation                          # 수정 — settings/channels 라우트 추가
├── docs/
│   └── youtube-subscriptions-import-2026-05-05.md  # 수정 — JSON 스키마 + converter 사용법
└── scripts/
    └── takeout-xml-to-json.py                 # 신규 — Takeout subscriptions.xml → SubFeed JSON converter
```

### 의존성 추가 (`gradle/libs.versions.toml`, `app/build.gradle.kts`)

```toml
[versions]
room = "2.6.1"
kotlinx-serialization = "1.7.3"

[libraries]
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinx-serialization" }

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

`app/build.gradle.kts`:
- `plugins { alias(libs.plugins.kotlin.serialization) }`
- `implementation(libs.room.runtime)` / `implementation(libs.room.ktx)` / `ksp(libs.room.compiler)`
- `implementation(libs.kotlinx.serialization.json)`

## Schema

```kotlin
@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val windowDays: Int,    // 기본 1
    val maxCount: Int,      // 기본 15
    val sortOrder: Int,     // import 순서 보존용
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String?,
    val thumbnailUrl: String,
    val uploadedAt: Long,   // epoch millis
    val addedAt: Long,      // epoch millis — 즐겨찾기 정렬용 (최근 추가 순)
)
```

## UI 변경

### FeedScreen

```
┌─────────────────────────────────────┐
│  SubFeed              [⚙ Settings]  │
├─────────────────────────────────────┤
│  [오늘의 구독영상]  [즐겨찾기]      │   ← Material3 TabRow
├─────────────────────────────────────┤
│  ┌──┐  영상 제목                ⭐  │   ← 행 우측 별표
│  │썸│  채널명 · 12분 전               │      tap → toggle
│  └──┘                               │
│  ┌──┐  영상 제목                ☆  │
│  └──┘  ...                          │
└─────────────────────────────────────┘
```

- ⭐ 노란색(`#FFD700`) = 즐겨찾기 / ☆ 회색 outline = 일반
- 같은 row UI 컴포넌트가 두 탭에서 모두 사용 (즐겨찾기 탭에서 ⭐ 다시 누르면 제거 → 리스트에서 사라짐)

### Settings

```
[Google 계정 섹션]                    ← 기존
[채널 import (JSON)]                  ← 신규 — file picker → JSON
[채널 편집]                           ← 신규 — 라우트 push
[기존: 채널 URL 직접 추가]            ← 유지
```

### ChannelEditScreen (신규)

```
┌─────────────────────────────────────┐
│  ← 채널 편집                         │
├─────────────────────────────────────┤
│  채널명                       [✏][🗑]│
│  최근 N일: 5    최대 개수: 10       │
├─────────────────────────────────────┤
│  ...                                │
└─────────────────────────────────────┘
```

- ✏ tap → name / windowDays / maxCount 편집 다이얼로그
- 🗑 tap → 확인 후 삭제

## Converter 스크립트 (`scripts/takeout-xml-to-json.py`)

```python
#!/usr/bin/env python3
"""Takeout subscriptions.xml → SubFeed JSON.

Usage:
  python takeout-xml-to-json.py subscriptions.xml > channels.json
  python takeout-xml-to-json.py subscriptions.xml --window-days 1 --max-count 15 > channels.json
"""
import argparse
import json
import sys
import xml.etree.ElementTree as ET

ATOM_NS = {
    "atom": "http://www.w3.org/2005/Atom",
    "yt":   "http://www.youtube.com/xml/schemas/2015",
}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("xml_path")
    ap.add_argument("--window-days", type=int, default=1)
    ap.add_argument("--max-count",   type=int, default=15)
    args = ap.parse_args()

    tree = ET.parse(args.xml_path)
    root = tree.getroot()

    channels = []
    for entry in root.findall("atom:entry", ATOM_NS):
        cid_el  = entry.find("yt:channelId", ATOM_NS)
        name_el = entry.find("atom:title",   ATOM_NS)
        if cid_el is None or name_el is None:
            continue
        channels.append({
            "id": cid_el.text.strip(),
            "name": name_el.text.strip(),
            "windowDays": args.window_days,
            "maxCount":   args.max_count,
        })

    json.dump(channels, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")

if __name__ == "__main__":
    main()
```

## 구현 순서

| Step | 내용 | 의존성 |
|---|---|---|
| 1 | gradle: Room + kotlinx-serialization 의존성 추가 | — |
| 2 | `data/db/` Entity + Dao + Database | 1 |
| 3 | `di/DatabaseModule.kt` Hilt provide | 2 |
| 4 | `model/SubscribedChannel` 확장 (windowDays, maxCount) + entity ↔ model 매핑 | 2 |
| 5 | `SubscriptionRepo` Room으로 전환 + JSON import 함수 + `fetchTodayVideos`가 채널별 필터 적용 | 3,4 |
| 6 | `FavoriteRepo` 신규 | 3 |
| 7 | `FeedViewModel` selectedTab + favoritesFlow + toggleFavorite | 5,6 |
| 8 | `FeedScreen` TabRow + ⭐ 버튼 + 즐겨찾기 탭 | 7 |
| 9 | `ChannelEditScreen` + ViewModel + 라우트 등록 | 5 |
| 10 | `SettingsScreen` JSON import 버튼 + 채널편집 진입점 | 5,9 |
| 11 | `scripts/takeout-xml-to-json.py` converter 작성 | — |
| 12 | docs 갱신: `youtube-subscriptions-import-2026-05-05.md` JSON 스키마 + converter 사용법 추가 / development-log 업데이트 | — |
| 13 | 빌드 + 폴드 단말 설치 + JSON import + ⭐ toggle + 채널 편집 검증 | 1-12 |

## Success Criteria

- [ ] JSON 파일 한 개 import → channels 테이블이 그 내용으로 교체됨 (favorites 보존)
- [ ] 채널별 windowDays/maxCount이 fetch 결과에 반영됨 (5일/3개로 설정한 채널은 최근 5일 내 최대 3개만 노출)
- [ ] FeedScreen 상단에 두 탭. "오늘의 구독영상" 기본 선택
- [ ] 영상 row의 ⭐ 토글이 즐겨찾기 탭과 즉시 동기화됨 (Flow 기반)
- [ ] 즐겨찾기 영상은 며칠 뒤 RSS에서 사라져도 즐겨찾기 탭에 표시 + 재생 시도 가능
- [ ] Settings → "채널 편집"에서 name / windowDays / maxCount 수정, 행 삭제 가능
- [ ] `scripts/takeout-xml-to-json.py` 가 Takeout XML을 받아 SubFeed JSON 출력
- [ ] BUILD SUCCESSFUL + 폴드(R3CT70FY0ZP) 설치 후 위 항목 실측

## Open Questions

1. **JSON file picker MIME 타입** — `application/json` 만 허용 vs `*/*` (Takeout 변환 결과 .json 확장자 통일 가정 시 전자) → 전자로 가되 OpenDocument는 `*/*` fallback도 허용하는 식
2. **채널 편집 다이얼로그 vs 별도 화면** — 다이얼로그 1개로 가는 게 짧음. 다이얼로그 채택
3. **즐겨찾기 화면에서 채널별 필터/정렬** — 일단 `addedAt DESC` 단일 정렬. 나중에 필요하면 추가
4. **`addedAt` vs `uploadedAt`** — 즐겨찾기 정렬은 `addedAt DESC` (최근 즐겨찾기한 순). 영상 업로드 순으로 보고 싶으면 추후 토글
5. **Room migration** — 첫 도입이므로 version=1, fallback `fallbackToDestructiveMigration` 안 씀. 다음 schema 변경 때 migration 작성

## Status

DRAFT — 아래 항목 확인 부탁드립니다:

- [ ] 위 schema/UI/순서 OK → APPROVED 처리하고 구현 시작
- [ ] 변경 요청 있으면 알려주세요 (특히 default windowDays=1, maxCount=15 / TabRow 위치 / 채널 편집을 다이얼로그로 통합)
