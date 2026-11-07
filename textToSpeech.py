import argparse
import asyncio
import codecs
import ctypes
import json
import os
import queue
import re
import shutil
import sys
import tempfile
import threading
import uuid
from ctypes import wintypes
from contextlib import nullcontext
from collections.abc import AsyncIterator, Iterable, Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Callable, TextIO


KOREAN_VOICE = "ko-KR-SunHiNeural"
MPV_FALLBACK_PATH = Path(r"C:\Program Files\MPV Player\mpv.exe")
BOOK_NAMES = {
    "창": "창세기", "출": "출애굽기", "레": "레위기", "민": "민수기", "신": "신명기",
    "수": "여호수아", "삿": "사사기", "룻": "룻기", "삼상": "사무엘상", "삼하": "사무엘하",
    "왕상": "열왕기상", "왕하": "열왕기하", "대상": "역대상", "대하": "역대하",
    "스": "에스라", "느": "느헤미야", "에": "에스더", "욥": "욥기", "시": "시편",
    "잠": "잠언", "전": "전도서", "아": "아가", "사": "이사야", "렘": "예레미야",
    "애": "예레미야애가", "겔": "에스겔", "단": "다니엘", "호": "호세아", "욜": "요엘",
    "암": "아모스", "옵": "오바댜", "욘": "요나", "미": "미가", "나": "나훔",
    "합": "하박국", "습": "스바냐", "학": "학개", "슥": "스가랴", "말": "말라기",
    "마": "마태복음", "막": "마가복음", "눅": "누가복음", "요": "요한복음",
    "행": "사도행전", "롬": "로마서", "고전": "고린도전서", "고후": "고린도후서",
    "갈": "갈라디아서", "엡": "에베소서", "빌": "빌립보서", "골": "골로새서",
    "살전": "데살로니가전서", "살후": "데살로니가후서", "딤전": "디모데전서",
    "딤후": "디모데후서", "딛": "디도서", "몬": "빌레몬서", "히": "히브리서",
    "약": "야고보서", "벧전": "베드로전서", "벧후": "베드로후서", "요일": "요한일서",
    "요이": "요한이서", "요삼": "요한삼서", "유": "유다서", "계": "요한계시록",
}
VERSE_PREFIX = re.compile(
    r"^\s*(?P<book>[가-힣]{1,8})\s*(?P<chapter>\d{1,3})\s*:\s*\d{1,3}\s*(?:<(?P<title>[^>\r\n]*)>\s*)?"
)
CHAPTER_AUDIO_FILENAME = re.compile(r"\d{4}_.+_\d{3}\.mp3\Z")
TEST_AUDIO_MAX_SECONDS = 180
EDGE_TTS_BITRATE = 48_000
TEST_AUDIO_MAX_BYTES = TEST_AUDIO_MAX_SECONDS * EDGE_TTS_BITRATE // 8
PARENTHETICAL_REFERENCE = re.compile(
    r"\((?P<book>[가-힣]{1,8})\s*(?P<chapter>\d{1,3})\s*:\s*"
    r"(?P<start>\d{1,3})(?:\s*-\s*(?P<end>\d{1,3}))?\)"
)


@dataclass
class AudioBudget:
    remaining_bytes: int
    exhausted: bool = False


def open_text_file(file_path: str | Path) -> TextIO:
    """Open UTF-8 or Korean legacy-encoded text without buffering the whole file."""
    path = Path(file_path)
    with path.open("rb") as binary_file:
        prefix = binary_file.read(4)

    if prefix.startswith((codecs.BOM_UTF16_LE, codecs.BOM_UTF16_BE)):
        return path.open(encoding="utf-16")
    if prefix.startswith(codecs.BOM_UTF8):
        return path.open(encoding="utf-8-sig")

    try:
        decoder = codecs.getincrementaldecoder("utf-8")()
        with path.open("rb") as binary_file:
            for block in iter(lambda: binary_file.read(64 * 1024), b""):
                decoder.decode(block)
            decoder.decode(b"", final=True)
        return path.open(encoding="utf-8")
    except UnicodeDecodeError:
        pass

    for encoding in ("cp949", "euc-kr"):
        try:
            with path.open(encoding=encoding) as text_file:
                for _ in text_file:
                    pass
            return path.open(encoding=encoding)
        except UnicodeDecodeError:
            continue

    raise UnicodeError(f"지원하는 텍스트 인코딩이 아닙니다: {path}")


