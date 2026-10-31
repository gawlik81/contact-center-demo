---
name: fe132-fe133-tabs-review
description: EPIC-32 FE-132/FE-133 (safe tab close, state markers, new contact not stealing tab) review 3/5, 2026-10-29; open majors to re-check
metadata:
  type: project
---

3/5 (2026-10-29), section appended to CR-FRONTEND.md. Open majors: (1) confirmed HANGUP closes the tab synchronously so CallLifecycleService.endedEffect finds no PHONE tab -> no ACW, agent stuck in AFTER_CONTACT (pre-existing, test enshrines it); (2) confirmClose does not re-validate state after dialog opened (tab became WRAPPING); (3) Delete without event.repeat cascades returns; (4) focus moved after async abandon success steals focus from editor; plus newContactEffect announces existing tabs on remount (knownTabIds starts empty), 404/409 on abandon leaves undismissable tab, RINGING tab x calls hangupCall (no reject), axe aria-required-children risk with x inside tablist.

**How to apply:** when reviewing FE-123/FE-135 or follow-ups in agent-desktop tabs, re-check whether these were fixed; recurring pattern: async-completion focus moves and effects keyed on "new since last run" with empty initial state.
