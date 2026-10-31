---
name: fe122-acw-inline
description: FE-122 ACW as non-modal PHONE tab content; DispositionSaveService owns save lifecycle; cc-acw-banner in agent shell
metadata:
  type: project
---

DispositionPanelComponent is an inline `<section>` rendered by AgentDesktop in the PHONE tab panel when `tab.status === 'WRAPPING'` (instead of app-softphone). Save lives in root `DispositionSaveService` (toast, AVAILABLE, closeTab, `closed$` for focus repair) because the form is destroyed on every tab switch and `takeUntilDestroyed` / output emit after destroy would drop the save. Draft in `ContactDraftStore.dispositionDraft` (not in draftContactIds). ACW timer derived from `ContactTab.wrappingSince`. Focus requests via `ContactTabStore.requestAcwFocus/consumeAcwFocus`. `cc-acw-banner` (agent-shell) is a polite status + button, never focuses. [[fe131-draft-store]]

**Why:** non-modal ACW so agent can work chats during ACW. **How to apply:** do not reintroduce showModal/dialog; keep save outside the component.
