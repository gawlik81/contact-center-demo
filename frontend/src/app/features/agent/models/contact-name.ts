/**
 * Display name of a contact. Backend sources sometimes hand over a raw mail address header
 * (`Anna Test <anna@example.test>`, `"Anna Test" <anna@example.test>`, `<anna@example.test>`);
 * the desk shows the display name when there is one, else the bare address, else the text as is.
 */
export function normalizeContactName(raw: string | null | undefined): string {
  const text = (raw ?? '').trim();
  const match = /^(.*?)\s*<([^<>]+)>$/.exec(text);
  if (!match) return text;
  const display = match[1]
    .trim()
    .replace(/^"(.*)"$/, '$1')
    .trim();
  return display || match[2].trim();
}