def read_text_file(file_path: str | Path) -> str:
    """Read UTF-8 or Korean legacy-encoded text."""
    with open_text_file(file_path) as text_file:
        return text_file.read()


def remove_verse_references(text: str) -> str:
    """Remove leading verse references and optional inline section headings."""
    cleaned_lines = [VERSE_PREFIX.sub("", line).strip() for line in text.splitlines()]
    return "\n".join(cleaned_lines).strip()


def _number_to_korean_sino(number: int) -> str:
    digits = ("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    units = ("", "십", "백")
    result = []

    for position, digit in enumerate(reversed(str(number))):
        value = int(digit)
        if value:
            digit_text = "" if value == 1 and position else digits[value]
            result.append(f"{digit_text}{units[position]}")

    return "".join(reversed(result))


def format_parenthetical_references(text: str) -> str:
    """Expand parenthetical Bible references into speech-friendly Korean."""
    def replace_reference(match: re.Match[str]) -> str:
        book = BOOK_NAMES.get(match.group("book"), match.group("book"))
        chapter = _number_to_korean_sino(int(match.group("chapter")))
        start_verse = _number_to_korean_sino(int(match.group("start")))
        end_verse = match.group("end")
        if end_verse is None:
            reference = f"{book} {chapter}장 {start_verse}절"
        else:
            end = _number_to_korean_sino(int(end_verse))
            reference = f"{book} {chapter}장 {start_verse}에서 {end}절"
        return f"({reference})"

    return PARENTHETICAL_REFERENCE.sub(replace_reference, text)


def iter_chapters(lines: Iterable[str]) -> Iterator[tuple[str, int, str]]:
    """Yield one chapter at a time, keeping only the current chapter in memory."""
    current_key: tuple[str, int] | None = None
    chapter_lines: list[str] = []
    leading_lines: list[str] = []

    for line in lines:
        match = VERSE_PREFIX.match(line)
        if match is None:
            cleaned_line = format_parenthetical_references(line.strip())
            if cleaned_line:
                if current_key is None:
                    leading_lines.append(cleaned_line)
                else:
                    chapter_lines.append(cleaned_line)
            continue

        book_abbreviation = match.group("book")
        chapter_number = int(match.group("chapter"))
        chapter_key = (book_abbreviation, chapter_number)
        if chapter_key != current_key:
            if current_key is not None:
                book, chapter = current_key
                yield BOOK_NAMES.get(book, book), chapter, "\n".join(chapter_lines)

            chapter_lines = leading_lines
            leading_lines = []
            book_name = BOOK_NAMES.get(book_abbreviation, book_abbreviation)
            chapter_lines.append(
                f"{book_name} {_number_to_korean_sino(chapter_number)}장."
            )
            current_key = chapter_key

        title = match.group("title")
        if title:
            chapter_lines.append(format_parenthetical_references(title.strip()))

        verse_text = format_parenthetical_references(line[match.end():].strip())
        if verse_text:
            chapter_lines.append(verse_text)

    if current_key is None:
        if leading_lines:
            raise ValueError("성경 장절 표기를 찾을 수 없습니다.")
        return

    book, chapter = current_key
    yield BOOK_NAMES.get(book, book), chapter, "\n".join(chapter_lines)


def resolve_book_name(book: str) -> str:
    """Resolve a full Korean book name or its source-text abbreviation."""
    normalized_book = book.strip()
    if normalized_book in BOOK_NAMES:
        return BOOK_NAMES[normalized_book]
    if normalized_book in BOOK_NAMES.values():
        return normalized_book
    raise ValueError(f"알 수 없는 성경 책 이름입니다: {book}")


def playback_position_path(file_path: Path) -> Path:
    if getattr(sys, "frozen", False):
        local_app_data = Path(
            os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local")
        )
        return local_app_data / "BibleReaderTTS" / "playback.json"
    return file_path.with_suffix(file_path.suffix + ".playback.json")


def _chapters_from(
    lines: Iterable[str], start_from: tuple[str, int] | None
) -> Iterator[tuple[str, int, str]]:
    if start_from is None:
        yield from iter_chapters(lines)
        return

    book, chapter = start_from
    target = (resolve_book_name(book), chapter)
    found = False
    for chapter_entry in iter_chapters(lines):
        if not found and chapter_entry[:2] == target:
            found = True
        if found:
            yield chapter_entry

    if not found:
        raise ValueError(f"성경 본문에서 {target[0]} {chapter}장을 찾을 수 없습니다.")


def add_chapter_announcements(text: str) -> str:
    """Add book and chapter announcements while preserving titles and verses."""
    return "\n".join(
        chapter_text for _, _, chapter_text in iter_chapters(text.splitlines())
    ).strip()


def _split_text(text: str, max_chars: int = 500) -> Iterator[str]:
    remaining = text.strip()
    while remaining:
        if len(remaining) <= max_chars:
            yield remaining
            break

        split_at = remaining.rfind(" ", 0, max_chars + 1)
        if split_at < max_chars // 2:
            split_at = max_chars

        yield remaining[:split_at].strip()
        remaining = remaining[split_at:].strip()


async def stream_audio(text: str) -> AsyncIterator[bytes]:
    if not text.strip():
        raise ValueError("텍스트 파일이 비어 있습니다.")

    try:
        import edge_tts
    except ImportError as error:
        raise RuntimeError(
            "음성 엔진이 설치되지 않았습니다. requirements.txt의 의존성을 설치해 주세요."
        ) from error

    for text_chunk in _split_text(text):
        communicator = edge_tts.Communicate(text_chunk, KOREAN_VOICE)
        async for message in communicator.stream():
            if message.get("type") == "audio" and message.get("data"):
                yield message["data"]


async def play_audio(audio_chunks: AsyncIterator[bytes]) -> None:
    mpv_executable = _find_mpv()

    player = await asyncio.create_subprocess_exec(
        mpv_executable,
        "--no-terminal",
        "--really-quiet",
        "-",
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.DEVNULL,
    )

    try:
        if player.stdin is None:
            raise RuntimeError("mpv 입력 스트림을 열 수 없습니다.")

        async for audio_chunk in audio_chunks:
            player.stdin.write(audio_chunk)
            await player.stdin.drain()

        player.stdin.close()
        await player.wait()
        if player.returncode:
            raise RuntimeError("mpv가 오디오 스트림을 재생하지 못했습니다.")
    except BaseException:
        if player.returncode is None:
            player.kill()
            await player.wait()
        raise


def _find_mpv() -> str:
    application_dir = (
        Path(sys.executable).resolve().parent
        if getattr(sys, "frozen", False)
        else Path(__file__).resolve().parent
    )
    bundled_mpv = application_dir / "mpv.exe"
    if bundled_mpv.is_file():
        return str(bundled_mpv)

    mpv_executable = shutil.which("mpv")
    if mpv_executable is None and MPV_FALLBACK_PATH.is_file():
        mpv_executable = str(MPV_FALLBACK_PATH)
    if mpv_executable is None:
        raise RuntimeError("오디오 스트리밍 재생을 위해 mpv를 설치해 주세요.")
    return mpv_executable


def _write_mpv_command(pipe_name: str, command: list[object]) -> None:
    if sys.platform != "win32":
        raise OSError("MPV GUI 제어는 Windows named pipe를 사용합니다.")

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.WaitNamedPipeW.argtypes = (wintypes.LPCWSTR, wintypes.DWORD)
    kernel32.WaitNamedPipeW.restype = wintypes.BOOL
    kernel32.CreateFileW.argtypes = (
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        ctypes.c_void_p,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    )
    kernel32.CreateFileW.restype = wintypes.HANDLE
    kernel32.WriteFile.argtypes = (
        wintypes.HANDLE,
        ctypes.c_void_p,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD),
        ctypes.c_void_p,
    )
    kernel32.WriteFile.restype = wintypes.BOOL
    kernel32.ReadFile.argtypes = (
        wintypes.HANDLE,
        ctypes.c_void_p,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD),
        ctypes.c_void_p,
    )
    kernel32.ReadFile.restype = wintypes.BOOL

    if not kernel32.WaitNamedPipeW(pipe_name, 1000):
        raise ctypes.WinError(ctypes.get_last_error())

    handle = kernel32.CreateFileW(
        pipe_name,
        0xC0000000,
        0,
        None,
        3,
        0,
        None,
    )
    if handle == wintypes.HANDLE(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())

    try:
        payload = json.dumps({"command": command}, separators=(",", ":")).encode() + b"\n"
        buffer = ctypes.create_string_buffer(payload)
        bytes_written = wintypes.DWORD()
        if not kernel32.WriteFile(
            handle, buffer, len(payload), ctypes.byref(bytes_written), None
        ):
            raise ctypes.WinError(ctypes.get_last_error())
        response_buffer = ctypes.create_string_buffer(4096)
        bytes_read = wintypes.DWORD()
        if not kernel32.ReadFile(
            handle, response_buffer, len(response_buffer), ctypes.byref(bytes_read), None
        ):
            raise ctypes.WinError(ctypes.get_last_error())
    finally:
        kernel32.CloseHandle(handle)


