/**
 * Deterministic PRNG (mulberry32). The corpus is generated in the browser on
 * every load, so it has to be reproducible: the same seed must produce the same
 * 10,000 messages, otherwise message IDs quoted in a demo stop resolving.
 */
export class SeededRandom {
  private state: number;

  constructor(seed = 42) {
    this.state = seed >>> 0;
  }

  next(): number {
    this.state = (this.state + 0x6d2b79f5) >>> 0;
    let t = this.state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }

  int(minInclusive: number, maxExclusive: number): number {
    return minInclusive + Math.floor(this.next() * (maxExclusive - minInclusive));
  }

  pick<T>(items: readonly T[]): T {
    return items[this.int(0, items.length)];
  }

  /** `count` distinct items, or all of them when count exceeds the pool. */
  sample<T>(items: readonly T[], count: number): T[] {
    if (count >= items.length) {
      return [...items];
    }
    const pool = [...items];
    const picked: T[] = [];
    for (let i = 0; i < count; i++) {
      picked.push(...pool.splice(this.int(0, pool.length), 1));
    }
    return picked;
  }

  bool(trueProbability: number): boolean {
    return this.next() < trueProbability;
  }

  /** A hex string of `length` characters; stands in for digests and IDs. */
  hex(length: number): string {
    let out = '';
    while (out.length < length) {
      out += Math.floor(this.next() * 0xffffffff)
        .toString(16)
        .padStart(8, '0');
    }
    return out.slice(0, length);
  }
}
