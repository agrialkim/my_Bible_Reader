import sys
from pathlib import Path
from tkinter import Tk, messagebox

from textToSpeech import launch_control_window


def bible_file_path() -> Path:
    application_dir = (
        Path(sys.executable).resolve().parent
        if getattr(sys, "frozen", False)
        else Path(__file__).resolve().parent
    )
    return application_dir / "개역개정.txt"


def main() -> int:
    bible_file = bible_file_path()

    try:
        if not bible_file.is_file():
            raise FileNotFoundError(
                f"실행 파일과 같은 폴더에서 개역개정.txt를 찾을 수 없습니다:\n{bible_file}"
            )
        launch_control_window(bible_file)
    except Exception as error:
        root = Tk()
        root.withdraw()
        messagebox.showerror("성경 음성 재생", str(error))
        root.destroy()
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())