async def _send_mpv_command(pipe_name: str, command: list[object]) -> None:
    for attempt in range(20):
        try:
            await asyncio.to_thread(_write_mpv_command, pipe_name, command)
            return
        except OSError:
            if attempt == 19:
                raise
            await asyncio.sleep(0.1)


class PlaybackControls:
    def __init__(self) -> None:
        self.ipc_pipe: str | None = None
        self.paused = False
        self.current_chapter = ""
        self.on_chapter_changed: Callable[[str], None] | None = None

    def set_current_chapter(self, book_name: str, chapter: int) -> None:
        self.current_chapter = f"{book_name} {chapter}장"
        if self.on_chapter_changed is not None:
            self.on_chapter_changed(self.current_chapter)

    def new_ipc_pipe(self) -> str:
        return rf"\\.\pipe\bible-tts-{uuid.uuid4().hex}"

    async def attach_player(self, pipe_name: str) -> None:
        self.ipc_pipe = pipe_name
        if self.paused:
            await _send_mpv_command(pipe_name, ["set_property", "pause", True])

    def detach_player(self, pipe_name: str) -> None:
        if self.ipc_pipe == pipe_name:
            self.ipc_pipe = None

    async def set_paused(self, paused: bool) -> None:
        self.paused = paused
        if self.ipc_pipe is not None:
            await _send_mpv_command(
                self.ipc_pipe, ["set_property", "pause", paused]
            )


