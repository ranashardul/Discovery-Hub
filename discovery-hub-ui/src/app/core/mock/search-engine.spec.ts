import { buildSnippet, highlight, parseQuery } from './search-engine';

describe('parseQuery', () => {
  it('keeps quoted phrases together', () => {
    expect(parseQuery('"preservation notice" restricted')).toEqual([
      'preservation notice',
      'restricted',
    ]);
  });

  it('drops stop words and single characters', () => {
    expect(parseQuery('the a x hold')).toEqual(['hold']);
  });
});

describe('highlight', () => {
  it('wraps matches case-insensitively', () => {
    expect(highlight('Restricted list update', ['restricted'])).toBe(
      '<mark>Restricted</mark> list update',
    );
  });

  it('escapes markup in the source text before highlighting', () => {
    const result = highlight('<script>alert(1)</script> hold', ['hold']);
    expect(result).not.toContain('<script>');
    expect(result).toContain('&lt;script&gt;');
    expect(result).toContain('<mark>hold</mark>');
  });

  it('returns escaped text when there are no terms', () => {
    expect(highlight('a & b', [])).toBe('a &amp; b');
  });
});

describe('buildSnippet', () => {
  it('centres the window on the first match', () => {
    const body = `${'filler '.repeat(60)}preservation notice${' tail'.repeat(60)}`;
    const snippet = buildSnippet(body, ['preservation']);

    expect(snippet).toContain('<mark>preservation</mark>');
    expect(snippet.startsWith('…')).toBe(true);
    expect(snippet.length).toBeLessThan(body.length);
  });

  it('falls back to the head of the body when nothing matches', () => {
    expect(buildSnippet('short body', ['absent'])).toBe('short body');
  });
});
