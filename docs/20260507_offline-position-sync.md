# Design: 비로그인 시청 위치 로컬 저장 + Firestore-우선 hybrid 조회

Generated 2026-05-07
Branch: main
Repo: Minseo41 (com.minseo41.subfeed)
Status: DRAFT

## Problem Statement

현재 SubFeed의 시청 위치(`WatchPosition.positionMs`) 저장/조회는 `SyncRepo`가 Firestore 단일 backend로 동작.

```kotlin
// 현재 동작
val u = uid                                  // FirebaseAuth.currentUser.uid
if (u == null) return                        // 비로그인 → 그냥 skip
firestore.collection("users")...set(...)     // 로그인 → Firestore
```

문제:

1. **비로그인 상태에선 시청 위치가 어디에도 저장되지 않음.** 로그인 전에는 같은 단말에서조차 이어보기가 안 됨 — 영상 30초 보고 뒤로가도 다음에 다시 0초부터.
2. **Firebase 다운/네트워크 끊김 시 위치 휘발.** 단말 로컬 캐시가 없어 Firestore 응답 실패 = 위치 없음.
3. **Sign-In 실패가 잦음** (다중 PC SHA-1 미등록 등 dev-log §B 같은 케이스). 그동안 시청한 모든 영상의 위치가 통째로 누락.

## What Makes This Cool

- **로그인 여부와 무관하게** 같은 단말 내 이어보기는 항상 동작.
- 로그인 시에는 cross-device sync 가 그대로 유지 (변경 없음).
- 네트워크 끊겨도 로컬 fallback 으로 이어보기 보장.
- Room schema 변화는 테이블 1개 추가 — migration 한 번이면 끝.

## Constraints

- 개인 사용 / 배포 없음
- 기존 `WatchPosition` 모델 / `PlayerViewModel` 호출부 (`syncRepo.savePositionDetached`, `syncRepo.getPosition`) 시그니처는 유지. 변경은 `SyncRepo` 내부와 DB 추가에 국한.
- Firestore 실패와 "데이터 없음" 은 구분 — 실패 시에만 local fallback (실수로 신선한 Firestore 데이터를 stale local 로 덮지 않도록).
- 즐겨찾기/채널 데이터(`channels`, `favorites` 테이블)는 마이그레이션에서 반드시 보존.

## Premises

1. **로컬은 무조건 write.** Firestore write 성공 여부와 무관하게 local 도 항상 갱신 (write-through). 사용자 요청 "로그인 안 됐을 때 local 에 write" 를 더 강하게 해석해 로그인 여부와 무관하게 항상 local 에도 박는다 — Firestore 만 있을 때 위치가 휘발되는 케이스를 원천 차단.
2. **읽기는 Firestore-우선, local fallback.** uid 가 있으면 Firestore 1회 시도 → 응답 doc 존재 시 그 값 사용 + local 캐시 갱신. 응답이 없거나 실패하면 local. uid 없으면 바로 local. **재시도 / 짧은 timeout 따로 두지 않음** — Firestore SDK 기본 동작에 맡기고, exception 또는 doc not-found 면 즉시 fallback.
3. **충돌 해결 정책은 그대로**: 더 큰 `positionMs` 우선. local 과 Firestore 각각 자기 규칙대로 비교 (Firestore 측 비교는 `savePosition` 안 그대로). 로컬은 단말 1대 안에서만 쓰이므로 단순 upsert (덮어쓰기) 로 충분.
4. **로그인 transition (null → uid) 시 local → Firestore one-shot upload.** 비로그인 동안 local 에 쌓인 모든 row 를 Firestore 에 upsert (더 큰 `positionMs` 우선 정책 적용). cross-device 전환 시 비로그인 시청 기록이 자연스럽게 합류.
5. **로그아웃 시 local 보존.** signOut → Firestore 호출만 끊기고 local 은 그대로. 같은 단말에서의 이어보기 흐름은 끊기지 않음. 다음 로그인 시 다시 4번 sync.
6. **Local cap = 5000 row LRU.** `updatedAt` 오래된 순으로 prune. write 후 cap 초과 시 trigger.
7. **Room v2 migration 은 ADD TABLE only.** `channels` / `favorites` 는 손대지 않음.

