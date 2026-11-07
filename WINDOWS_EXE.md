# Windows 실행 파일

## 빌드

Windows에서 `uv`와 프로젝트의 `개역개정.txt`를 준비한 뒤 PowerShell에서 실행합니다.

```powershell
.\build_windows.ps1
```

빌드 후 `Bible Reader Ver 2` 폴더에 배포 파일이 모입니다.

- `BibleReader V2.exe`
- `개역개정.txt`
- `mpv.exe` 및 설치된 경우 `d3dcompiler_43.dll`

실행 파일은 텍스트를 내장하지 않고 같은 폴더의 `개역개정.txt`를 읽습니다. 이 폴더를 통째로 옮기거나 공유해야 하며, exe만 따로 옮기면 본문을 찾지 못합니다. 빌드 컴퓨터에는 `C:\Program Files\MPV Player\mpv.exe` 경로에 MPV가 설치되어 있어야 합니다.

## 실행 조건

- 한국어 음성 생성에는 인터넷 연결이 필요합니다.
- 오디오 재생에는 함께 제공되는 MPV 실행 파일을 사용합니다.
- 실행 후 제어 창에서 이어서 재생, 책과 장 지정 재생, 일시정지, 종료를 선택할 수 있습니다.
- 재생 중인 책과 장은 일반 실행 시 성경 텍스트 옆의 `.playback.json` 파일에, 실행 파일에서는 `%LOCALAPPDATA%\BibleReaderTTS\playback.json`에 저장됩니다. 다시 실행하면 마지막 저장 위치부터 이어서 읽으며, 중간에 종료한 경우 해당 장 처음부터 다시 재생합니다.
- 지정 위치에는 성경 책 전체 이름(예: `창세기`) 또는 본문에 사용된 약칭(예: `창`)과 장 번호를 입력합니다.