/**
 * True when the element accepts typed text (or a selection of options), i.e. a keystroke such
 * as Space / Enter / "?" should reach it instead of triggering a global action.
 */
export function isEditableElement(el: EventTarget | Element | null | undefined): boolean {
  if (!(el instanceof HTMLElement)) return false;
  if (el.isContentEditable) return true;
  const tag = el.tagName;
  if (tag === 'TEXTAREA' || tag === 'SELECT') return true;
  if (tag === 'INPUT') {
    const type = (el as HTMLInputElement).type;
    // Buttons, checkboxes and radios do not consume typed characters.
    return !['button', 'submit', 'reset', 'checkbox', 'radio', 'image', 'file', 'range'].includes(
      type,
    );
  }
  return false;
}

/**
 * Remembers the element that currently has focus (the opener of a dialog) and returns a function
 * that gives focus back to it. A native `<dialog>` restores focus on its own only while the
 * element survives; when the parent destroys the component right after closing, focus would
 * otherwise fall back to `<body>`.
 */
export function rememberFocus(): () => void {
  const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  return () => {
    if (opener && opener.isConnected) opener.focus();
  };
}
