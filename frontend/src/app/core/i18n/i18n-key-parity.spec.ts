import de from '../../../../public/i18n/de.json';
import en from '../../../../public/i18n/en.json';
import pl from '../../../../public/i18n/pl.json';
import uk from '../../../../public/i18n/uk.json';

/** Flattens nested translation objects into dot-separated leaf keys. */
function flattenKeys(node: unknown, prefix = ''): string[] {
  if (node === null || typeof node !== 'object') return [prefix];
  return Object.entries(node as Record<string, unknown>).flatMap(([key, value]) =>
    flattenKeys(value, prefix ? `${prefix}.${key}` : key),
  );
}

/**
 * Plural rule: keys ending in `_zero/_one/_two/_few/_many/_other` are compared by their ROOT
 * (suffix stripped). Languages legitimately have different plural categories (pl/uk need
 * `_few`/`_many`, en/de only `_one`/`_other`), so a language passes as long as it has the root
 * through at least one of its forms.
 */
const PLURAL_SUFFIX = /_(zero|one|two|few|many|other)$/;

function normalizeKey(key: string): string {
  return key.replace(PLURAL_SUFFIX, '');
}

/**
 * Explicit exceptions: roots deliberately missing in some language, each with a justification.
 * Intentionally empty - all four dictionaries are in full parity.
 */
const ALLOWED_MISSING: readonly { lang: string; key: string; reason: string }[] = [];

/** Returns, per language, the (normalized) keys present in any other language but missing there. */
export function findMissingKeys(
  dicts: Record<string, unknown>,
  allowed: readonly { lang: string; key: string }[] = [],
): Record<string, string[]> {
  const keySets = Object.fromEntries(
    Object.entries(dicts).map(([lang, json]) => [
      lang,
      new Set(flattenKeys(json).map(normalizeKey)),
    ]),
  );
  const allKeys = new Set(Object.values(keySets).flatMap((set) => [...set]));
  return Object.fromEntries(
    Object.keys(dicts).map((lang) => [
      lang,
      [...allKeys]
        .filter((key) => !keySets[lang].has(key))
        .filter((key) => !allowed.some((a) => a.lang === lang && a.key === key))
        .sort(),
    ]),
  );
}

describe('findMissingKeys (negative tests)', () => {
  it('reports a key present in one language and missing in another', () => {
    const result = findMissingKeys({
      pl: { a: { b: 'x', c: 'y' } },
      en: { a: { b: 'x' } },
    });
    expect(result['en']).toEqual(['a.c']);
    expect(result['pl']).toEqual([]);
  });

  it('treats plural forms as the same root across languages', () => {
    const result = findMissingKeys({
      pl: { n: { item_one: 'a', item_few: 'b', item_many: 'c', item_other: 'd' } },
      en: { n: { item_one: 'a', item_other: 'd' } },
    });
    expect(result['pl']).toEqual([]);
    expect(result['en']).toEqual([]);
  });

  it('still reports a plural root missing entirely', () => {
    const result = findMissingKeys({
      pl: { n: { item_one: 'a' } },
      en: { n: {} },
    });
    expect(result['en']).toEqual(['n.item']);
  });

  it('honours the explicit exception list', () => {
    const result = findMissingKeys({ pl: { a: 'x' }, en: {} }, [{ lang: 'en', key: 'a' }]);
    expect(result['en']).toEqual([]);
  });
});

describe('i18n key parity (pl / en / de / uk)', () => {
  const result = findMissingKeys({ pl, en, de, uk }, ALLOWED_MISSING);

  for (const lang of ['pl', 'en', 'de', 'uk']) {
    it(`${lang} has every key present in any other language`, () => {
      expect(result[lang]).toEqual([]);
    });
  }
});
