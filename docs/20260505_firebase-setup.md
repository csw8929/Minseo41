# Firebase 설정 가이드 — SubFeed (com.minseo41.subfeed)

> 작성일: 2026-05-05
> 대상: 다른 단말 추가, 새 PC에서 재설정, 또는 다른 사람이 동일한 셋업을 재현해야 하는 경우.
> 현재 적용 상태: **활성**. Firebase 프로젝트 `ongoingview-3e904`, project_number `217716213799` 사용 중.

---

## 0. 왜 Firebase가 필요한가

SubFeed는 폴드 / 플립 / 탭 사이에서 **시청 위치를 이어보기**한다.
- 폰에서 5분 시청 → 태블릿에서 같은 영상 열면 5분부터 자동 재개
- 동기화 매개체: Firebase **Firestore** (`users/{uid}/positions/{videoId}`)
- 사용자 식별: Firebase **Auth + Google Sign-In** (단말마다 같은 Google 계정으로 로그인 → 같은 `uid`)

Firebase가 없으면 `auth.currentUser=null`이 되어 `SyncRepo.savePosition`이 silent skip한다.
즉, 코드는 다 깔려있어도 Firebase 연결 없으면 cross-device 이어보기 자체가 동작하지 않는다.

---

## 1. Firebase 프로젝트 생성 (한 번만)

1. https://console.firebase.google.com 접속, Google 계정 로그인
2. **프로젝트 추가** 클릭
3. 프로젝트 이름은 자유롭게 (예: `SubFeed`, `OngoingView`). 영구 ID는 자동 생성됨 (예: `ongoingview-3e904`)
4. Google Analytics는 **사용 중지** 권장 (개인 사용에는 불필요, Auth/Firestore에 영향 없음)
5. 프로젝트 생성 → 잠시 대기

> 이미 만들어진 프로젝트가 있으면 이 단계 skip.
> 현재 SubFeed가 사용하는 프로젝트는 콘솔에서 `ongoingview-3e904` ID로 식별된다.

---

## 2. Android 앱 등록

좌측 상단 톱니 → **프로젝트 설정** → **내 앱** → **앱 추가** → Android 아이콘.

또는 직링크: `https://console.firebase.google.com/project/<project-id>/settings/general/android:com.minseo41.subfeed`

| 필드 | 값 |
|---|---|
| Android 패키지 이름 | `com.minseo41.subfeed` (대소문자 정확히) |
| 앱 닉네임 | 임의 (`SubFeed`) |
| 디버그 서명 인증서 SHA-1 | 아래 3번에서 추출 |

---

## 3. SHA-1 인증서 지문 추출

`debug.keystore`는 Android Studio가 첫 빌드 시 자동 생성한다. 위치는 `%USERPROFILE%\.android\debug.keystore` (Windows) 또는 `~/.android/debug.keystore` (macOS/Linux).

### Windows PowerShell

```powershell
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -keypass android -alias androiddebugkey
```

`keytool`이 PATH에 없으면:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -keypass android
```

또는 Android Studio JBR을 직접 가리킨다:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -keypass android
```

### macOS / Linux

```bash
keytool -list -v -keystore ~/.android/debug.keystore -storepass android -keypass android -alias androiddebugkey
```

### SHA-1 복사

출력에서 다음 라인을 찾아 hex 값(콜론 포함, 대소문자 무관)을 복사:

```
SHA1: A1:B2:C3:D4:E5:F6:07:08:09:10:11:12:13:14:15:16:17:18:19:20
```

콘솔의 "SHA 인증서 지문" 섹션 → "지문 추가" → 붙여넣기 → 저장.

### 여러 PC / 여러 단말

- **PC가 여러 대**일 때: 각 PC의 `debug.keystore`는 다르다. 각 PC의 SHA-1을 모두 등록해야 그 PC에서 빌드한 APK로도 sign-in이 동작.
- **단말 자체는 SHA-1 등록과 무관하다** (서명은 APK에 박혀있음). 단, APK가 다른 keystore로 서명되면 단말에 이미 설치된 앱과 서명 불일치 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → uninstall 후 재설치 필요.
- **macOS에서 만든 keystore와 Windows에서 만든 keystore를 통일하려면** macOS의 `~/.android/debug.keystore`를 Windows의 `%USERPROFILE%\.android\debug.keystore`로 복사. 그러면 같은 서명, 같은 SHA-1, uninstall 불필요.

---

## 4. `google-services.json` 다운로드 → 프로젝트에 적용

3번까지 끝낸 후 콘솔 앱 설정 페이지에서 **`google-services.json` 다운로드** 버튼.

다운로드한 파일을 다음 경로에 덮어쓴다 (절대 commit하지 않는다 — `.gitignore`에 등록되어 있음):