async def play_audio_file(
    audio_path: Path, controls: PlaybackControls | None = None
) -> None:
    pipe_name = controls.new_ipc_pipe() if controls is not None else None
    command = [_find_mpv(), "--no-terminal", "--really-quiet"]
    if pipe_name is not None:
        command.append(f"--input-ipc-server={pipe_name}")
    command.append(str(audio_path))

    player = await asyncio.create_subprocess_exec(
        *command,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.DEVNULL,
    )
    try:
        if controls is not None and pipe_name is not None:
            await controls.attach_player(pipe_name)
        await player.wait()
    except asyncio.CancelledError:
        if player.returncode is None:
            player.terminate()
            try:
                await asyncio.wait_for(asyncio.shield(player.wait()), timeout=3)
            except TimeoutError:
                player.kill()
                await asyncio.shield(player.wait())
        raise
    except BaseException:
        if player.returncode is None:
            player.terminate()
            try:
                await asyncio.wait_for(asyncio.shield(player.wait()), timeout=3)
            except TimeoutError:
                player.kill()
                await asyncio.shield(player.wait())
        raise
    finally:
        if controls is not None and pipe_name is not None:
            controls.detach_player(pipe_name)
    if player.returncode:
        raise RuntimeError(f"오디오 파일 재생에 실패했습니다: {audio_path.name}")


