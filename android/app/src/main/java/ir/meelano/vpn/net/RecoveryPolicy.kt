package ir.meelano.vpn.net

/**
 * The decision table behind "the network moved - now what". Pure Kotlin, no Android, no coroutines:
 * every row below is a unit test in app/src/test, because this is the exact layer where the 2.6.0
 * shipped code lost events and froze tunnels (see docs/ANTI-BLOCK.md and the three bugs this file
 * was extracted for):
 *
 *  1. a *restoration* after total loss was dropped (`oldPrimary == null` early-returned), so an app
 *     that burned all candidates while the radio was down stayed down until the 15-minute watchdog;
 *  2. a transient loss knocked the phase to Handshake(0.2f) and nothing ever published Connected
 *     again, so the UI froze on "در حال اتصال", isRunning() lied, and the watchdog restarted a
 *     tunnel that was working;
 *  3. the seconds-level retry loop (KeepAlive.retryWithBackoff) had no caller at all.
 *
 * Keeping the decisions here - and only the effects in MeelanoVpnService / VpnOrchestrator - is what
 * makes "why did the app not reconnect" answerable by reading one table instead of replaying binder
 * callbacks. The service owns one boolean ([MeelanoVpnService] `networkOutage`): the *marker* that a
 * total loss happened under a live tunnel, consumed exactly once by the restore that follows it.
 */
object RecoveryPolicy {

    /** What changed underneath the tunnel. */
    enum class Underlay {
        /** Same primary (or a callback about a network we do not track): nothing to do. */
        UNCHANGED,
        /** The last physical network is gone. */
        LOST,
        /** A physical network appeared after a period with none. */
        RESTORED,
        /** A different network became primary while at least one other remains. */
        SWITCHED,
    }

    /**
     * Compares the *identity* of the primary network before and after the callback. `Network`
     * equality is identity, which is exactly the question ("did the path move?"); the service passes
     * the two references straight in.
     */
    fun <T> classify(oldPrimary: T?, newPrimary: T?): Underlay = when {
        oldPrimary == newPrimary -> Underlay.UNCHANGED
        newPrimary == null -> Underlay.LOST
        oldPrimary == null -> Underlay.RESTORED
        else -> Underlay.SWITCHED
    }

    enum class Action {
        /** Nothing must change: an in-flight connect self-heals, or the health monitor owns the proof. */
        NONE,
        /** Total loss under a live tunnel: mark the stall and wait for proofs. */
        MARK_OUTAGE,
        /** The live tunnel's sockets are bound to a dead path: re-dial now (debounced). */
        RECONNECT_CHANGED,
        /** The network is back under a stalled or failed tunnel: re-dial now (debounced). */
        RECONNECT_RESTORED,
    }

    /**
     * @param event           what changed underneath (see [classify])
     * @param phaseConnected  the phase currently says Connected
     * @param recoverable     the phase is Failed/Idle - a state that will not heal by itself
     * @param outageMarked    a total loss happened under a live tunnel and its restore was not yet
     *                        consumed (the Handshake(0.2f) stall the health monitor may still heal)
     */
    fun onUnderlayEvent(
        event: Underlay,
        phaseConnected: Boolean,
        recoverable: Boolean,
        outageMarked: Boolean,
    ): Action = when {
        event == Underlay.UNCHANGED -> Action.NONE
        event == Underlay.LOST -> if (phaseConnected) Action.MARK_OUTAGE else Action.NONE
        // A primary exists again from here (RESTORED or SWITCHED).
        //
        // Connected + SWITCHED: the path moved under live sockets; protect() cannot re-bind them, so
        //   re-dial through the orchestrator (its generation guard supersedes any monitor attempt).
        // Connected + RESTORED: either the flap preserved the sockets (a re-dial would tear down a
        //   working tunnel for nothing) or it did not (the health monitor proves that within ~15 s
        //   and failovers). Reconnecting here is how two cores/two TUNs are born.
        phaseConnected -> if (event == Underlay.SWITCHED) Action.RECONNECT_CHANGED else Action.NONE
        // Not connected: a stalled restore (outage marker) or a dead end (Failed/Idle) must re-dial.
        // A connect already in flight (WritingProfile..Verifying) is deliberately left alone - it
        // walks its own candidate ladder and superseding it discards a race that may be winning.
        recoverable || outageMarked -> Action.RECONNECT_RESTORED
        else -> Action.NONE
    }

    /**
     * Health monitor: once a proof succeeds again, the tunnel must *say* so. A stale non-Connected
     * phase (the Handshake(0.2f) outage marker) freezes the UI, makes isRunning() false and lets the
     * watchdog restart a working tunnel. The generation check keeps a superseded monitor from
     * overwriting the phase of a newer connect that is already writing its profile.
     */
    fun shouldRepublishConnected(generationMatches: Boolean, phaseConnected: Boolean): Boolean =
        generationMatches && !phaseConnected

    /**
     * Which Failed reasons deserve a seconds-level retry loop (KeepAlive.retryWithBackoff).
     * "all_candidates_failed:*", "tunnel_error" and friends are weather - retry now. Consent,
     * a missing profile and a core-less build are not weather: retrying only spams the same
     * deterministic failure.
     */
    fun isRetryableFailure(reason: String): Boolean = when (reason) {
        "permission_required",
        "profile_missing",
        "no_candidates",
        "no_supported_candidates",
        "core_not_linked" -> false
        else -> true
    }
}