```
D:\workspace\Minseo41\app\google-services.json
```

### 파일 내용 검증

다운로드한 파일을 열어 다음을 확인한다:

```json
{
  "project_info": {
    "project_number": "217716213799",            ← 12자리 숫자
    "project_id": "ongoingview-3e904",
    ...
  },
  "client": [
    {
      "client_info": {
        "android_client_info": {
          "package_name": "com.minseo41.subfeed"  ← 정확히 이 패키지명
        }
      },
      "oauth_client": [                           ← 비어있지 않아야 함
        {
          "client_id": "...-....apps.googleusercontent.com",
          "client_type": 1                        ← Android client
        },
        {
          "client_id": "...-....apps.googleusercontent.com",
          "client_type": 3                        ← Web client (idToken용)
        }
      ],
      "api_key": [
        { "current_key": "AIzaSy..." }
      ]
    }
  ]
}
```

`oauth_client` 배열이 **비어있다**(`"oauth_client": []`)면:
- SHA-1 등록을 안 했거나
- 등록 후 `google-services.json`을 다시 다운받지 않은 상태이다

→ 3번 다시 → `google-services.json` 다시 다운 → 덮어쓰기.

`client_type=3` (Web client)이 없으면 `default_web_client_id`가 strings.xml에 자동 생성되지 않아 Sign-In은 항상 `ApiException statusCode=10` (DEVELOPER_ERROR)으로 실패한다.

---

## 5. Authentication > Google 활성화

좌측 사이드바 → **빌드 / Build** 카테고리 펼침 → **Authentication** 클릭.

또는 직링크: `https://console.firebase.google.com/project/<project-id>/authentication/providers`

1. **시작하기** (Get started) 버튼
2. 위쪽 탭 메뉴 **Sign-in method** (로그인 방법)
3. "기본 제공 공급업체" 목록에서 **Google** 클릭
4. 우측 상단 토글 **사용 설정 / Enable** ON
5. **프로젝트 지원 이메일** drop-down — 본인 Gmail 선택 (필수)
6. **저장**

이 단계를 빼먹으면 Sign-In intent는 launch되지만 결과는 `RESULT_CANCELED` + `googleSignInStatus` extras (계정 정보 없음)로 돌아온다.

---

## 6. Firestore 데이터베이스 생성

좌측 사이드바 → **빌드 / Build** → **Firestore Database** 클릭.

또는 직링크: `https://console.firebase.google.com/project/<project-id>/firestore`

1. **데이터베이스 만들기** (Create database)
2. **위치** 선택:
   - `asia-northeast3 (서울)` 추천 (한국 단말 기준 latency 최저)
   - **위치는 한 번 정하면 변경 불가**
3. **보안 규칙 모드**:
   - "**테스트 모드에서 시작**" 선택 (30일짜리 임시)
4. **사용 설정** → 잠시 대기

### 보안 규칙 — 30일 안에 교체

생성된 후 상단 **규칙(Rules)** 탭 → 내용을 다음으로 통째 교체 → **게시(Publish)**:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{userId}/{document=**} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```

이 규칙의 의미:
- 로그인된 사용자만 read/write 가능 (`request.auth != null`)
- 본인 데이터(`/users/{본인 uid}/...`)만 접근 가능
- SubFeed가 사용하는 정확한 경로 `/users/{uid}/positions/{videoId}`를 정확히 커버

테스트 모드 만료 후에는 모든 read/write가 차단되므로, 30일 안에 위 규칙으로 반드시 교체할 것.

---

## 7. 검증

```bash
# 빌드 + 설치
JAVA_HOME=... ./gradlew assembleDebug
adb -s R3CT70FY0ZP install -r app/build/outputs/apk/debug/Minseo41.apk

