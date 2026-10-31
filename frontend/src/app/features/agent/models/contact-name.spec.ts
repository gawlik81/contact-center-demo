import { normalizeContactName } from './contact-name';

describe('normalizeContactName', () => {
  it.each([
    ['Anna Test <anna@example.test>', 'Anna Test'],
    ['"Anna Test" <anna@example.test>', 'Anna Test'],
    ['<anna@example.test>', 'anna@example.test'],
    ['anna@example.test', 'anna@example.test'],
    ['Jan Kowalski', 'Jan Kowalski'],
    ['  Jan  ', 'Jan'],
    ['', ''],
  ])('%s -> %s', (raw, expected) => {
    expect(normalizeContactName(raw)).toBe(expected);
  });

  it('handles null and undefined', () => {
    expect(normalizeContactName(null)).toBe('');
    expect(normalizeContactName(undefined)).toBe('');
  });
});
