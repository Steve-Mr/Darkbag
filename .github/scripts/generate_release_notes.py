#!/usr/bin/env python3
"""Deterministic, non-LLM release notes and Telegram message generator for Darkbag."""

import argparse
import html
import re
import subprocess
from collections import OrderedDict

CC_RE = re.compile(
    r"^(feat|fix|perf|refactor|build|chore|docs|test|ci|style)(?:\(([^)]+)\))?(!)?:\s*(.+)$",
    re.IGNORECASE,
)
PR_RE = re.compile(r"#(\d+)")

CATEGORIES = OrderedDict([
    ("feat", "✨ Features"),
    ("fix", "🐛 Bug Fixes"),
    ("perf", "⚡ Performance"),
    ("refactor", "♻️ Refactor & Architecture"),
    ("build", "🔧 Build & Dependencies"),
    ("other", "📝 Other Changes"),
])

TYPE_TO_CATEGORY = {
    "feat": "feat",
    "fix": "fix",
    "perf": "perf",
    "refactor": "refactor",
    "build": "build",
    "ci": "build",
    "chore": "other",
    "docs": "other",
    "test": "other",
    "style": "other",
}


def collect_commits(base_ref: str, head_ref: str):
    if not base_ref:
        return []
    cmd = ["git", "log", "--no-merges", "--reverse", f"{base_ref}..{head_ref}", "--format=%H%x1f%s"]
    res = subprocess.run(cmd, check=True, capture_output=True, text=True)
    commits = []
    for line in res.stdout.splitlines():
        if "\x1f" not in line:
            continue
        full_sha, subject = line.split("\x1f", 1)
        subject = subject.strip()
        if not subject or subject.startswith("Merge "):
            continue
        commits.append((full_sha.strip(), subject))
    return commits


def categorize_commits(commits):
    grouped = OrderedDict((k, []) for k in CATEGORIES)
    for full_sha, subject in commits:
        # Strip accidental backticks wrapping conventional commit subjects
        cleaned = subject.strip("`")
        m = CC_RE.match(cleaned)
        if m:
            ctype = m.group(1).lower()
            scope = m.group(2)
            breaking = bool(m.group(3))
            desc = m.group(4).strip()
            cat = TYPE_TO_CATEGORY.get(ctype, "other")
        else:
            scope = None
            breaking = False
            desc = cleaned
            cat = "other"
        grouped[cat].append((full_sha, scope, breaking, desc))
    return grouped


def format_md_entry(full_sha: str, scope: str | None, breaking: bool, desc: str, repo: str) -> str:
    short_sha = full_sha[:7]
    desc_linked = PR_RE.sub(rf"[#\1](https://github.com/{repo}/pull/\1)", desc)
    prefix = "**BREAKING:** " if breaking else ""
    scope_str = f"**{scope}:** " if scope else ""
    return f"- {prefix}{scope_str}{desc_linked} ([`{short_sha}`](https://github.com/{repo}/commit/{full_sha}))"


def build_markdown(args, grouped) -> str:
    lines = []
    if args.extra_notes.strip():
        lines.append(args.extra_notes.strip())
        lines.append("")

    if not args.base_ref:
        lines.append("## Initial Release")
        lines.append("")
        lines.append(
            "Initial GitHub Release of **Darkbag**, an experimental computational photography "
            "camera and RAW darkroom app for Android (`arm64-v8a`)."
        )
        lines.append("")
    else:
        lines.append("## What's Changed")
        lines.append("")
        total_items = sum(len(v) for v in grouped.values())
        if total_items == 0:
            lines.append("- Maintenance and build updates.")
            lines.append("")
        else:
            for cat_key, cat_title in CATEGORIES.items():
                items = grouped[cat_key]
                if not items:
                    continue
                lines.append(f"### {cat_title}")
                for full_sha, scope, breaking, desc in items:
                    lines.append(format_md_entry(full_sha, scope, breaking, desc, args.repo))
                lines.append("")
        lines.append(
            f"**Full Changelog:** https://github.com/{args.repo}/compare/{args.base_ref}...{args.tag}"
        )
        lines.append("")

    lines.extend([
        "## Build Info",
        "",
        "| Field | Value |",
        "| :--- | :--- |",
        f"| **Version** | `{args.version}` (`{args.version_code}`) |",
        f"| **Tag** | `{args.tag}` |",
        f"| **Commit** | `{args.commit_sha[:7]}` |",
        f"| **hdr-plus** | `{args.hdrplus_sha}` |",
        f"| **Artifact** | `{args.apk_name}` |",
        f"| **SHA-256** | `{args.sha256}` |",
        "",
    ])
    return "\n".join(lines)


def build_telegram_html(args, grouped) -> str:
    release_url = f"https://github.com/{args.repo}/releases/tag/{args.tag}"
    header = [
        "#app #apk #darkbag",
        f"<b>Darkbag {html.escape(args.version)}</b> (<code>{html.escape(str(args.version_code))}</code>)",
    ]
    if args.extra_notes.strip():
        header.append("")
        header.append(html.escape(args.extra_notes.strip()))

    body = [""]
    if not args.base_ref:
        body.append("<b>Initial Release</b>")
        body.append("Experimental computational photography camera &amp; RAW darkroom (<code>arm64-v8a</code>).")
    else:
        body.append("<b>What's Changed</b>")
        total_items = sum(len(v) for v in grouped.values())
        if total_items == 0:
            body.append("• Maintenance and build updates.")
        else:
            for cat_key, cat_title in CATEGORIES.items():
                items = grouped[cat_key]
                if not items:
                    continue
                body.append(f"\n<b>{html.escape(cat_title)}</b>")
                for full_sha, scope, breaking, desc in items:
                    prefix = "<b>BREAKING:</b> " if breaking else ""
                    scope_str = f"<b>{html.escape(scope)}:</b> " if scope else ""
                    body.append(f"• {prefix}{scope_str}{html.escape(desc)} (<code>{full_sha[:7]}</code>)")

    footer = [
        "",
        f"<b>SHA-256:</b> <code>{html.escape(args.sha256)}</code>",
        f'<a href="{html.escape(release_url)}">GitHub Release ({html.escape(args.tag)})</a>',
    ]

    full_text = "\n".join(header + body + footer)
    if len(full_text) <= 3800:
        return full_text

    # Safely truncate changelog lines to stay well within Telegram's 4096-char limit
    budget = 3800 - len("\n".join(header + footer)) - 80
    kept_body = []
    used = 0
    for line in body:
        if used + len(line) + 1 > budget:
            kept_body.append("• ... (see full changelog on GitHub Release)")
            break
        kept_body.append(line)
        used += len(line) + 1
    return "\n".join(header + kept_body + footer)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--apk-name", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--hdrplus-sha", required=True)
    parser.add_argument("--commit-sha", required=True)
    parser.add_argument("--base-ref", default="")
    parser.add_argument("--head-ref", default="HEAD")
    parser.add_argument("--extra-notes", default="")
    parser.add_argument("--out-md", required=True)
    parser.add_argument("--out-tg", required=True)
    args = parser.parse_args()

    commits = collect_commits(args.base_ref.strip(), args.head_ref.strip())
    grouped = categorize_commits(commits)

    with open(args.out_md, "w", encoding="utf-8") as f:
        f.write(build_markdown(args, grouped))
    with open(args.out_tg, "w", encoding="utf-8") as f:
        f.write(build_telegram_html(args, grouped))


if __name__ == "__main__":
    main()
