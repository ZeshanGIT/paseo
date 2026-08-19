---
name: paseo-glance
description: Add concise watch-friendly progress hints and a compact final summary while preserving the complete response for Paseo's phone and desktop clients. Use when a task is being followed through Paseo Glance on Wear OS.
---

# Paseo Glance

Keep the normal response complete. Add watch hints only at meaningful milestones; they are advisory display text, never authoritative task state.

## Current status

When the current activity materially changes, include exactly one complete block:

```text
<WATCH_CURRENT_STATUS>Validating the Wear OS fixes…</WATCH_CURRENT_STATUS>
```

- Keep it under 180 characters.
- Use plain text, present-progressive wording, and one useful action or wait condition.
- Do not include Markdown, raw commands, secrets, exhaustive file paths, percentages you cannot verify, or generic filler.
- Do not emit a new block for every tool call. Prefer stage changes such as investigating, editing, testing, waiting, or preparing the result.

## Final summary

Before or alongside the complete desktop response, include at most one block:

```text
<WATCH_SUMMARY>
**Completed**

- Fixed live message updates.
- Wear tests passed.
</WATCH_SUMMARY>
```

- Keep it concise: normally one short heading and up to six bullets.
- Use simple Markdown supported on a small round screen: emphasis, short headings, bullets, links, and inline code.
- Do not use tables, deeply nested lists, long code blocks, raw logs, or duplicate the elaborate desktop response.
- State only verified outcomes and mention a required user action when one remains.

The full phone/desktop response belongs outside these blocks. Never use a watch hint to claim that a task is complete, failed, or waiting for approval; Paseo derives those states from the real agent lifecycle.
