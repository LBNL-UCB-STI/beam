package beam.router.r5;

import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.profile.DominatingList;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * An implementation of DominatingList, retaining pareto-optimal paths on time and fare.
 */
public class BeamDominatingList implements DominatingList {
    // Defensive upper bound: transit back-chains should be far shorter than this.
    private static final int MAX_BACK_CHAIN_HOPS = 10000;
    private static final int MAX_CHAIN_SUMMARY_HOPS = 15;
    private static final int LOG_FIRST_N_FAILURES = 5;
    private static final int LOG_EVERY_N_FAILURES = 1000;
    private static final boolean LOG_MALFORMED_CHAIN_DETAILS =
        Boolean.parseBoolean(System.getProperty("beam.r5.logMalformedChainDetails", "false"));
    private static final Logger LOG = LoggerFactory.getLogger(BeamDominatingList.class);
    private static final AtomicLong INVALID_BACK_CHAIN_COUNT = new AtomicLong(0);
    private static final AtomicLong FARE_CALC_EXCEPTION_COUNT = new AtomicLong(0);
    private static final AtomicLong DROPPED_MALFORMED_STATE_COUNT = new AtomicLong(0);

    private int maxFare;
    private int maxClockTime;
    private InRoutingFareCalculator fareCalculator;

    private final ArrayList<McRaptorSuboptimalPathProfileRouter.McRaptorState> states = new ArrayList<>(10);

    public BeamDominatingList(InRoutingFareCalculator fareCalculator, int maxFare, int maxClockTime) {
        this.fareCalculator = fareCalculator;
        this.maxFare = maxFare;
        this.maxClockTime = maxClockTime;
    }

    /**
     * Return true if there is no way that a route with dominator as a prefix can yield a route that is slower or more
     * expensive than the same route with dominatee as a prefix.
     */
    private boolean betterOrEqual(McRaptorSuboptimalPathProfileRouter.McRaptorState dominator, McRaptorSuboptimalPathProfileRouter.McRaptorState dominatee) {
        if (dominator.fare == null || dominatee.fare == null) {
            return false;
        }
        // FIXME add check for nonnegative
        boolean sameAccessMode = dominator.accessMode == dominatee.accessMode;
        boolean sameEgressMode = dominator.egressMode == dominatee.egressMode;
        if (!sameAccessMode || !sameEgressMode) {
            return false;
        }

        int dominateeConsumedValue = dominatee.fare.cumulativeFarePaid - dominatee.fare.transferAllowance.value;
        if (dominator.time <= dominatee.time) {
            // this route is as good or better on time
            if (dominator.fare.cumulativeFarePaid <= dominateeConsumedValue) {
                // This route is as fast as the alternate route, and it costs no more than the fare paid for the other route
                // minus any transfer priviliges that the user gets from the other route that could be realized in the future.
                return true;
            }

            // if the out of pocket cost is the same or less and the transfer privilege is as good as or better than the
            // other transfer allowance (exact definition depends on the system, see javadoc), then there is no way that
            // dominatee could yield a better fare than dominator.
            if (dominator.fare.cumulativeFarePaid <= dominatee.fare.cumulativeFarePaid &&
                    dominator.fare.transferAllowance.atLeastAsGoodForAllFutureRedemptions(dominatee.fare.transferAllowance)) {
                return true;
            }
        }

        // if we have not returned true by now, there may be a way that the dominatee can yield a faster or cheaper route
        return false;
    }