## Approaches Considered

### Approach A: SyncRepo 내부에 local DAO 추가 (선택)

- `SyncRepo` 가 `FirebaseFirestore` + `WatchPositionDao` 둘 다 inject 받아 분기 로직 직접 처리.
- 외부 인터페이스 (`savePositionDetached`, `getPosition`) 시그니처 유지 → 호출부(`PlayerViewModel`) 변경 없음.
- Effort: CC ~20-30분
- Pros: 변경 범위가 SyncRepo 내부로 한정. 호출부 영향 없음.
- Cons: SyncRepo 가 Firestore + Room 두 backend 를 알게 됨 (책임 약간 늘어남). 단, 위치라는 한 개 도메인 안이라 수용 가능.

### Approach B: `PositionRepo` 신규 + `SyncRepo` 폐기

- 새 `data/PositionRepo.kt` 가 두 backend 를 통합. 기존 `SyncRepo` 는 삭제.
- `PlayerViewModel` 도 `positionRepo` 로 inject 변경.
- Effort: CC ~30-40분
- Pros: 이름이 더 정확 (`SyncRepo` → 더 이상 "동기화 전용" 이 아님).
- Cons: 호출부 + Hilt module + ViewModel 변경 다수. 타이핑은 많지만 의미상 변화는 없음.

### Approach C: Repository 한 단계 더 — `LocalPositionDataSource` + `RemotePositionDataSource`

- 정석적인 layered repo 패턴.
- Effort: CC ~1시간
- Pros: 테스트 시 mock 분리 깔끔.
- Cons: 개인용 / 테스트 코드 없음 / 변경 빈도 낮음 — 과한 추상화.

## Recommended Approach

**Approach A — SyncRepo 내부에 local DAO 추가.** 호출부 영향 없음 + 변경 범위 최소. 향후 backend 가 더 늘면 그때 B/C 로 리팩토링.

`SyncRepo` 의 이름은 약간 부정확해지지만 (이제 "Sync" 만 하지 않음), 한 도메인 안의 이름 정확성보다 변경 범위 최소화가 더 가치 있음. 이름만 신경 쓰이면 별도 PR 에서 `PositionRepo` 로 rename.

## Schema

### 신규 Entity

```kotlin
@Entity(tableName = "watch_positions")
data class WatchPositionEntity(
    @PrimaryKey val videoId: String,
    val positionMs: Long,
    val updatedAt: Long,    // epoch millis
)
```

`updatedAt` 은 충돌 비교용은 아니지만 디버깅 + 향후 LRU 정리 시 유용.

### 신규 Dao

```kotlin
@Dao
interface WatchPositionDao {
    @Query("SELECT * FROM watch_positions WHERE videoId = :videoId")
    suspend fun get(videoId: String): WatchPositionEntity?

    @Query("SELECT * FROM watch_positions")
    suspend fun getAll(): List<WatchPositionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: WatchPositionEntity)

    @Query("DELETE FROM watch_positions WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("SELECT COUNT(*) FROM watch_positions")
    suspend fun count(): Int

    // LRU prune — updatedAt 오래된 순으로 (count - keep) 개 삭제
    @Query(
        """
        DELETE FROM watch_positions
        WHERE videoId IN (
            SELECT videoId FROM watch_positions
            ORDER BY updatedAt ASC
            LIMIT :deleteCount
        )
        """
    )
    suspend fun pruneOldest(deleteCount: Int)
}
```

`getAll` 은 로그인 직후 Firestore 일괄 push 용 (Premises 4).
`count` + `pruneOldest` 는 LRU cap (Premises 6) 용.

### Database 변경

```kotlin
@Database(
    entities = [ChannelEntity::class, FavoriteEntity::class, WatchPositionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class SubFeedDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchPositionDao(): WatchPositionDao
}
```