# logcat 띄우고
adb -s R3CT70FY0ZP logcat -c
adb -s R3CT70FY0ZP logcat -s SubFeedAuth:V SubFeedSettingsVM:V SubFeedSettingsScreen:V SubFeedPlayerVM:V SubFeedSync:V AndroidRuntime:E '*:S'
```

기대 흐름 (logcat 출력):

```
SubFeedSettingsVM: signInIntent requested
SubFeedAuth: signInIntent requested
SubFeedAuth: webClientId resolved: empty=false, prefix=217716213799   ← project_number
SubFeedAuth: GoogleSignInClient built (requestIdToken=true)
SubFeedSettingsScreen: signIn launcher result: code=-1, hasData=true   ← code=-1 = RESULT_OK
SubFeedSettingsVM: handleSignInResult: data=..., extras=[googleSignInAccount]
SubFeedAuth: Google account: email=..., hasIdToken=true, ...
SubFeedAuth: Firebase signIn ok: uid=..., email=...
```

영상 재생 → 30초 이상 시청 → 뒤로가기 → 같은 영상 다시 클릭:
```
SubFeedSync: getPosition start: uid=..., videoId=...
SubFeedSync: getPosition ok: videoId=..., positionMs=...    ← 0이 아니면 성공
SubFeedPlayerVM: loadVideo: videoId=..., savedPosition=...
```

ResumeBanner가 영상 시작 5초 동안 화면 상단에 노출되어야 한다.

---

## 8. 알려진 실패 모드 → 해결법

| logcat 신호 | 원인 | 해결 |
|---|---|---|
| `webClientId resolved: empty=true` | `google-services.json` 없거나 `oauth_client`가 비어있음 | 4번 다시 — 콘솔에서 SHA-1 등록 확인 후 json 재다운 |
| `webClientId resolved: empty=false, prefix=000000000000` | placeholder json 사용 중 (실제 Firebase 미연결) | 4번 — 진짜 콘솔에서 받은 json으로 교체 |
| `ApiException statusCode=10` (DEVELOPER_ERROR) | SHA-1 미등록 / 잘못된 패키지명 / Google 로그인 미활성 | 3번 + 5번 다시 |
| `ApiException statusCode=12501` (SIGN_IN_CANCELLED) | 사용자가 계정 선택 화면에서 취소 | 정상, 다시 시도 |
| `RuntimeException: Internal error in Cloud Firestore. NoClassDefFoundError: ...protobuf...` | protobuf 클래스 충돌 (예전 NewPipe 의존성 흔적) | `app/build.gradle.kts`에서 NewPipe 의존성 제거 + protolite/javalite 양쪽 exclude 충돌 해결. `docs/development-log.md`의 "protobuf 의존성 정리" 섹션 참조 |
| `getPosition: doc not found` | 해당 영상에 저장된 위치 없음 (처음 시청) | 정상, 30초 이상 시청 후 다시 |
| `JobCancellationException: ... was cancelled` | ViewModel cleared로 await가 끊김 | `SyncRepo`가 detached scope로 commit하도록 변경되어 있어야 함. `development-log.md`의 "savePosition detached scope" 섹션 참조 |

---

## 9. 보안

- **`google-services.json`은 git에 commit하지 않는다** — `.gitignore`에 `app/google-services.json` 등록되어 있음.
  - 그 안의 `api_key`는 Android client용으로 SHA-1 + 패키지명 제약이 걸려있어 외부 노출 자체로는 큰 위험 아님. 하지만 관행상 commit하지 않는다.
- **Firestore Rules가 진짜 보안 경계** — `google-services.json` 노출돼도 Rules가 본인 데이터만 허용하면 다른 user가 본인 시청 위치를 못 보낸다.
- **테스트 모드 Rules는 30일 후 만료** — 만료 후 모든 read/write 차단. 6번의 production rules로 반드시 교체.

---

## 10. 비용

Firebase Free Tier (Spark plan):
- Firestore: 1 GB 저장, 일 50K reads / 20K writes / 20K deletes
- Auth: 무료 (Google Sign-In은 quota 없음)

SubFeed 사용 패턴 추정:
- 영상 1개당 read 1회 (`getPosition`) + write 매 30초 + 화면 이탈 시 1회
- 일 100개 영상 시청 가정 → 100 reads + 1000 writes 정도 → Free tier 한참 안쪽

→ 개인 사용에는 사실상 비용 0.

---

## 11. 다른 단말 추가 시 체크리스트

새 폰/태블릿에 SubFeed 설치하고 cross-device 이어보기 활성화:

- [ ] 단말이 빌드한 PC와 같은 keystore의 SHA-1로 서명되어 있는지 (다른 PC면 그 PC의 SHA-1을 콘솔에 추가)
- [ ] APK 설치 (서명 충돌 있으면 uninstall 후 재설치)
- [ ] 앱 → Settings → "Google 계정 연동" 클릭 → 다른 단말과 **같은** Google 계정 선택
- [ ] logcat에 `Firebase signIn ok: uid=...` 확인 (uid가 다른 단말과 같아야 cross-device 동작)
- [ ] 영상 하나 재생 → 다른 단말에서 같은 영상 열어 ResumeBanner 노출 확인

---

## 참고

- 현재 SubFeed가 사용하는 Firebase 콘솔: https://console.firebase.google.com/project/ongoingview-3e904
- 공식 Firebase Android 문서: https://firebase.google.com/docs/android/setup
- Google Sign-In Android: https://developers.google.com/identity/sign-in/android/start
- Firestore Security Rules: https://firebase.google.com/docs/firestore/security/get-started
