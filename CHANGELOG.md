# Changelog

All notable changes to SubFeed are documented in this file.

## [1.0.1.0] - 2026-05-24

### Fixed
- 403 에러 재시도 시 재생 위치가 초기 로드 시점으로 리셋되던 버그 — 이제 에러 발생 직전 위치에서 재개
- DASH 스트림에서 화질 선택 메뉴가 비활성화되던 문제 — HLS/DASH 모두 화질 선택 가능
- OkHttpDownloader response body 미닫힘으로 인한 커넥션 풀 고갈 위험 해소
- 챕터 파싱 시 타임스탬프 단조 증가 체크 개선 — 중복 타임스탬프는 skip, 감소 시에만 전체 폐기
- 챕터 관련 디버그 로그 제거 (릴리즈 빌드에 노출되던 verbose 로그)