### Migration v1 → v2

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `watch_positions` (
                `videoId` TEXT NOT NULL PRIMARY KEY,
                `positionMs` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}
```

`DatabaseModule.provideDatabase` 에 `.addMigrations(MIGRATION_1_2)` 등록.

## SyncRepo 동작 변경

### 저장 (`savePosition`)

```kotlin
suspend fun savePosition(videoId: String, positionMs: Long) {
    val now = System.currentTimeMillis()

    // 1. 항상 로컬 upsert (write-through, 더 큰 positionMs 정책 적용)
    val localExisting = watchPositionDao.get(videoId)
    if (positionMs > (localExisting?.positionMs ?: 0L)) {
        watchPositionDao.upsert(
            WatchPositionEntity(videoId, positionMs, now)
        )
    }

    // 2. 로그인 상태이면 Firestore 도 갱신 (기존 로직 그대로 — 충돌 정책은 Firestore 안에서)
    val u = uid ?: return
    val remoteExisting = runCatching { fetchFromFirestore(videoId) }.getOrNull()
    if (positionMs <= (remoteExisting?.positionMs ?: 0L)) return
    runCatching {
        firestore.collection("users").document(u)
            .collection("positions").document(videoId)
            .set(mapOf("videoId" to videoId, "positionMs" to positionMs, "updatedAt" to Timestamp.now()))
            .await()
    }.onFailure { Log.e(TAG, "savePosition Firestore write failed", it) }
}
```

### 조회 (`getPosition`)

```kotlin
suspend fun getPosition(videoId: String): WatchPosition? {
    // 1. 로그인 상태면 Firestore 우선 시도
    val u = uid
    if (u != null) {
        val remote = runCatching { fetchFromFirestore(videoId) }
            .onFailure { Log.e(TAG, "getPosition Firestore read failed (fallback to local)", it) }
            .getOrNull()
        if (remote != null) {
            // local cache 동기화 — 다음 비로그인/오프라인 read 대비
            watchPositionDao.upsert(
                WatchPositionEntity(videoId, remote.positionMs, System.currentTimeMillis())
            )
            return remote
        }
        // remote == null: doc 자체가 없거나 read 실패. local fallback.
    }

    // 2. 비로그인 또는 Firestore 실패/없음 → local
    val local = watchPositionDao.get(videoId) ?: return null
    return WatchPosition(
        videoId = local.videoId,
        positionMs = local.positionMs,
        updatedAt = Timestamp(local.updatedAt / 1000, 0),
    )
}
```

`fetchFromFirestore` 는 기존 `getPosition` 의 Firestore 호출 부분을 분리한 private helper.

### LRU prune (write 직후, Premises 6)

```kotlin
private suspend fun pruneIfOver() {
    val n = watchPositionDao.count()
    if (n > LOCAL_MAX_ROWS) {
        watchPositionDao.pruneOldest(n - LOCAL_MAX_ROWS)
    }
}

