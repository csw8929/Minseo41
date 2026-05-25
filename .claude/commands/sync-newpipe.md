---
description: NewPipe Extractor를 최신 버전으로 동기화 (재생 깨짐 대응)
---

YouTube 재생이 깨졌을 때 NewPipe Extractor를 최신 버전으로 흡수한다.

# 절차

## 0. 사전 조건 확인

- `docs/newpipe/maintenance.md`가 존재해야 한다. 없으면 사용자에게 안내하고 중단.
- `gh auth status` 확인. 인증 실패 시 사용자에게 `gh auth login` 안내하고 중단 (무한 retry 금지).
- `git status` 확인. working tree가 dirty면 사용자에게 확인 후 진행 여부 결정.

## 1. 현재 / 최신 버전 비교

현재 버전:
```bash
grep -E '^newpipe\s*=' gradle/libs.versions.toml
```

최신 릴리즈:
```bash
gh api repos/TeamNewPipe/NewPipeExtractor/releases/latest --jq '{tag: .tag_name, body: .body, published: .published_at}'
```

## 2. 판단

| 상태 | 행동 |
|---|---|
| 최신 = 현재 | "NewPipe도 아직 fix 미공개. 다른 원인 의심" 안내 후 종료. `docs/newpipe/maintenance.md`의 "위험 신호" 섹션 참조 권고. |
| 최신 > 현재, 노트가 스트림/n-param/signature/PoToken/InnerTube/DASH 관련 | 다음 단계 진행 |
| 최신 > 현재, 노트가 RSS/UI/타 서비스 만 | 사용자에게 "이번 변경은 SubFeed 재생 문제와 무관해 보임. 그래도 업데이트할지" 확인 |

## 3. 위험 신호 사전 점검

릴리즈 노트에 다음 키워드가 있으면 사용자에게 명시적 경고:

- `PoTokenProvider` interface 변경 → `SubFeedPoTokenProvider`/`PoTokenWebView` 영향
- `Downloader` interface 변경 → `OkHttpDownloader`/`SubFeedDownloader` 영향
- protobuf 버전 변경 → Firebase BOM과 충돌 가능
- major 버전 점프 (`vX.Y.* → vX.(Y+1).*`) → 전체 검토 권장

## 4. 업데이트 적용

브랜치 생성:
```bash
git checkout -b fix/newpipe-{최신버전}-{YYYY-MM}
```

`gradle/libs.versions.toml` 의 `newpipe = "..."` 한 줄 수정. 다른 변경은 하지 않는다.

빌드:
```bash
./gradlew assembleDebug
```

- 빌드 실패 = NewPipe API breaking change. 컴파일 에러 위치와 함께 `docs/newpipe/maintenance.md`의 "API Breaking Change 대응" 섹션 사용자에게 안내. 자동 진행 중단.
- 빌드 성공 = 다음 단계

## 5. 설치 + 확인 리스트 출력

연결된 단말 확인:
```bash
adb devices
```

여러 대 연결되어 있으면 사용자에게 어느 단말에 설치할지 묻는다.

설치:
```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

확인 리스트 출력 (CLAUDE.md "빌드 후 워크플로우" 형식 따름):

```
확인 리스트
1. 한 영상 재생 시작 — 첫 chunk OK?
2. seek 동작 — 중간 위치로 점프 시 chunk 403 안 나는지
3. 화질 전환 — 144p ↔ 1080p 정상?
4. (가능하면) 라이브 방송 정상 재생?
```

**스크린샷 캡처 금지** — 사용자가 직접 검증한다.

## 6. 결과 반영

### 사용자가 OK 회신 시

`docs/newpipe/maintenance.md`의 "깨짐 사례 기록" 섹션에 신규 항목 추가 제안:

```markdown
### YYYY-MM — {증상 한 줄}

- **증상**: (사용자에게 받음)
- **원인**: (릴리즈 노트에서 추출)
- **NewPipe 해결**: {최신버전} — {요약}
- **SubFeed 흡수**: PR #{번호} 예정 / 또는 직접 머지
```

이후 `/ship` 사용 권장 안내.

### 사용자가 실패 회신 시

원인 추적 옵션 제시:
1. NewPipe 더 새 버전 기다리기 (롤백): `git checkout main && git branch -D fix/newpipe-...`
2. NewPipe Extractor 빌드 자체 문제 확인 (logcat 분석)
3. SubFeed 측 코드 변경 필요 (PoTokenWebView, NewPipeVideoExtractor 등) 의심

## 무한 retry 금지 규칙

- `gh` 호출 실패 → 1회 알림 후 중단
- 빌드 실패 → 자동 fix 시도 금지, 사용자에게 보고
- 단말 미연결 → 1회 안내 후 중단

## 참조

- 지식 베이스: `docs/newpipe/maintenance.md` (이 명령의 source of truth)
- 도입 과정 배경: `docs/newpipe/20260524_newpipe_solution_analysis.md`, `docs/newpipe/20260524_potoken_implementation.md`
