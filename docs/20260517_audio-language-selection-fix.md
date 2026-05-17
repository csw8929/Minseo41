# 오디오 언어 선택 버그 수정

## 문제

멀티 언어 오디오 트랙이 있는 YouTube 영상(예: 영어 원본 + 한국어 더빙 anime, 영화)에서 한국어가 아닌 다른 언어(주로 영어 원본)가 재생되는 현상.

## 근본 원인

`NewPipeVideoExtractor.buildAdaptiveDashManifest`의 두 가지 결함:

1. **오디오 트랙을 bitrate 기준으로만 선택**: YouTube `adaptiveFormats` 응답에는 `audioTrack.id` 필드(`"1.ko"`, `"2.en"` 등)로 언어 정보가 제공되는데, 이를 무시하고 bitrate가 가장 높은 트랙 하나만 선택. 영어 원본이 한국어 더빙보다 bitrate가 높은 경우가 많아 영어가 선택됨.

2. **생성된 DASH MPD에 `lang` 속성 없음**: `<AdaptationSet>` 에 `lang` 속성이 없어서 ExoPlayer의 `setPreferredAudioLanguages("ko")` 가 언어 매칭을 할 수 없었음. 트랙이 하나뿐이라 선택지 자체가 없는 상태.

이 경로는 iOS 클라이언트가 `hlsManifestUrl`/`dashManifestUrl` 없이 `adaptiveFormats`만 반환할 때 동작하는 4순위 fallback이다.

> HLS 경로(iOS 1순위)나 DASH manifest URL 경로(ANDROID_VR/ANDROID)는 YouTube가 제공한 manifest에 언어 태그가 포함되므로 영향 없음.

## 수정 내용

`buildAdaptiveDashManifest` 리팩터링 (`NewPipeVideoExtractor.kt`):

- `audioTrack.id`에서 언어 코드 파싱 (`.ko`, `.en` 등)
- 언어별로 그룹화하여 각 언어의 최고 bitrate 트랙 선택
- DASH MPD에 언어별 `<AdaptationSet lang="ko">` 생성 → ExoPlayer의 `preferredAudioLanguages("ko")` 가 올바른 트랙 선택
- `audioTrack` 정보가 없는 단일 오디오 트랙의 경우 기존 동작 유지 (lang 속성 없는 단일 AdaptationSet)

## 기존 코드 vs 수정 후

### 기존
```xml
<!-- 언어 태그 없는 단일 AdaptationSet — 항상 같은 (높은 bitrate) 트랙 선택 -->
<AdaptationSet id="2" contentType="audio">
  <Representation id="a0" ...> <!-- 영어 192kbps (highest) -->
```

### 수정 후
```xml
<!-- 언어별 분리, ExoPlayer가 "ko" 선택 -->
<AdaptationSet id="2" contentType="audio" lang="ko">
  <Representation id="a0" ...> <!-- 한국어 128kbps -->
<AdaptationSet id="3" contentType="audio" lang="en">
  <Representation id="a1" ...> <!-- 영어 192kbps -->
```

## 영향 범위

`adaptiveFormats` fallback만 해당. 대부분의 영상은 iOS HLS 경로를 사용하므로 영향 없음. 멀티 언어 더빙 콘텐츠(YouTube에서 공식 제공하는 anime, 영화, 글로벌 채널)에서 효과 있음.
