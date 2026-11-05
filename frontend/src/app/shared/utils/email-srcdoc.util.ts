import { SecurityContext } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';

export interface EmailSrcdocOptions {
  /**
   * Adds a Content-Security-Policy meta that blocks every remote resource (images, fonts,
   * stylesheets, frames, forms). Only inline styles and data: images remain allowed.
   * Off by default so existing callers keep their exact output.
   */
  blockRemoteResources?: boolean;
  /**
   * Injects `<base target="_blank">` so link clicks open a new window instead of navigating the
   * frame itself. Needs `allow-popups` (never allow-popups-to-escape-sandbox) on the iframe.
   */
  linksInNewTab?: boolean;
}

export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const CSP_META =
  `<meta http-equiv="Content-Security-Policy" ` +
  `content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">`;
// base-uri restricts the href of a <base> element; <base target> (no href) is not affected.
const BASE_TARGET = '<base target="_blank">';

/**
 * Builds the HTML document rendered inside a sandboxed e-mail iframe (`[srcdoc]`).
 * The caller is responsible for the sandbox attribute (never allow-scripts) and, when it passes
 * `html`, for the trust level of that markup.
 */
export function buildEmailSrcdoc(
  html: string | null | undefined,
  text: string | null | undefined,
  noContentLabel: string,
  options: EmailSrcdocOptions = {},
): string {
  const content =
    html ||
    (text
      ? `<pre style="white-space:pre-wrap;word-break:break-word">${escapeHtml(text)}</pre>`
      : `<p style="color:#64748b;font-style:italic">${escapeHtml(noContentLabel)}</p>`);
  const csp = options.blockRemoteResources ? CSP_META : '';
  const base = options.linksInNewTab ? BASE_TARGET : '';
  return `<!DOCTYPE html><html><head><meta charset="utf-8">${csp}${base}
<style>
  * { box-sizing: border-box; }
  body { margin: 0; padding: 8px 12px; font-family: sans-serif; font-size: 14px; line-height: 1.5; color: #1e293b; word-break: break-word; }
  img { max-width: 100%; height: auto; }
  a { color: #1a56db; }
  blockquote { border-left: 3px solid #cbd5e1; margin: 0; padding-left: 1em; color: #64748b; }
</style>
</head><body>${content}</body></html>`;
}

/**
 * Builds the srcdoc for UNTRUSTED inbound e-mail HTML, ready to bind to `[srcdoc]` of an iframe
 * with `sandbox="allow-same-origin allow-popups"` (no allow-scripts):
 *  1. the body goes through Angular's HTML sanitizer (scripts, on* handlers, javascript: URLs);
 *  2. every link is forced to `target="_blank"` with `rel="noopener noreferrer nofollow"` (the
 *     sanitizer keeps `target`, so `_self` would navigate the frame itself) - done on an inert
 *     DOMParser document, which neither runs scripts nor loads resources;
 *  3. the document carries a CSP blocking all remote resources (tracking pixels).
 * The result is trusted only because of those three layers plus the sandbox.
 *
 * Sandbox trade-off (decision for the PO): the iframe uses `allow-popups` WITHOUT
 * `allow-popups-to-escape-sandbox`, so a page opened from a link inherits the sandbox and runs
 * without scripts (login pages / SPAs may look broken). Adding the escape flag would give those
 * pages full capabilities, so it is deliberately not set.
 */
export function buildSafeEmailSrcdoc(
  sanitizer: DomSanitizer,
  html: string | null | undefined,
  text: string | null | undefined,
  noContentLabel: string,
): SafeHtml {
  let safeBody: string | null = null;
  if (html) {
    const sanitized = sanitizer.sanitize(SecurityContext.HTML, html) ?? '';
    const body = new DOMParser().parseFromString(sanitized, 'text/html').body;
    body.querySelectorAll('a, area').forEach((el) => {
      el.setAttribute('target', '_blank');
      el.setAttribute('rel', 'noopener noreferrer nofollow');
    });
    safeBody = body.innerHTML;
  }
  const doc = buildEmailSrcdoc(safeBody, text, noContentLabel, {
    blockRemoteResources: true,
    linksInNewTab: true,
  });
  return sanitizer.bypassSecurityTrustHtml(doc);
}
