import asyncio
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

from textToSpeech import (
    TEST_AUDIO_MAX_BYTES,
    add_chapter_announcements,
    format_parenthetical_references,
    iter_chapters,
    open_text_file,
    read_text_file,
    remove_verse_references,
    process_chapters,
    save_audio,
    save_and_play_audio,
    stream_audio,
)


def test_remove_verse_references():
    source = "창1:1 <천지 창조> 태초에 하나님이 천지를 창조하시니라\n창 1:2 땅이 혼돈하고 공허하며"

    assert remove_verse_references(source) == (
        "태초에 하나님이 천지를 창조하시니라\n땅이 혼돈하고 공허하며"
    )


def test_read_cp949_text_file(tmp_path):
    expected_text = "창1:1 태초에 하나님이 천지를 창조하시니라"
    text_file = tmp_path / "legacy.txt"
    text_file.write_bytes(expected_text.encode("cp949"))

    with open_text_file(text_file) as opened_file:
        assert opened_file.encoding == "cp949"
        assert opened_file.read() == expected_text


def test_add_chapter_announcements_only_at_chapter_changes():
    source = (
        "창1:1 <천지 창조> 태초에 하나님이 천지를 창조하시니라\n"
        "창1:2 땅이 혼돈하고 공허하며\n"
        "창 2:1 천지와 만물이 다 이루어지니라\n"
        "출1:1 <이스라엘 자손> 야곱과 함께 애굽에 이른 이스라엘 자손의 이름은 이러하니\n"
        "삼상14:2 사울이 기브아에 머물렀고\n"
        "시119:1 행위가 온전한 자는 복이 있도다"
    )

    assert add_chapter_announcements(source) == (
        "창세기 일장.\n"
        "천지 창조\n"
        "태초에 하나님이 천지를 창조하시니라\n"
        "땅이 혼돈하고 공허하며\n"
        "창세기 이장.\n"
        "천지와 만물이 다 이루어지니라\n"
        "출애굽기 일장.\n"
        "이스라엘 자손\n"
        "야곱과 함께 애굽에 이른 이스라엘 자손의 이름은 이러하니\n"
        "사무엘상 십사장.\n"
        "사울이 기브아에 머물렀고\n"
        "시편 백십구장.\n"
        "행위가 온전한 자는 복이 있도다"
    )


def test_parenthetical_reference_uses_full_book_name_and_spoken_range():
    assert format_parenthetical_references("(수 15:13-19)") == (
        "(여호수아 십오장 십삼에서 십구절)"
    )
    assert format_parenthetical_references("(수 15:13)") == "(여호수아 십오장 십삼절)"


def test_chapter_title_formats_parenthetical_reference():
    source = "삿1:11 <옷니엘이 드빌을 치다(수 15:13-19)> 드빌의 주민들을 쳤으니"

    assert add_chapter_announcements(source) == (
        "사사기 일장.\n"
        "옷니엘이 드빌을 치다(여호수아 십오장 십삼에서 십구절)\n"
        "드빌의 주민들을 쳤으니"
    )


def test_iter_chapters_groups_verses_by_chapter():
    source = [
        "창1:1 <천지 창조> 태초에 하나님이 천지를 창조하시니라",
        "창1:2 땅이 혼돈하고 공허하며",
        "창2:1 천지와 만물이 다 이루어지니라",
    ]

    assert list(iter_chapters(source)) == [
        (
            "창세기",
            1,
            "창세기 일장.\n천지 창조\n태초에 하나님이 천지를 창조하시니라\n땅이 혼돈하고 공허하며",
        ),
        ("창세기", 2, "창세기 이장.\n천지와 만물이 다 이루어지니라"),
    ]


