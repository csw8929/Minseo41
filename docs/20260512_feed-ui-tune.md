# Feed/Player UI 튜닝 (2026-05-12)

## 배경
구독 목록의 정보 밀도와 비전체화면 플레이어의 사용성을 다듬는 작은 UX 개선 묶음.

## 변경 사항

### FeedScreen
- **헤더 컴팩트화**: `TopAppBar` (기본 64dp) → 상태바 inset 적용한 40dp 커스텀 Row로 교체. 아이콘 버튼도 48dp → 40dp 로 축소. `TabRow` 는 유지.
- **영상 등록 일시 표시**: 썸네일 Box 를 Column 으로 감싸 아래에 `MM/dd HH:mm` 포맷의 업로드 시각을 표시. `video.uploadedAt > 0L` 일 때만 노출.
- **색상 강조**:
  - 업로드 일시: `UnreadBlue` (#4FC3F7)
  - 채널명: 새로 추가한 `ChannelGreen` (#81C784, Material light green)

### PlayerScreen
- **비전체화면 좌상단 제목 노출**: 컨트롤이 숨겨졌을 때도 영상 제목을 좌상단에 표시. `isFullscreen == false && !isInPipMode && !controlsVisible && videoTitle.isNotEmpty()` 조건으로 검은 반투명 배경(`alpha 0.5`) + `RoundedCornerShape(4.dp)` + `widthIn(max = 280.dp)` + 1줄 ellipsis.
- **PlayerTopBar 제목 실값화**: 기존 placeholder `"재생 중"` → `uiState.videoTitle.ifEmpty { "재생 중" }`. 컨트롤이 보일 때 실제 제목 노출.

## 영향 범위
- UI 전용 변경, 데이터 레이어/싱크/스트림 추출 로직은 무관.
- `VideoItem.uploadedAt` 은 RSS 파싱 시점부터 epoch millis 로 채워지고 있어 기존 데이터로도 즉시 동작.

## 검증
- 폴드(R3CT70FY0ZP) 에서 빌드 + 설치 후 구독영상 리스트, 영상 재생(컨트롤 토글) 동작 확인.
