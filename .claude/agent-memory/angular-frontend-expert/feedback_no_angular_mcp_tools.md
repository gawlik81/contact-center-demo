---
name: feedback_no_angular_mcp_tools
description: angular-mcp MCP server tools referenced in persona instructions are not installed in this environment — use Read/Edit/Write/Bash directly instead
metadata:
  type: feedback
---

The persona system prompt says to use "angular-mcp MCP server tools" for all UI build/modification
tasks and never write Angular code manually when they're available. In this project's actual
environment, no such MCP server is registered — `ToolSearch` for "angular-mcp" returns zero
matches, and no `mcp__angular*` tools appear in the deferred tools list.

**Why:** Verified by searching deferred tools before starting FE-109 (frontend/tenant.model.ts
cleanup, 2026-08-13). No angular-mcp tools exist to load.

**How to apply:** Do not spend time repeatedly searching for angular-mcp tools on every task —
one check per session is enough. Proceed directly with the standard Read/Edit/Write/Bash tools for
all Angular component/service/model work in this repo. This does not relax any other project rule
(standalone components only, `signal()`/`computed()` state, etc. — those still apply per
`/home/pawelm/contact-center/CLAUDE.md`), it only means the file-editing mechanism is the normal
one, not an MCP tool.