    @Override
    public boolean add(McRaptorSuboptimalPathProfileRouter.McRaptorState newState, Consumer<McRaptorSuboptimalPathProfileRouter.McRaptorState> evictionCallback) {
        // if it is past the time limit, drop it
        if (newState.time > maxClockTime) return false;

        // calculate fare if it has not been calculated before
        // this is not the best place to do this, as there are two FareDominatingLists per stop (for best and nontransfer
        // states), but it works.
        if (newState.fare == null) {
            if (fareCalculator == null) return false;
            if (newState.back != null && newState.back.fare == null && hasInvalidBackChain(newState)) {
                long invalid = INVALID_BACK_CHAIN_COUNT.incrementAndGet();
                long dropped = DROPPED_MALFORMED_STATE_COUNT.incrementAndGet();
                maybeLogMalformedState(
                    "invalid-back-chain",
                    invalid,
                    dropped,
                    newState,
                    null
                );
                return false;
            }
            try {
                newState.fare = fareCalculator.calculateFare(newState, maxClockTime);
            } catch (RuntimeException e) {
                // Drop malformed states (e.g., cyclic/corrupted back-chains) instead of stalling/failing the route.
                long fareEx = FARE_CALC_EXCEPTION_COUNT.incrementAndGet();
                long dropped = DROPPED_MALFORMED_STATE_COUNT.incrementAndGet();
                maybeLogMalformedState(
                    "fare-calc-exception",
                    fareEx,
                    dropped,
                    newState,
                    e
                );
                return false;
            }
            if (newState.fare == null) return false;
        }

        // Prune if the fare paid _minus the transfer privilege_ exceeds the max fare, for efficient calculation.
        // This is in order to support subway systems where the cumulative fare paid may actually go _down_ after an
        // additional ride.

        // For instance, in the San Francisco Bay Area Rapid Transit (BART) system, the fare to travel from the San
        // Francisco International Airport (SFO) to San Bruno is $7.85 (using a Clipper contactless smartcard). But if the
        // user then crosses the platform and boards a Millbrae-bound train and exits at Millbrae, their total fare will
        // be only $4.55 (i.e. riding another transit vehicle will actually reduce the fare). This is an optimal trip
        // at some times of day when direct SFO-Millbrae service is not running. If we cut off the search when cumulativeFarePaid
        // exceeded, say, $5, we'd prevent this trip. But if cumulativeFarePaid is set to $7.85 when alighting at San
        // Bruno, and transferAllowance.value is set to $7.85 - $4.55 = $3.30, we will retain it properly.
        if (newState.fare.cumulativeFarePaid - newState.fare.transferAllowance.value > maxFare) return false;

        for (Iterator<McRaptorSuboptimalPathProfileRouter.McRaptorState> it = states.iterator(); it.hasNext(); ) {
            McRaptorSuboptimalPathProfileRouter.McRaptorState existing = it.next();


            // Check first if the existing state is better than or equal to the new state. We check the existing state
            // vs the new state before doing the opposite, because two states may be equal (for instance, in Boston,
            // a trip from the Conveyal office at Mass Ave and Newbury to Alewife using CT1 -> Red and 1 -> Red are
            // equal if they both get you on the same red line train - they have the same time, and the same fare situation
            // (both leave you coming off the subway with a 2.25 fare privilige that can be used on any mode that has
            // discounted transfer). We prefer to save the state that was found first, to minimize churn. This also prefers
            // fewer-transfer routes, all else equal, because fewer-transfer routes are found before more-transfer routes
            // due to the RAPTOR algorithm.
            if (betterOrEqual(existing, newState)) {
                return false;
            }

            if (betterOrEqual(newState, existing)) {
                it.remove();
                evictionCallback.accept(existing);
            }
        }

        // if we haven't returned false by now, state is nondominated.
        states.add(newState);
        return true;
    }

    private boolean hasInvalidBackChain(McRaptorSuboptimalPathProfileRouter.McRaptorState state) {
        McRaptorSuboptimalPathProfileRouter.McRaptorState slow = state;
        McRaptorSuboptimalPathProfileRouter.McRaptorState fast = state;
        int hops = 0;

        while (fast != null && fast.back != null) {
            slow = slow.back;
            fast = fast.back.back;
            hops += 2;

            if (slow != null && slow == fast) {
                return true;
            }
            if (hops > MAX_BACK_CHAIN_HOPS) {
                return true;
            }
        }

        return false;
    }

