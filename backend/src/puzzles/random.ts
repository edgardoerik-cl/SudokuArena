import { createHash } from "node:crypto";

/** PRNG reproducible: la semilla permite auditar/repetir una arena sin publicar su solución. */
export class SeededRandom {
  private state: number;

  constructor(seed: string) {
    this.state = createHash("sha256").update(seed).digest().readUInt32LE(0) || 0x9e3779b9;
  }

  next(): number {
    let value = this.state;
    value ^= value << 13;
    value ^= value >>> 17;
    value ^= value << 5;
    this.state = value >>> 0;
    return this.state / 0x1_0000_0000;
  }

  int(minimum: number, maximum: number): number {
    return minimum + Math.floor(this.next() * (maximum - minimum + 1));
  }

  pick<T>(values: readonly T[]): T {
    return values[this.int(0, values.length - 1)]!;
  }

  shuffle<T>(values: readonly T[]): T[] {
    const copy = [...values];
    for (let index = copy.length - 1; index > 0; index -= 1) {
      const target = this.int(0, index);
      [copy[index], copy[target]] = [copy[target]!, copy[index]!];
    }
    return copy;
  }
}
