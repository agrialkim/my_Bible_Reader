import asyncio
import threading
from pathlib import Path

import pytest

from BibleReaderTTS import bible_file_path
from textToSpeech import (
    PlaybackControlWindow,
    PlaybackControls,
    _find_mpv,
    _run,
    playback_position_path,
    process_chapters,
)


def test_playback_controls_pause_resume_and_apply_pause_on_new_chapter(monkeypatch):
    commands = []

    async def fake_send_mpv_command(pipe_name, command):
        commands.append((pipe_name, command))

    monkeypatch.setattr("textToSpeech._send_mpv_command", fake_send_mpv_command)
    controls = PlaybackControls()

    asyncio.run(controls.set_paused(True))
    assert controls.paused
    assert commands == []

    pipe_name = r"\\.\pipe\test-player"
    asyncio.run(controls.attach_player(pipe_name))
    assert commands == [(pipe_name, ["set_property", "pause", True])]

    asyncio.run(controls.set_paused(False))
    assert not controls.paused
    assert commands[-1] == (pipe_name, ["set_property", "pause", False])


def test_current_chapter_updates_at_each_chapter_boundary(monkeypatch):
    controls = PlaybackControls()
    locations = []
    controls.on_chapter_changed = locations.append

    async def fake_stream_audio(text):
        yield b"audio"

    async def fake_save_and_play_audio(audio_chunks, output_path, playback_controls):
        async for _ in audio_chunks:
            pass

    monkeypatch.setattr("textToSpeech.stream_audio", fake_stream_audio)
    monkeypatch.setattr("textToSpeech.save_and_play_audio", fake_save_and_play_audio)

    asyncio.run(
        process_chapters(
            ["창1:1 첫째 장", "창2:1 둘째 장"],
            playback_controls=controls,
        )
    )

    assert locations == ["창세기 1장", "창세기 2장"]
    assert controls.current_chapter == "창세기 2장"


def test_playback_position_is_saved_and_loaded(tmp_path):
    source_file = tmp_path / "bible.txt"
    window = object.__new__(PlaybackControlWindow)
    window.file_path = source_file
    window.position_path = source_file.with_suffix(".txt.playback.json")
    window.on_chapter_changed = None

    window.set_current_chapter("창세기", 12)

    assert window._load_position() == ("창세기", 12)
    assert not list(tmp_path.glob("*.tmp"))


def test_frozen_app_uses_persistent_user_data_for_playback_position(
    monkeypatch, tmp_path
):
    monkeypatch.setattr("textToSpeech.sys.frozen", True, raising=False)
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path))

    assert playback_position_path(tmp_path / "bundle" / "bible.txt") == (
        tmp_path / "BibleReaderTTS" / "playback.json"
    )


def test_frozen_app_reads_bible_text_next_to_executable(monkeypatch, tmp_path):
    executable = tmp_path / "BibleReader V2.exe"
    monkeypatch.setattr("BibleReaderTTS.sys.frozen", True, raising=False)
    monkeypatch.setattr("BibleReaderTTS.sys.executable", str(executable))

    assert bible_file_path() == tmp_path / "개역개정.txt"


def test_mpv_bundled_next_to_frozen_executable_is_preferred(monkeypatch, tmp_path):
    executable = tmp_path / "BibleReader V2.exe"
    bundled_mpv = tmp_path / "mpv.exe"
    bundled_mpv.touch()
    monkeypatch.setattr("textToSpeech.sys.frozen", True, raising=False)
    monkeypatch.setattr("textToSpeech.sys.executable", str(executable))
    monkeypatch.setattr("textToSpeech.shutil.which", lambda _: "system-mpv")

    assert _find_mpv() == str(bundled_mpv)


def test_control_window_reads_cp949_input(monkeypatch, tmp_path):
    source_file = tmp_path / "legacy.txt"
    source_file.write_bytes("창1:1 본문".encode("cp949"))
    window = object.__new__(PlaybackControlWindow)
    window.file_path = source_file
    window.once = True
    window._stop_event = threading.Event()
    window._post_status = lambda message: None
    received_lines = []

    async def fake_process_chapters(lines, **kwargs):
        received_lines.extend(lines)

    monkeypatch.setattr("textToSpeech.process_chapters", fake_process_chapters)

    asyncio.run(window._repeat_playback())

    assert received_lines == ["창1:1 본문"]


def test_run_repeats_from_start_until_cancelled(monkeypatch, tmp_path):
    source_file = tmp_path / "sample.txt"
    source_file.write_text("창1:1 본문", encoding="utf-8")
    passes = []

    class StopAfterSecondPass(Exception):
        pass

    async def fake_process_chapters(lines, output_dir=None, *, play=True, max_audio_bytes=None):
        passes.append(list(lines))
        if len(passes) == 2:
            raise StopAfterSecondPass

    monkeypatch.setattr("textToSpeech.process_chapters", fake_process_chapters)

    with pytest.raises(StopAfterSecondPass):
        asyncio.run(_run(source_file, None, None, False, False))

    assert passes == [["창1:1 본문"], ["창1:1 본문"]]


def test_run_once_stops_after_last_chapter(monkeypatch, tmp_path):
    source_file = tmp_path / "sample.txt"
    source_file.write_text("창1:1 본문", encoding="utf-8")
    passes = []

    async def fake_process_chapters(lines, output_dir=None, *, play=True, max_audio_bytes=None):
        passes.append(list(lines))

    monkeypatch.setattr("textToSpeech.process_chapters", fake_process_chapters)

    asyncio.run(_run(source_file, None, None, False, True))

    assert passes == [["창1:1 본문"]]