async def save_and_play_audio(
    audio_chunks: AsyncIterator[bytes],
    output_path: str | Path,
    controls: PlaybackControls | None = None,
) -> None:
    """Stream each audio chunk to MPV and disk with bounded memory usage."""
    target_path = Path(output_path)
    target_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    player: asyncio.subprocess.Process | None = None
    pipe_name = controls.new_ipc_pipe() if controls is not None else None

    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", suffix=target_path.suffix, dir=target_path.parent, delete=False
        ) as output_file:
            temporary_path = Path(output_file.name)
            command = [_find_mpv(), "--no-terminal", "--really-quiet", "--cache=no"]
            if pipe_name is not None:
                command.append(f"--input-ipc-server={pipe_name}")
            command.extend(("--demuxer=lavf", "-"))

            player = await asyncio.create_subprocess_exec(
                *command,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            if player.stdin is None:
                raise RuntimeError("mpv 입력 스트림을 열 수 없습니다.")

            if controls is not None and pipe_name is not None:
                await controls.attach_player(pipe_name)

            audio_size = 0
            async for audio_chunk in audio_chunks:
                output_file.write(audio_chunk)
                audio_size += len(audio_chunk)
                player.stdin.write(audio_chunk)
                await player.stdin.drain()

            if audio_size == 0:
                raise RuntimeError("음성 데이터가 생성되지 않았습니다.")

            player.stdin.close()
            await player.stdin.wait_closed()
            await player.wait()
            if player.returncode:
                raise RuntimeError("mpv가 오디오 스트림을 재생하지 못했습니다.")

        temporary_path.replace(target_path)
    except BaseException:
        if player is not None and player.returncode is None:
            player.terminate()
            try:
                await asyncio.wait_for(asyncio.shield(player.wait()), timeout=3)
            except TimeoutError:
                player.kill()
                await asyncio.shield(player.wait())
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
        raise
    finally:
        if controls is not None and pipe_name is not None:
            controls.detach_player(pipe_name)


async def save_audio(audio_chunks: AsyncIterator[bytes], output_path: str | Path) -> None:
    target_path = Path(output_path)
    target_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None

    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", suffix=target_path.suffix, dir=target_path.parent, delete=False
        ) as output_file:
            temporary_path = Path(output_file.name)
            audio_size = 0
            async for audio_chunk in audio_chunks:
                output_file.write(audio_chunk)
                audio_size += len(audio_chunk)

        if audio_size == 0:
            raise RuntimeError("음성 데이터가 생성되지 않았습니다.")

        temporary_path.replace(target_path)
    except BaseException:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
        raise


async def _tee_audio_chunks(
    audio_chunks: AsyncIterator[bytes],
    combined_file: BinaryIO,
    budget: AudioBudget | None = None,
) -> AsyncIterator[bytes]:
    async for audio_chunk in audio_chunks:
        if budget is not None:
            if len(audio_chunk) > budget.remaining_bytes:
                budget.exhausted = True
                return
            budget.remaining_bytes -= len(audio_chunk)
        combined_file.write(audio_chunk)
        yield audio_chunk


async def speak_text(text: str) -> None:
    await play_audio(stream_audio(text))


async def process_chapters(
    lines: Iterable[str],
    output_dir: Path | None = None,
    *,
    play: bool = True,
    max_audio_bytes: int | None = None,
    playback_controls: PlaybackControls | None = None,
    start_from: tuple[str, int] | None = None,
) -> None:
    if max_audio_bytes is not None and max_audio_bytes <= 0:
        raise ValueError("최대 오디오 크기는 0보다 커야 합니다.")

    if output_dir is None:
        with tempfile.TemporaryDirectory(prefix="bible-tts-") as temporary_dir:
            await _process_chapters(
                lines,
                Path(temporary_dir),
                play=play,
                retain_files=False,
                max_audio_bytes=max_audio_bytes,
                playback_controls=playback_controls,
                start_from=start_from,
            )
        return

    output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".staging-", dir=output_dir) as staging_dir:
        staging_path = Path(staging_dir)
        await _process_chapters(
            lines,
            staging_path,
            play=play,
            retain_files=True,
            max_audio_bytes=max_audio_bytes,
            playback_controls=playback_controls,
            start_from=start_from,
        )
        clear_generated_audio(output_dir)
        for staged_file in staging_path.glob("*.mp3"):
            output_path = output_dir / staged_file.name
            staged_file.replace(output_path)
            print(f"저장: {output_path}")
        print(f"전체 음성 파일을 저장했습니다: {output_dir / 'total.mp3'}")


def clear_generated_audio(output_dir: Path) -> None:
    for audio_file in output_dir.glob("*.mp3"):
        if audio_file.name == "total.mp3" or CHAPTER_AUDIO_FILENAME.fullmatch(
            audio_file.name
        ):
            audio_file.unlink()