companion object {
    private const val LOCAL_MAX_ROWS = 5000
}
```

`savePosition` 의 local upsert 직후 호출. count 쿼리 한 번 + 초과 시에만 delete — 일반 트래픽에선 거의 no-op.

### 로그인 직후 Firestore one-shot upload (Premises 4)

```kotlin
// uid 가 null → non-null 로 바뀌는 시점에 1회 호출.
suspend fun syncLocalToFirestoreOnSignIn() {
    val u = uid ?: return
    val locals = watchPositionDao.getAll()
    locals.forEach { local ->
        runCatching {
            val remote = fetchFromFirestore(local.videoId)
            if (local.positionMs > (remote?.positionMs ?: 0L)) {
                firestore.collection("users").document(u)
                    .collection("positions").document(local.videoId)
                    .set(mapOf(
                        "videoId" to local.videoId,
                        "positionMs" to local.positionMs,
                        "updatedAt" to Timestamp.now(),
                    ))
                    .await()
            }
        }.onFailure { Log.e(TAG, "sync upload failed for ${local.videoId}", it) }
    }
}
```

**Trigger** — `SyncRepo.init` 에서 `auth.addAuthStateListener` 등록, `currentUser` 가 null→non-null 전이 시 `detachedScope.launch { syncLocalToFirestoreOnSignIn() }`. 첫 startup 시점 (앱 시작 시 이미 로그인 상태) 은 무시 (이미 동기 상태로 가정). 진짜 sign-in flow 만 trigger.

> 동시성 — 한 번에 다수 row push (sequential await). 100~수천 row 일 가능성 있음. 너무 느리면 `awaitAll()` 병렬화 가능하나 Firestore quota 고려해 일단 직렬.

### 그대로 두는 부분

- `detachedScope` 와 `savePositionDetached` 는 그대로. 화면 dispose 시 마지막 위치 저장 race 보호.
- `WatchPosition` 모델 (`Timestamp` 필드 포함) 도 그대로. 호출부에서 `updatedAt` 사용처가 없으니 local→model 변환에서 적당히 채움.

## 새 / 변경 파일

```
app/src/main/java/com/minseo41/subfeed/
├── data/
│   ├── db/
│   │   ├── WatchPositionEntity.kt        # 신규
│   │   ├── WatchPositionDao.kt           # 신규
│   │   └── SubFeedDatabase.kt            # 수정 — entities + version=2
│   └── SyncRepo.kt                       # 수정 — local DAO inject + 분기 로직 + Firestore helper 분리
├── di/
│   └── DatabaseModule.kt                 # 수정 — MIGRATION_1_2 추가, watchPositionDao provide
docs/
└── offline-position-sync-2026-05-07.md   # 이 문서
```

호출부 변경 없음:
- `PlayerViewModel.loadVideo` 는 `syncRepo.getPosition(videoId)` 그대로 — 내부 동작만 hybrid
- `PlayerViewModel.onPositionChanged` / `savePositionNow` 는 `syncRepo.savePositionDetached` 그대로
- `MainActivity` / `Hilt` 모듈 그래프 영향 없음

## 구현 순서

| Step | 내용 | 의존성 |
|---|---|---|
| 1 | `WatchPositionEntity` + `WatchPositionDao` (get/getAll/upsert/delete/count/pruneOldest) | — |
| 2 | `SubFeedDatabase` version=2 + entities + dao 메서드 | 1 |
| 3 | `MIGRATION_1_2` 작성 + `DatabaseModule.provideDatabase` 에 등록, `provideWatchPositionDao` 추가 | 2 |
| 4 | `SyncRepo` 에 `WatchPositionDao` inject, `savePosition` write-through + `pruneIfOver` | 3 |
| 5 | `SyncRepo.getPosition` Firestore-우선 + local fallback 분기 | 4 |
| 6 | `SyncRepo.syncLocalToFirestoreOnSignIn` + AuthStateListener 등록 (init 블록) | 5 |
| 7 | `BUILD SUCCESSFUL` 확인 + 단말(R3CX705W62D) install | 6 |
| 8 | 실측: (a) 비로그인으로 영상 30초 시청 → 뒤로 → 재진입 → ResumeBanner 노출 (b) 그 상태에서 로그인 → Firestore 콘솔에서 `users/{uid}/positions/{videoId}` 생성 확인 (c) 다른 단말 로그인 시 같은 위치 (d) 로그아웃 후 local 보존 확인 (e) 비행기모드 fallback | 7 |

## Success Criteria

- [ ] **비로그인 동일 단말 이어보기**: Sign-In 안 한 상태에서 영상 30초 시청 → 뒤로 → 같은 영상 재진입 → ResumeBanner 노출 + 마지막 위치부터 재생
- [ ] **로그인 직후 sync upload**: 비로그인으로 N개 영상 시청 → Sign-In → Firestore 콘솔의 `users/{uid}/positions/` 에 N개 doc 생성 + positionMs 일치
- [ ] **로그인 cross-device sync 유지**: 단말 A 에 로그인 + 영상 시청 → 단말 B 에 로그인 + 같은 영상 진입 → A 의 위치 그대로 (regression 없음)
- [ ] **Firestore 다운/오프라인 fallback**: 비행기모드에서 영상 진입 시 local 의 마지막 위치 노출
- [ ] **로그아웃 후 local 보존**: 로그아웃 → 같은 영상 재진입 → ResumeBanner 정상
- [ ] **충돌 정책 유지**: Firestore 위치가 더 크면 그 값 채택 + local 동기화. local 이 더 크면 Firestore 호출 안 함
- [ ] **LRU cap 동작** (선택 검증): 5000개 초과 row 가 쌓일 때 가장 오래된 `updatedAt` 부터 제거되는지 단위 검증
- [ ] **마이그레이션 무손실**: 기존 `channels` / `favorites` 데이터 v1→v2 후에도 그대로 (실측: 즐겨찾기 1개 + 채널 1개 추가 → uninstall 없이 재설치 시 유지 여부 확인)
- [ ] **BUILD SUCCESSFUL** + 단말 install + 위 시나리오 통과

## 결정된 항목 (이전 라운드 Open Questions)

| 항목 | 결정 | 반영 위치 |
|---|---|---|
| 로그아웃 시 local 처리 | **보존** | Premises 5 |
| Firestore read 실패 fallback | **1회 시도 후 즉시 local fallback** (별도 timeout/retry 없음) | Premises 2 |
| local 캐시 정리 | **5000 row LRU cap** (`updatedAt` 오래된 순) | Premises 6, Dao `pruneOldest` |
| 로그인 직후 sync upload | **하기로 결정** — null→uid 전이 시 1회 일괄 push | Premises 4, `syncLocalToFirestoreOnSignIn` |

## Open Questions (이번 라운드)

1. **`updatedAt` 의 의미** — local 의 "마지막 write 시각" vs Firestore Timestamp 의 의미가 약간 다름. 현재 설계: local `updatedAt` = 단말 시계 (System.currentTimeMillis), 충돌 비교에는 사용 안 함 (positionMs 만 사용). LRU prune + 디버깅 전용. 충분합니다.
2. **로그인 직후 sync 의 진행률 노출 여부** — 5000개 영상이 쌓여있다면 Firestore 1회 호출 × 5000 = 분 단위 소요 가능. 사용자에겐 안 보이는 백그라운드 작업이라 default 는 silent. 필요 시 로그 / 토스트 추가. **일단 silent**.
3. **AuthStateListener 등록 위치** — `SyncRepo.init` 블록 vs `SubFeedApp.onCreate`. SyncRepo init 이 더 깔끔하나 Hilt graph 초기화 순서 의존. **SyncRepo init** 으로 가되 ApplicationContext 의존성 없는 형태 유지.
4. **5000 cap 의 적정성** — 영상 1개 = 1 row, 평생 시청 영상이 5000개를 자주 넘기지는 않음. 단말당 SQLite 부담도 미미. cap 자체를 더 크게(10000) 잡거나 아예 안 잡아도 무방. **5000 으로 가되 추후 조정 자유.**

## Status

DRAFT — 최종 확인 부탁드립니다:

- [ ] write 전략 (Premises 1: **항상 local write-through + 로그인 시 Firestore 도 갱신**) OK?
  - 더 단순한 분기 ("비로그인 → local only / 로그인 → Firestore only") 도 가능하나, 본 문서는 위치 휘발 가능성 차단 위해 write-through 채택.
- [ ] read 전략 (Premises 2: **로그인 시 Firestore 1회 시도 → 실패/없음 시 local fallback / 비로그인 시 local only**) OK?
- [ ] 로그인 직후 sync upload (Premises 4) OK?
- [ ] 로그아웃 시 local 보존 (Premises 5) OK?
- [ ] LRU cap 5000 (Premises 6) OK?
- [ ] migration 방식 (v1→v2 ADD TABLE only) OK?
- [ ] Approach A (SyncRepo 내부 변경, 이름 유지) vs B (PositionRepo 분리) — 본 문서는 A.

위 항목 모두 OK 면 APPROVED 처리하고 바로 구현 시작합니다. 변경 요청 있으시면 알려주세요.