def test_process_chapters_can_start_from_a_selected_book_and_chapter(monkeypatch):
    source = ["창1:1 첫째 장", "창2:1 둘째 장", "출1:1 셋째 장"]
    spoken_texts = []

    async def fake_stream_audio(text):
        spoken_texts.append(text)
        yield b"audio"

    async def fake_save_and_play_audio(audio_chunks, output_path, controls=None):
        async for _ in audio_chunks:
            pass

    monkeypatch.setattr("textToSpeech.stream_audio", fake_stream_audio)
    monkeypatch.setattr("textToSpeech.save_and_play_audio", fake_save_and_play_audio)

    asyncio.run(process_chapters(source, start_from=("창세기", 2)))

    assert len(spoken_texts) == 2
    assert spoken_texts[0].startswith("창세기 이장.")
    assert spoken_texts[1].startswith("출애굽기 일장.")


def test_process_chapters_reports_missing_start_location(monkeypatch):
    async def fake_stream_audio(text):
        yield b"audio"

    async def fake_save_and_play_audio(audio_chunks, output_path, controls=None):
        async for _ in audio_chunks:
            pass

    monkeypatch.setattr("textToSpeech.stream_audio", fake_stream_audio)
    monkeypatch.setattr("textToSpeech.save_and_play_audio", fake_save_and_play_audio)

    with pytest.raises(ValueError, match="성경 본문에서 창세기 3장을 찾을 수 없습니다"):
        asyncio.run(process_chapters(["창1:1 첫째 장"], start_from=("창", 3)))


def test_test_mode_keeps_chapter_files_and_total_without_playing(monkeypatch, tmp_path):
    source = ["창1:1 첫째 장 본문", "창2:1 둘째 장 본문"]
    events = []
    streamed_texts = []
    stale_chapter = tmp_path / "0009_창세기_009.mp3"
    stale_total = tmp_path / "total.mp3"
    unrelated_mp3 = tmp_path / "personal-recording.mp3"
    stale_chapter.write_bytes(b"old chapter")
    stale_total.write_bytes(b"old total")
    unrelated_mp3.write_bytes(b"keep me")

    async def fake_save_audio(audio_chunks, output_path):
        output_path.write_bytes(b"".join([chunk async for chunk in audio_chunks]))
        events.append(("save", output_path.name))

    async def fake_stream_audio(text):
        streamed_texts.append(text)
        yield text.encode("utf-8")

    async def fake_play_audio_file(audio_path):
        events.append(("play", audio_path.name))

    monkeypatch.setattr("textToSpeech.save_audio", fake_save_audio)
    monkeypatch.setattr("textToSpeech.stream_audio", fake_stream_audio)
    monkeypatch.setattr("textToSpeech.play_audio_file", fake_play_audio_file)

    asyncio.run(process_chapters(source, tmp_path, play=False))

    assert events == [
        ("save", "0001_창세기_001.mp3"),
        ("save", "0002_창세기_002.mp3"),
    ]
    assert (tmp_path / "0001_창세기_001.mp3").exists()
    assert (tmp_path / "0002_창세기_002.mp3").exists()
    assert (tmp_path / "total.mp3").read_bytes() == b"".join(
        text.encode("utf-8") for text in streamed_texts
    )
    assert not stale_chapter.exists()
    assert unrelated_mp3.read_bytes() == b"keep me"
    assert not list(tmp_path.glob(".staging-*"))


def test_production_mode_removes_each_temporary_chapter_after_playback(
    monkeypatch,
):
    source = ["창1:1 첫째 장 본문", "창2:1 둘째 장 본문"]
    events = []
    saved_paths = []

    async def fake_save_and_play_audio(audio_chunks, output_path, controls=None):
        output_path.write_bytes(b"".join([chunk async for chunk in audio_chunks]))
        saved_paths.append(output_path)
        events.append(("stream-and-play", output_path.name))

    async def fake_stream_audio(text):
        yield text.encode("utf-8")

    monkeypatch.setattr("textToSpeech.save_and_play_audio", fake_save_and_play_audio)
    monkeypatch.setattr("textToSpeech.stream_audio", fake_stream_audio)

    asyncio.run(process_chapters(source))

    assert events == [
        ("stream-and-play", "0001_창세기_001.mp3"),
        ("stream-and-play", "0002_창세기_002.mp3"),
    ]
    assert all(not audio_path.exists() for audio_path in saved_paths)
    assert all(not audio_path.parent.exists() for audio_path in saved_paths)


