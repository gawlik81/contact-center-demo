---
name: Angular Material not installed
description: Project does NOT use Angular Material — UI is built with native HTML + SCSS only
type: project
---

Angular Material (`@angular/material`) is NOT installed in the frontend project. The installed Angular packages are only: `@angular/core`, `@angular/common`, `@angular/forms`, `@angular/router`, `@angular/platform-browser`, `@angular/compiler`, `@angular/build`, `@angular/cli`, `@angular/compiler-cli`.

**Why:** The project deliberately uses custom SCSS components instead of a component library, following the pattern established in agent-desktop, softphone, and supervisor components.

**How to apply:** Never import `@angular/material/*` packages. Use native `<input>`, `<button>`, `<select>`, `<ul>`, etc. with custom SCSS classes. Style buttons with `.btn .btn--primary/.btn--secondary/.btn--ghost` pattern. Style form fields with `.field-label` + `.field-input` pattern.