async def _process_chapters(
    lines: Iterable[str],
    output_dir: Path,
    *,
    play: bool,
    retain_files: bool,
    max_audio_bytes: int | None,
    playback_controls: PlaybackControls | None,
    start_from: tuple[str, int] | None,
) -> None:
    total_path = output_dir / "total.mp3"
    temporary_total_path: Path | None = None
    total_context = (
        tempfile.NamedTemporaryFile(
            mode="wb", suffix=".mp3", dir=output_dir, delete=False
        )
        if retain_files
        else nullcontext(None)
    )
    budget = AudioBudget(max_audio_bytes) if max_audio_bytes is not None else None
    chapter_count = 0

    try:
        with total_context as total_file:
            if total_file is not None:
                temporary_total_path = Path(total_file.name)

            for index, (book_name, chapter, chapter_text) in enumerate(
                _chapters_from(lines, start_from), start=1
            ):
                chapter_count += 1
                if budget is not None and budget.remaining_bytes == 0:
                    break
                if playback_controls is not None:
                    playback_controls.set_current_chapter(book_name, chapter)

                filename = f"{index:04d}_{book_name}_{chapter:03d}.mp3"
                audio_path = output_dir / filename
                audio_chunks = stream_audio(chapter_text)
                if total_file is not None:
                    audio_chunks = _tee_audio_chunks(audio_chunks, total_file, budget)
                try:
                    if play:
                        await save_and_play_audio(
                            audio_chunks, audio_path, playback_controls
                        )
                    else:
                        await save_audio(audio_chunks, audio_path)
                finally:
                    if not retain_files:
                        audio_path.unlink(missing_ok=True)

                if budget is not None and (
                    budget.exhausted or budget.remaining_bytes == 0
                ):
                    break

            if chapter_count == 0:
                raise ValueError("파일에서 성경 장절 표기를 찾을 수 없습니다.")
            if total_file is not None and total_file.tell() == 0:
                raise RuntimeError("합칠 음성 데이터가 생성되지 않았습니다.")

        if temporary_total_path is not None:
            temporary_total_path.replace(total_path)
    except BaseException:
        if temporary_total_path is not None:
            temporary_total_path.unlink(missing_ok=True)
        raise

async def _run(
    file_path: Path,
    output_path: Path | None,
    chapter_dir: Path | None,
    test_mode: bool,
    once: bool,
) -> None:
    if output_path is not None:
        text = add_chapter_announcements(read_text_file(file_path))
        await save_audio(stream_audio(text), output_path)
        return

    if test_mode or chapter_dir is not None:
        output_dir = chapter_dir or file_path.with_name(f"{file_path.stem}_test_output")
        with open_text_file(file_path) as input_file:
            await process_chapters(
                input_file,
                output_dir,
                play=False,
                max_audio_bytes=TEST_AUDIO_MAX_BYTES,
            )
        return

    while True:
        with open_text_file(file_path) as input_file:
            await process_chapters(input_file, play=True)
        if once:
            return
        print("마지막 장까지 읽었습니다. 처음부터 다시 읽습니다.")