def test_audio_chunks_are_saved_and_sent_to_player_as_they_arrive(
    monkeypatch, tmp_path
):
    class FakeStdin:
        def __init__(self):
            self.chunks = []
            self.closed = False

        def write(self, chunk):
            self.chunks.append(chunk)

        async def drain(self):
            return None

        def close(self):
            self.closed = True

        async def wait_closed(self):
            return None

    class FakePlayer:
        def __init__(self):
            self.stdin = FakeStdin()
            self.returncode = None

        async def wait(self):
            self.returncode = 0
            return 0

        def terminate(self):
            self.returncode = 0

        async def communicate(self):
            return b"", b""

        def kill(self):
            self.returncode = -1

    player = FakePlayer()
    commands = []

    async def fake_create_subprocess_exec(*command, **kwargs):
        commands.append(command)
        return player

    async def fake_audio_stream():
        yield b"first"
        assert player.stdin.chunks == [b"first"]
        yield b"second"

    monkeypatch.setattr("textToSpeech._find_mpv", lambda: "mpv")
    monkeypatch.setattr(
        "textToSpeech.asyncio.create_subprocess_exec", fake_create_subprocess_exec
    )

    output_file = tmp_path / "chapter.mp3"
    asyncio.run(save_and_play_audio(fake_audio_stream(), output_file))

    assert output_file.read_bytes() == b"firstsecond"
    assert player.stdin.chunks == [b"first", b"second"]
    assert player.stdin.closed
    assert "--demuxer=lavf" in commands[0]


def test_test_mode_audio_limit_is_about_three_minutes():
    assert TEST_AUDIO_MAX_BYTES == 1_080_000


def test_empty_input_does_not_start_a_repeating_playback_loop():
    with pytest.raises(ValueError, match="장절 표기를 찾을 수 없습니다"):
        asyncio.run(process_chapters([]))


def test_test_mode_stops_at_audio_budget(monkeypatch, tmp_path):
    source = ["창1:1 첫째 장 본문", "창2:1 둘째 장 본문"]
    streamed_texts = []

    async def fake_stream_audio(text):
        streamed_texts.append(text)
        yield b"1234"
        yield b"5678"

    async def fake_save_audio(audio_chunks, output_path):
        output_path.write_bytes(b"".join([chunk async for chunk in audio_chunks]))

    monkeypatch.setattr("textToSpeech.stream_audio", fake_stream_audio)
    monkeypatch.setattr("textToSpeech.save_audio", fake_save_audio)

    asyncio.run(
        process_chapters(source, tmp_path, play=False, max_audio_bytes=6)
    )

    assert len(streamed_texts) == 1
    assert (tmp_path / "0001_창세기_001.mp3").read_bytes() == b"1234"
    assert not (tmp_path / "0002_창세기_002.mp3").exists()
    assert (tmp_path / "total.mp3").read_bytes() == b"1234"


def test_korean_sino_chapter_number():
    from textToSpeech import _number_to_korean_sino

    assert [_number_to_korean_sino(number) for number in (1, 10, 11, 20, 101, 150)] == [
        "일",
        "십",
        "십일",
        "이십",
        "백일",
        "백오십",
    ]


def test_textToSpeech(monkeypatch, tmp_path):
    text = read_text_file(Path(__file__).with_name("a.txt")).strip()
    expected_audio = [b"audio-chunk-1", b"audio-chunk-2"]
    requests = []
    output_file = tmp_path / "a.mp3"

    class FakeCommunicate:
        def __init__(self, request_text, voice):
            requests.append((request_text, voice))

        async def stream(self):
            yield {"type": "WordBoundary", "data": b"metadata"}
            for chunk in expected_audio:
                yield {"type": "audio", "data": chunk}

    monkeypatch.setitem(
        sys.modules,
        "edge_tts",
        SimpleNamespace(Communicate=FakeCommunicate),
    )

    asyncio.run(save_audio(stream_audio(text), output_file))

    assert output_file.read_bytes() == b"".join(expected_audio)
    assert requests == [(text, "ko-KR-SunHiNeural")]