---
name: project_fe_adhoc_email_wysiwyg
description: AdHocEmailModalComponent body field przerobiony z textarea na contenteditable WYSIWYG (2026-06-17)
metadata:
  type: project
---

Naprawiono bug w `AdHocEmailModalComponent` (`frontend/src/app/features/agent/pages/customers/adhoc-email-modal/`):
pole "Treść wiadomości" było zwykłym `<textarea formControlName="bodyHtml">`, więc po wczytaniu szablonu agent widział
surowy `<!DOCTYPE html>...` jako tekst. Przerobione na contenteditable WYSIWYG identyczny ze wzorcem
[[project_fe012_email_attachments]] (`EmailContactComponent` w `agent-desktop/email-contact/`).

**Co zmieniono:**
- `bodyHtml` usunięty z reactive form (`FormGroup`), zastąpiony przez `signal<string>('')` (`this.bodyHtml`) — wzorowane
  1:1 na `EmailContactComponent.replyHtml`.
- Dodano `editorRef = viewChild<ElementRef<HTMLDivElement>>('editor')`, `bodyHtmlTouched` signal (zamiast
  `form.controls.bodyHtml.touched`, bo to już nie FormControl).
- Metody: `setEditorHtml()` (ustawia `innerHTML` programowo, używana w `onTemplateChange`/`applyTemplate`),
  `onEditorInput()`, `onEditorBlur()` (ustawia touched), `execCommand()`, `insertLink()` — identyczne z
  `EmailContactComponent`.
- HTML: `<div #editor contenteditable="true" id="email-bodyHtml" class="editor-content" ...>` + toolbar
  (Bold/Italic/Underline/Insert Link) skopiowany 1:1 z `email-contact.component.html` (klasy `editor-toolbar`,
  `editor-btn`, `editor-separator`, i18n keys `agent.emailContact.*` — reużyte, nie duplikowane pod `adhocEmail`).
- SCSS: dodano `.editor-wrapper`, `.editor-toolbar`, `.editor-content`, `.editor-btn`, `.editor-separator`
  skopiowane ze stylu `email-contact.component.scss` ale z lokalnymi zmiennymi `$ease-standard` już istniejącymi
  w pliku adhoc-email-modal.

**Gotcha:** `@angular-eslint/template/label-has-associated-control` wymaga `<label for="email-bodyHtml">` mimo że
target to `<div contenteditable>`, nie natywny form control — działa, bo `id` matchuje `for`. Próba użycia
`aria-labelledby` na divie + `id` na labelu (bez `for`) nie satysfakcjonuje tej reguły lint.

Zobacz też [[project_fe012_email_contact]] dla pełnego kontekstu wzorcowego edytora.