class PlaybackControlWindow(PlaybackControls):
    def __init__(self, file_path: Path, once: bool = False) -> None:
        import tkinter as tk
        from tkinter import ttk

        super().__init__()
        self.file_path = file_path
        self.position_path = playback_position_path(file_path)
        self.once = once
        saved_position = self._load_position()
        self.root = tk.Tk()
        self.root.title("성경 음성 재생")
        self.root.geometry("380x245")
        self.root.resizable(False, False)
        self.status = tk.StringVar(value="대기 중")
        self.chapter_status = tk.StringVar(value="대기 중")
        self.book_entry = tk.StringVar(
            value=saved_position[0] if saved_position is not None else ""
        )
        self.chapter_entry = tk.StringVar(
            value=str(saved_position[1]) if saved_position is not None else ""
        )
        self._status_queue: queue.Queue[tuple[str, str]] = queue.Queue()
        self._stop_event = threading.Event()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._task: asyncio.Task[None] | None = None
        self._thread: threading.Thread | None = None
        self._closing = False
        self.on_chapter_changed = self._post_chapter

        frame = ttk.Frame(self.root, padding=16)
        frame.pack(fill="both", expand=True)
        position = ttk.Frame(frame)
        position.pack(fill="x", pady=(0, 8))
        ttk.Label(position, text="책").pack(side="left")
        ttk.Entry(position, textvariable=self.book_entry, width=16).pack(
            side="left", padx=(4, 10)
        )
        ttk.Label(position, text="장").pack(side="left")
        ttk.Entry(position, textvariable=self.chapter_entry, width=5).pack(
            side="left", padx=4
        )
        ttk.Label(frame, text="현재 읽는 성경 장", anchor="center").pack(fill="x")
        ttk.Label(
            frame, textvariable=self.chapter_status, anchor="center",
            font=("Segoe UI", 11, "bold"),
        ).pack(fill="x", pady=(2, 8))
        ttk.Label(frame, textvariable=self.status, anchor="center").pack(
            fill="x", pady=(0, 10)
        )
        buttons = ttk.Frame(frame)
        buttons.pack()
        ttk.Button(buttons, text="이어서 재생", command=self.start).pack(
            side="left", padx=4
        )
        ttk.Button(buttons, text="지정 위치 재생", command=self.start_selected).pack(
            side="left", padx=4
        )
        controls = ttk.Frame(frame)
        controls.pack(pady=(6, 0))
        ttk.Button(controls, text="일시정지", command=self.pause).pack(
            side="left", padx=4
        )
        ttk.Button(controls, text="종료", command=self.stop).pack(side="left", padx=4)

        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(100, self._poll_status)

    def show(self) -> None:
        self.root.mainloop()

    def _post_status(self, message: str) -> None:
        self._status_queue.put(("status", message))

    def _post_chapter(self, message: str) -> None:
        self._status_queue.put(("chapter", message))

    def _load_position(self) -> tuple[str, int] | None:
        if not self.position_path.exists():
            return None
        with self.position_path.open(encoding="utf-8") as position_file:
            position = json.load(position_file)
        if not isinstance(position, dict):
            raise ValueError(f"재생 위치 파일 형식이 올바르지 않습니다: {self.position_path}")
        book = resolve_book_name(str(position.get("book", "")))
        chapter = position.get("chapter")
        if not isinstance(chapter, int) or isinstance(chapter, bool) or chapter < 1:
            raise ValueError(f"재생 위치 파일의 장 번호가 올바르지 않습니다: {self.position_path}")
        return book, chapter

    def _save_position(self, book_name: str, chapter: int) -> None:
        temporary_path: Path | None = None
        try:
            self.position_path.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                suffix=".tmp",
                dir=self.position_path.parent,
                delete=False,
            ) as position_file:
                temporary_path = Path(position_file.name)
                json.dump(
                    {"book": book_name, "chapter": chapter},
                    position_file,
                    ensure_ascii=False,
                )
                position_file.write("\n")
            temporary_path.replace(self.position_path)
        except BaseException:
            if temporary_path is not None:
                temporary_path.unlink(missing_ok=True)
            raise

    def set_current_chapter(self, book_name: str, chapter: int) -> None:
        super().set_current_chapter(book_name, chapter)
        self._save_position(book_name, chapter)

    def _poll_status(self) -> None:
        while True:
            try:
                kind, message = self._status_queue.get_nowait()
                if kind == "chapter":
                    self.chapter_status.set(message)
                else:
                    self.status.set(message)
            except queue.Empty:
                break
        if not self._closing:
            self.root.after(100, self._poll_status)

    def start(self) -> None:
        try:
            start_from = self._load_position()
        except (OSError, ValueError, json.JSONDecodeError) as error:
            self._post_status(f"오류: 재생 위치를 읽지 못했습니다: {error}")
            return
        self._start(start_from)

    def start_selected(self) -> None:
        try:
            book = resolve_book_name(self.book_entry.get())
            chapter_text = self.chapter_entry.get().strip()
            chapter = int(chapter_text)
            if chapter < 1:
                raise ValueError("장 번호는 1 이상이어야 합니다.")
        except ValueError as error:
            self._post_status(f"입력 오류: {error}")
            return
        self._start((book, chapter))

    def _start(self, start_from: tuple[str, int] | None) -> None:
        if self._thread is not None and self._thread.is_alive():
            if self.paused:
                self._submit(self.set_paused(False))
            return

        self.paused = False
        self._stop_event.clear()
        self._post_status("재생 준비 중")
        self._thread = threading.Thread(
            target=self._worker, args=(start_from,), daemon=True
        )
        self._thread.start()

    def pause(self) -> None:
        if self._thread is None or not self._thread.is_alive():
            self._post_status("먼저 재생을 시작하세요")
            return
        self._submit(self.set_paused(not self.paused))

    async def set_paused(self, paused: bool) -> None:
        await super().set_paused(paused)
        self._post_status("일시정지" if paused else "재생 중")

    def stop(self) -> None:
        self._stop_event.set()
        if self._loop is not None and self._task is not None:
            self._loop.call_soon_threadsafe(self._task.cancel)
        else:
            self._post_status("중지됨")

    def close(self) -> None:
        self._closing = True
        self.stop()
        self._finish_close()

    def _finish_close(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            self.root.after(100, self._finish_close)
        else:
            self.root.destroy()

    def _submit(self, coroutine: object) -> None:
        if self._loop is None or self._loop.is_closed():
            return
        asyncio.run_coroutine_threadsafe(coroutine, self._loop)

    def _worker(self, start_from: tuple[str, int] | None) -> None:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self._loop = loop
        self._task = loop.create_task(self._repeat_playback(start_from))
        final_status = "재생 종료"
        try:
            loop.run_until_complete(self._task)
        except asyncio.CancelledError:
            final_status = "중지됨"
        except Exception as error:
            final_status = f"오류: {error}"
        finally:
            self.ipc_pipe = None
            self._task = None
            self._loop = None
            loop.run_until_complete(loop.shutdown_asyncgens())
            loop.close()
            self._post_status(final_status)

    async def _repeat_playback(
        self, start_from: tuple[str, int] | None = None
    ) -> None:
        while not self._stop_event.is_set():
            self._post_status("본문 읽는 중")
            with open_text_file(self.file_path) as input_file:
                await process_chapters(
                    input_file,
                    play=True,
                    playback_controls=self,
                    start_from=start_from,
                )
            if self.once or self._stop_event.is_set():
                return
            start_from = None
            self._post_status("처음부터 다시 재생합니다")


def launch_control_window(file_path: Path, once: bool = False) -> None:
    PlaybackControlWindow(file_path, once=once).show()


def main() -> int:
    parser = argparse.ArgumentParser(description="UTF-8 텍스트 파일을 한국어 음성으로 읽습니다.")
    parser.add_argument(
        "file",
        nargs="?",
        type=Path,
        default=Path(__file__).with_name("개역개정.txt"),
        help="읽을 텍스트 파일 (기본값: 프로그램 폴더의 개역개정.txt)",
    )
    output_mode = parser.add_mutually_exclusive_group()
    output_mode.add_argument(
        "--output",
        type=Path,
        help="전체 내용을 MP3 한 파일로 저장합니다.",
    )
    output_mode.add_argument(
        "--chapter-dir",
        type=Path,
        help="테스트용 장별 MP3 저장 폴더 (지정 시 파일을 보존하고 재생하지 않습니다)",
    )
    output_mode.add_argument(
        "--test",
        "--no-play",
        dest="test_mode",
        action="store_true",
        help="약 3분 분량의 장별 MP3와 total.mp3만 저장하고 재생하지 않습니다.",
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="기본 재생 모드에서 한 번만 읽고 종료합니다.",
    )
    parser.add_argument(
        "--controls",
        action="store_true",
        help="재생·일시정지·종료 버튼이 있는 작은 제어 창을 엽니다.",
    )
    args = parser.parse_args()

    try:
        if args.controls:
            launch_control_window(args.file, once=args.once)
            return 0
        asyncio.run(
            _run(args.file, args.output, args.chapter_dir, args.test_mode, args.once)
        )
        if args.output is not None:
            print(f"음성 파일을 저장했습니다: {args.output}")
        elif args.test_mode or args.chapter_dir is not None:
            output_dir = args.chapter_dir or args.file.with_name(f"{args.file.stem}_test_output")
            print(f"장별 음성 파일을 저장했습니다: {output_dir}")
    except KeyboardInterrupt:
        print("\n사용자 요청으로 음성 읽기를 중지했습니다.")
        return 0
    except (OSError, RuntimeError, ValueError) as error:
        print(f"오류: {error}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
