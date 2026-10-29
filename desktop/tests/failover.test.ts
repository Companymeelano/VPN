import { describe, expect, it } from "vitest";
import { candidateOrder, FailoverTracker, failoverDelayMs } from "../src/shared/failover";

const node = (id: string) => ({ id });

describe("FailoverTracker", () => {
  it("cools a failed node, clears on success", () => {
    let t = 1000;
    const tracker = new FailoverTracker(30_000, () => t);
    expect(tracker.isCooled("a")).toBe(false);
    tracker.markFailed("a");
    expect(tracker.isCooled("a")).toBe(true);
    t += 29_000;
    expect(tracker.isCooled("a")).toBe(true);
    t += 1_500;
    expect(tracker.isCooled("a")).toBe(false); // cooldown expired — honest to retry
    tracker.markFailed("b");
    tracker.succeeded("b");
    expect(tracker.isCooled("b")).toBe(false);
  });
});

describe("candidateOrder", () => {
  it("excludes cooled nodes and ranks the rest by ping (reachable first)", () => {
    const tracker = new FailoverTracker();
    tracker.markFailed("b");
    const pings = new Map<string, number>([
      ["a", 300],
      ["c", 80],
      ["d", -1], // unreachable
    ]);
    const order = candidateOrder([node("a"), node("b"), node("c"), node("d")], pings, tracker);
    expect(order.map((n) => n.id)).toEqual(["c", "a", "d"]); // b cooled → gone
  });

  it("returns EMPTY when everything is cooled — the loop-stopper", () => {
    const tracker = new FailoverTracker();
    tracker.markFailed("a");
    tracker.markFailed("b");
    expect(candidateOrder([node("a"), node("b")], new Map(), tracker)).toEqual([]);
    expect(tracker.availableCount(["a", "b"])).toBe(0);
  });

  it("a success makes the node selectable again immediately", () => {
    const tracker = new FailoverTracker();
    tracker.markFailed("a");
    tracker.markFailed("b");
    tracker.succeeded("b");
    expect(candidateOrder([node("a"), node("b")], new Map(), tracker).map((n) => n.id)).toEqual(["b"]);
  });
});

describe("failoverDelayMs", () => {
  it("doubles from 1.5 s and caps at 20 s; garbage input clamps", () => {
    expect(failoverDelayMs(0)).toBe(1500);
    expect(failoverDelayMs(1)).toBe(1500);
    expect(failoverDelayMs(2)).toBe(3000);
    expect(failoverDelayMs(4)).toBe(12_000);
    expect(failoverDelayMs(8)).toBe(20_000);
    expect(failoverDelayMs(99)).toBe(20_000);
    expect(failoverDelayMs(-3)).toBe(1500);
  });
});
