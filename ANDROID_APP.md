# Android 앱

`android-app`은 Android 시스템의 한국어 TTS 엔진을 사용해 선택한 성경 텍스트를 장별 WAV 파일로 만들고 재생하는 앱입니다. 앱은 인터넷 권한을 요청하지 않아 선택한 본문을 자체 서버로 전송하지 않습니다. 오프라인 사용 여부는 선택된 Android TTS 엔진과 설치된 한국어 음성 데이터에 따라 다르므로, 비행기 모드에서도 쓸 수 있는 음성 데이터를 휴대폰 설정에서 확인해 주세요.

## 주요 기능

- Android 파일 선택기에서 UTF-8, UTF-16 또는 CP949 성경 텍스트 선택
- 장절 범위(예: `대상16:12-13`)를 본문에서 제외하고 장별로 음성 합성
- 장별 WAV 생성 후 `음악/BibleReader/<실행 시각>`에 저장
- 생성된 장을 순서대로 재생, 일시정지, 중지

현재 앱은 MP3 인코딩이 아니라 휴대폰 TTS 엔진의 WAV 합성 결과를 사용합니다. 음색과 지원 여부는 Android 기기에 설치된 음성 엔진에 따라 다릅니다.

## 빌드

Android Studio에서 `android-app` 폴더를 열고 Gradle 동기화 후 앱을 Android 10(API 29) 이상 기기에서 실행합니다. Android SDK 35와 JDK 17이 필요합니다. Windows에서는 SDK 설치 후 해당 폴더에서 다음 명령으로 APK를 빌드할 수 있습니다.

```powershell
.\gradlew.bat :app:assembleDebug
```

생성 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 있습니다. 파서 단위 테스트는 `.\gradlew.bat :app:testDebugUnitTest`로 실행합니다. 이 저장소의 현재 실행 환경에는 Android SDK가 없어 APK 빌드와 Android 단위 테스트는 아직 실행하지 못했습니다.
