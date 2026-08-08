---
name: Admin Metrics — contacts-by-channel matrix (2026-07-15, extended 2026-07-15)
description: Tenant x channel contact-count table on the metrics page; originally "today"-only inside Usage, later reattached to the shared 7/30/90-day range selector and moved into Growth & adoption
metadata:
  type: project
---

## Current state (post day-range extension)

The panel now lives inside the **"Growth & adoption"** section (`growthTitle`) of
`frontend/src/app/features/admin/pages/metrics/admin-metrics-page.component.{ts,html,scss}`,
as a sub-panel placed AFTER the existing `.panel-grid` (growth chart + top plugins) but still
inside the same `<section>`. It was moved out of "Usage today" because it no longer reflects
"today" — it now uses the SAME 7/30/90-day range selector (`selectedRange` signal) that used to
drive only the growth chart.

- Model: `ContactChannelMatrix` gained `fromDate`/`toDate: string` (ISO LocalDate) fields.
- Service: `AdminMetricsService.getContactChannelMatrix(days: number)` — now takes a required
  `days` param (7/30/90 only, backend 400/422s otherwise), sent as `HttpParams` exactly like
  `getGrowthMetrics(weeks)`.
- Component: `RangeOption` ('7'|'30'|'90') values ARE day counts directly —
  `Number(this.selectedRange())` is passed as `days`, no separate mapping table needed (unlike
  `RANGE_TO_WEEKS` used for growth's `weeks` param, a different unit).
- The private `Subject` that used to be named `growthRangeChange$` (fired only by
  `onRangeChange()` for growth) was RENAMED to `rangeChange$` and is now merged into BOTH
  `startGrowthPolling()`'s and `startChannelMatrixPolling()`'s `merge(timer, manualRefresh$,
  rangeChange$)` pipelines — one Subject driving both range-dependent sections, since
  `onRangeChange()` only needs to `.next()` once.
- UI: the sub-panel's `<h3 class="section__subtitle">` now appends a dynamic
  `({{ fromDate | date:'dd.MM' }}–{{ toDate | date:'dd.MM' }})` badge (new `.section__subtitle-range`
  CSS class: smaller, muted, inline) sourced straight from the API response — no i18n string
  needed for the date range itself since `DatePipe` (already imported) formats it directly in the
  template.
- i18n: `admin.metrics.channelMatrixEmpty` reworded in all 4 languages (pl/en/de/uk) to drop
  "dzisiaj"/"today" wording (now "w wybranym okresie" / "in the selected period" / "im gewählten
  Zeitraum" / "за обраний період"). `channelMatrixTitle` itself never said "today" so it was left
  unchanged. The "Usage today" section's OWN wording (`usageTitle` = "Wykorzystanie (dziś)") was
  deliberately left alone — it's still accurate for the section's remaining KPI cards
  (contactsHandledToday etc.), only the channel matrix moved out.

## Original build (2026-07-14/15) — channel/label conventions, still valid

New sub-panel originally inside "Usage today"; table with tenants as rows, channels (`PHONE`,
`EMAIL`, `SOCIAL_FACEBOOK`, `SOCIAL_INSTAGRAM`, `SOCIAL_WHATSAPP` — fixed order from backend) as
columns, plus a "Razem"/Total column.

Channel label mapping: `CHANNEL_LABEL_KEYS: Record<string, string>` module-level constant +
`channelLabel(channel: string)` component method calling `transloco.translate()`, fallback to the
raw backend key for unknown channels. This is the first place in the repo using
`SOCIAL_FACEBOOK`/`SOCIAL_INSTAGRAM`/`SOCIAL_WHATSAPP` as first-class channel keys (rest of the app
only has a generic `'SOCIAL'` channel) — new flat i18n keys
`admin.metrics.channelFacebook/channelInstagram/channelWhatsapp`, matching the flat-key style
already used throughout `admin.metrics.*`.

Zero-value dimming: `.channel-count--zero { color: var(--text-muted) }` /
`.channel-count--nonzero { font-weight: 600 }`, toggled via
`[class.x]="countFor(row, channel) === 0"`.

## Testing gotcha (still applies)

EVERY custom `metricsServiceMock` built ad-hoc in a spec test (not via the shared `setup()`
helper) must stub `getContactChannelMatrix` (now with a `days` arg — assertions like
`toHaveBeenCalledWith(30)` for the default '30' range, `toHaveBeenCalledWith(7)` after
`onRangeChange('7')`) since `startChannelMatrixPolling()` runs unconditionally in `ngOnInit()`.
`mockChannelMatrix` in the spec now also needs `fromDate`/`toDate` fields or `ContactChannelMatrix`
typing fails.

## Related

- [[project_admin_metrics_page_unified_polling]] — the merge(timer, manualRefresh$, rangeChange$)
  pattern; this doc's history of the Subject rename from `growthRangeChange$` to the more general
  `rangeChange$` once a second section (channel matrix) started depending on the same range.
- [[project_fe029_contacts_report]] — the contacts-report page's `channelPhone`/`channelEmail`
  wording that this section's PHONE/EMAIL labels were matched against.
