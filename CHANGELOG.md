# Changelog

All notable changes to SubFeed are documented in this file.

## [1.2.1.0] - 2026-06-10

### Fixed
- NewPipe Extractor v0.26.2 → v0.26.3 업데이트 — YouTube SABR 강제 적용 우회 (visionOS 클라이언트, PoToken 불요). 360p 고정·chunk 403·라이브 파싱 실패가 해결되어 고화질 인앱 재생 경로 복구. 폴드/플립 실기기 재생 확인 완료.

## [1.2.0.0] - 2026-05-29

### Added
- 재생 실패 화면에 "브라우저로 열기" 버튼 추가 — 인앱 재생이 막힌 영상을 YouTube 앱/브라우저로 넘겨 볼 수 있음. NewPipe·Tubular 등 extractor 기반 앱들이 공통으로 쓰는 폴백 전략과 동일.

## [1.1.2.0] - 2026-05-25

### Changed
- NewPipe Extractor 의존 코드를 `data/newpipe/` 패키지로 격리 — 라이브러리 업데이트 시 영향 범위를 한 폴더로 좁힘. 동작 변경 없음.

### Added
- `docs/newpipe/` 서브폴더에 NewPipe 관련 문서 통합 (도입 분석, PoToken 구현, 활용 구조 개요, 유지보수 가이드)
- `docs/newpipe/maintenance.md` — 살아있는 유지보수 가이드 (업데이트 절차, 위험 신호, 깨짐 사례 누적)
- `data/newpipe/README.md` — 우리가 의존하는 NewPipe API contract (v0.26.2 기준)
- `.claude/commands/sync-newpipe.md` — `/sync-newpipe` project-scope 슬래시 명령 (재생 깨질 때 NewPipe 최신화 보조)

## [1.1.1.0] - 2026-05-25

### Fixed
- RSS 피드 갱신 시 영상 제목·썸네일이 변경돼도 DB에 반영되지 않던 문제 — insertIgnoreAll 후 기존 행의 title/thumbnailUrl을 updateMeta로 갱신

## [1.1.0.0] - 2026-05-24

### Added
- YouTube PoToken (BotGuard) 인증 도입 — 2026년 YouTube 외부 클라이언트 차단 정책 대응. WebView에서 BgUtils JS 실행해 토큰 발급
- NewPipeExtractor v0.26.2 라이브러리 통합 — n-param/signature deobfuscation 및 DASH manifest 처리 자동화

### Fixed
- seek 시 chunk 403으로 재생 불가 → NewPipe의 n-param JavaScript deobfuscation 으로 해소
- 짧은 재생 후 "외부 재생 차단" 에러 → PoToken + n-param 처리로 chunk URL 정상화
- 라이브 방송 예약 / 연령 제한 / 비공개 영상 등 상태별 친화적 에러 메시지 표시

### Changed
- 화질 개선 — videoOnlyStreams + audioStreams 결합 inline DASH MPD 빌더로 1080p+ 재생 지원 (기존 muxed 360p 한계 해소)
- 한국어 더빙 등 멀티 언어 오디오 트랙 선택 정상화 (audioLocale 기반 분리)

## [1.0.1.0] - 2026-05-24

### Fixed
- 403 에러 재시도 시 재생 위치가 초기 로드 시점으로 리셋되던 버그 — 이제 에러 발생 직전 위치에서 재개
- DASH 스트림에서 화질 선택 메뉴가 비활성화되던 문제 — HLS/DASH 모두 화질 선택 가능
- OkHttpDownloader response body 미닫힘으로 인한 커넥션 풀 고갈 위험 해소
- 챕터 파싱 시 타임스탬프 단조 증가 체크 개선 — 중복 타임스탬프는 skip, 감소 시에만 전체 폐기
- 챕터 관련 디버그 로그 제거 (릴리즈 빌드에 노출되던 verbose 로그)