    private static void maybeLogMalformedState(
        String reason,
        long reasonCount,
        long droppedCount,
        McRaptorSuboptimalPathProfileRouter.McRaptorState state,
        RuntimeException error
    ) {
        if (!(reasonCount <= LOG_FIRST_N_FAILURES || reasonCount % LOG_EVERY_N_FAILURES == 0)) {
            return;
        }
        String stateSummary = summarizeState(state);
        String messageBase =
            "Dropping malformed McRaptor state in BeamDominatingList " +
                "[reason={}, reasonCount={}, droppedTotal={}, thread={}, state={}]";

        if (error == null) {
            if (LOG_MALFORMED_CHAIN_DETAILS) {
                String chainSummary = summarizeBackChain(state, MAX_CHAIN_SUMMARY_HOPS);
                LOG.warn(
                    messageBase + ", chain={}",
                    reason,
                    reasonCount,
                    droppedCount,
                    Thread.currentThread().getName(),
                    stateSummary,
                    chainSummary
                );
            } else {
                LOG.warn(
                    messageBase,
                    reason,
                    reasonCount,
                    droppedCount,
                    Thread.currentThread().getName(),
                    stateSummary
                );
            }
        } else {
            if (LOG_MALFORMED_CHAIN_DETAILS) {
                String chainSummary = summarizeBackChain(state, MAX_CHAIN_SUMMARY_HOPS);
                LOG.warn(
                    messageBase + ", chain={}",
                    reason,
                    reasonCount,
                    droppedCount,
                    Thread.currentThread().getName(),
                    stateSummary,
                    chainSummary,
                    error
                );
            } else {
                LOG.warn(
                    messageBase,
                    reason,
                    reasonCount,
                    droppedCount,
                    Thread.currentThread().getName(),
                    stateSummary,
                    error
                );
            }
        }
    }

    private static String summarizeState(McRaptorSuboptimalPathProfileRouter.McRaptorState state) {
        if (state == null) {
            return "null";
        }
        int backId = state.back == null ? -1 : System.identityHashCode(state.back);
        return String.format(
            "id=%d stop=%d round=%d pattern=%d trip=%d time=%d boardPos=%d alightPos=%d backId=%d access=%s egress=%s",
            System.identityHashCode(state),
            state.stop,
            state.round,
            state.pattern,
            state.trip,
            state.time,
            state.boardStopPosition,
            state.alightStopPosition,
            backId,
            state.accessMode,
            state.egressMode
        );
    }

    private static String summarizeBackChain(
        McRaptorSuboptimalPathProfileRouter.McRaptorState state,
        int maxHops
    ) {
        StringBuilder sb = new StringBuilder();
        McRaptorSuboptimalPathProfileRouter.McRaptorState cursor = state;
        int hops = 0;
        while (cursor != null && hops < maxHops) {
            if (hops > 0) sb.append(" <- ");
            sb.append(String.format(
                "%d:%d:r%d:p%d:t%d",
                System.identityHashCode(cursor),
                cursor.stop,
                cursor.round,
                cursor.pattern,
                cursor.time
            ));
            cursor = cursor.back;
            hops++;
        }
        if (cursor != null) {
            sb.append(" <- ...");
        }
        return sb.toString();
    }

    @Override
    public void reset() {
        states.clear();  // Clears but keeps capacity (pre-sized to 10)
        // maxFare, maxClockTime, fareCalculator are configuration - update via updateFrom
    }

    @Override
    public void updateFrom(DominatingList other) {
        if (other instanceof BeamDominatingList) {
            BeamDominatingList bdl = (BeamDominatingList) other;
            this.maxFare = bdl.maxFare;
            this.maxClockTime = bdl.maxClockTime;
            this.fareCalculator = bdl.fareCalculator;
        }
    }

    @Override
    public Collection<McRaptorSuboptimalPathProfileRouter.McRaptorState> getNonDominatedStates() {
        return states;
    }
}
