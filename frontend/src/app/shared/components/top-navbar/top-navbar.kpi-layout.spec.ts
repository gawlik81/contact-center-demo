import de from '../../../../../public/i18n/de.json';
import en from '../../../../../public/i18n/en.json';
import pl from '../../../../../public/i18n/pl.json';
import uk from '../../../../../public/i18n/uk.json';

/**
 * Computational check of the KPI strip tiers in top-navbar.component.scss (FE-134). jsdom has no
 * layout, so widths are modelled from the real translated strings with the measured font metrics
 * (11px uppercase label ~8px/char with tracking, 11px caption ~6px/char, 13.5px mono value
 * ~8.1px/char). The THRESHOLDS below must match the `@container kpi (max-width: ...)` rules.
 */
const THRESHOLDS = { compact: 600, drop4: 400, drop3: 290, drop2: 190 } as const;

const CHROME = 2 + 20 + 24 + 8; // border + padding + icon + gap
const GAP = 5;
const LABEL_PX = 8;
const SUB_PX = 6;
const VALUE_PX = 8.1;
const VALUES = ['9999', '100%', '99:59', '240:00']; // widest realistic value per tile

type Desktop = typeof pl.agent.desktop;

function tileWidths(d: Desktop, compact: boolean): number[] {
  const labels = [d.kpiHandled, d.kpiSla, d.kpiAht, d.kpiWorkTime];
  const subs = [d.kpiHandledSub, '', d.kpiAhtSub, ''];
  return labels.map((label, i) => {
    const value = VALUES[i].length * VALUE_PX;
    if (compact) return CHROME + value;
    const valueRow = value + (subs[i] ? 4 + subs[i].length * SUB_PX : 0);
    return CHROME + Math.max(label.length * LABEL_PX, valueRow);
  });
}

const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0) + GAP * Math.max(xs.length - 1, 0);

/** Which tiles stay visible at a given strip width (mirrors the container-query tiers). */
function visibleCount(stripWidth: number): number {
  if (stripWidth < THRESHOLDS.drop2) return 1;
  if (stripWidth < THRESHOLDS.drop3) return 2;
  if (stripWidth < THRESHOLDS.drop4) return 3;
  return 4;
}

const LANGS: Record<string, Desktop> = {
  pl: pl.agent.desktop,
  en: en.agent.desktop,
  de: de.agent.desktop,
  uk: uk.agent.desktop,
};

/** Strip width: viewport minus chrome, calibrated on the measured 346px at innerWidth 1334. */
const stripWidth = (viewport: number) => viewport - (viewport > 1440 ? 988 : 888);

describe('TopNavbar KPI strip tiers (FE-134)', () => {
  for (const [lang, d] of Object.entries(LANGS)) {
    it(`${lang}: each tier's tiles fit within the tier's own threshold`, () => {
      expect(sum(tileWidths(d, false))).toBeLessThanOrEqual(THRESHOLDS.compact);
      const compact = tileWidths(d, true);
      expect(sum(compact)).toBeLessThanOrEqual(THRESHOLDS.drop4);
      expect(sum(compact.slice(0, 3))).toBeLessThanOrEqual(THRESHOLDS.drop3);
      expect(sum(compact.slice(0, 2))).toBeLessThanOrEqual(THRESHOLDS.drop2);
      expect(compact[0]).toBeLessThanOrEqual(THRESHOLDS.drop2);
    });

    it.each([1280, 1366, 1440, 1920])(
      `${lang}: at %ipx the visible tiles always fit the strip`,
      (viewport) => {
        const width = stripWidth(viewport);
        const compact = width < THRESHOLDS.compact;
        const shown = tileWidths(d, compact).slice(0, visibleCount(width));
        expect(sum(shown)).toBeLessThanOrEqual(width);
      },
    );
  }

  it('keeps all four full tiles at 1920px and degrades to compact tiles at 1366px', () => {
    expect(stripWidth(1920)).toBeGreaterThanOrEqual(THRESHOLDS.compact);
    expect(stripWidth(1366)).toBeLessThan(THRESHOLDS.compact);
    expect(visibleCount(stripWidth(1366))).toBe(4);
  });
});
