/**
 * Failover policy — the desktop twin of the Android cooldown logic (VpnOrchestrator.cooledUntil).
 *
 * Why this is its own tested module: the infinite A↔B reconnect loop (a node dies, we try the
 * other, it dies, we try the first again — every ~1 s, forever) is the exact failure mode that
 * eats CPUs during a blackout. The rules, in one place:
 *
 *  1. a node that just failed is "cooled" for [cooldownMs] and is skipped by every chooser;
 *  2. a success erases the node's history;
 *  3. when EVERYTHING is cooled there is no honest "next" — the chooser returns nothing and the
 *     caller surfaces a human-actionable error instead of silently burning battery. (Android's
 *     `ifEmpty { distinct }` fallback exists for a service that must self-heal forever; on the
 *     desktop the user is one click away and a full stop is the truthful answer.)
 */

export class FailoverTracker {
  private readonly cooledUntil = new Map<string, number>();

  constructor(
    private readonly cooldownMs = 30_000,
    private readonly now: () => number = Date.now,
  ) {}

  markFailed(id: string): void {
    this.cooledUntil.set(id, this.now() + this.cooldownMs);
  }

  succeeded(id: string): void {
    this.cooledUntil.delete(id);
  }

  isCooled(id: string): boolean {
    return (this.cooledUntil.get(id) ?? 0) > this.now();
  }

  /** For tests and diagnostics: how many of [ids] can still be tried right now. */
  availableCount(ids: readonly string[]): number {
    return ids.filter((id) => !this.isCooled(id)).length;
  }
}

/**
 * Attempt order for a connect: cooled nodes removed, remainder ranked by ping
 * (reachable first, then fastest). Empty array ⇔ everything is cooled — stop, do not loop.
 */
export function candidateOrder<T extends { id: string }>(
  nodes: readonly T[],
  pings: ReadonlyMap<string, number>,
  tracker: FailoverTracker,
): T[] {
  const warm = nodes.filter((n) => !tracker.isCooled(n.id));
  if (warm.length === 0) return [];
  return [...warm].sort((a, b) => {
    const pa = pings.get(a.id);
    const pb = pings.get(b.id);
    const aOk = pa !== undefined && pa >= 0;
    const bOk = pb !== undefined && pb >= 0;
    if (aOk !== bOk) return aOk ? -1 : 1;
    if (aOk && bOk && pa !== pb) return (pa as number) - (pb as number);
    return 0;
  });
}

/**
 * Backoff for *automatic* failover reconnects: 1.5 s, 3 s, 6 s, … capped at 20 s. The first
 * retry is prompt (a blip deserves a fast recovery), later ones are patient (a blackout
 * deserves patience, not a reconnect storm).
 */
export function failoverDelayMs(consecutiveFailures: number): number {
  const n = Math.max(1, Math.min(consecutiveFailures, 8));
  return Math.min(1500 * 2 ** (n - 1), 20_000);
}
