#!/usr/bin/env python3
"""Publish Darkbag release APK and HTML release notes to a Telegram channel via Bot API."""

import html
import json
import os
import subprocess
import sys


def post_telegram(token: str, method: str, form_fields: dict[str, str], file_path: str | None = None) -> dict:
    url = f"https://api.telegram.org/bot{token}/{method}"
    cmd = ["curl", "-sS", "--fail-with-body", "-X", "POST", url]
    for k, v in form_fields.items():
        cmd.extend(["--form-string", f"{k}={v}"])
    if file_path is not None:
        cmd.extend(["-F", f"document=@{file_path}"])
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"Telegram {method} failed (exit {res.returncode}): {res.stdout} {res.stderr}")
    data = json.loads(res.stdout)
    if not data.get("ok"):
        raise RuntimeError(f"Telegram {method} returned error: {data}")
    return data


def main():
    token = os.environ.get("TELEGRAM_TOKEN", "").strip()
    chat_id = os.environ.get("TELEGRAM_CHAT_ID", "").strip()
    apk_path = os.environ.get("APK_PATH", "").strip()
    message_file = os.environ.get("MESSAGE_FILE", "").strip()
    version_name = os.environ.get("VERSION_NAME", "").strip()

    if not token or not chat_id:
        raise RuntimeError("TELEGRAM_TOKEN and TELEGRAM_CHAT_ID must be set.")
    if not apk_path or not os.path.isfile(apk_path):
        raise RuntimeError(f"APK_PATH not found: {apk_path}")
    if not message_file or not os.path.isfile(message_file):
        raise RuntimeError(f"MESSAGE_FILE not found: {message_file}")

    with open(message_file, "r", encoding="utf-8") as f:
        message = f.read().strip()

    if len(message) <= 1024:
        post_telegram(
            token,
            "sendDocument",
            {"chat_id": chat_id, "caption": message, "parse_mode": "HTML"},
            file_path=apk_path,
        )
    else:
        short_caption = (
            f"#app #apk #darkbag\n"
            f"<b>Darkbag {html.escape(version_name)}</b> — <code>{html.escape(os.path.basename(apk_path))}</code>"
        )
        post_telegram(
            token,
            "sendDocument",
            {"chat_id": chat_id, "caption": short_caption, "parse_mode": "HTML"},
            file_path=apk_path,
        )
        post_telegram(
            token,
            "sendMessage",
            {
                "chat_id": chat_id,
                "text": message,
                "parse_mode": "HTML",
                "disable_web_page_preview": "true",
            },
        )
    print("Telegram release published successfully.")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
