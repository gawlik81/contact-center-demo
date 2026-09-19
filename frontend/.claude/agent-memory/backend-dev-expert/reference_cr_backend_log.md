---
name: reference_cr_backend_log
description: Backend code review history for this project lives in /home/pawelm/contact-center/CR-BACKEND.md as a single append-only, dated log file (newest entries at the end).
type: reference
---

`senior-code-reviewer` writes all backend review reports into `/home/pawelm/contact-center/CR-BACKEND.md` — one dated `## Review: ...` section per review, appended at the end of the file (never edited retroactively). Sections are large; when asked to act on "the review from <date>", jump straight to that section (`grep -n "^## " CR-BACKEND.md` to find the offset, then read from there) rather than reading the whole file.

**How to apply:** when picking up a fix/follow-up task referencing a code review, read the specific dated section in `CR-BACKEND.md` first for exact file/class/line references before touching any code — reviews cite precise locations and prior history (e.g. a module's earlier review + what was already fixed since), which is essential context not otherwise available from the diff alone.
