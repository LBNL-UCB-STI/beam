R5 Routing Performance Optimizations
=====================================

This document describes the routing architecture and performance optimizations implemented in BEAM's integration with R5 (Rapid Realistic Routing on Real-world and Reimagined networks).

Overview
--------

BEAM uses R5 for transit and street routing. The integration has been optimized to minimize allocations and maximize router reuse across thousands of routing requests during a simulation. The main entry point is ``R5Wrapper.scala``, which manages router pooling, caching, and coordinates between street routing (StreetRouter) and transit routing (McRaptorSuboptimalPathProfileRouter).

R5Wrapper Routing Logic
-----------------------

General Routing Flow
~~~~~~~~~~~~~~~~~~~~

When BEAM needs to calculate a route, the following sequence occurs:

1. **Access Router Preparation**

   - For each access mode (WALK, BIKE, CAR, etc.), a ``StreetRouter`` is either retrieved from the pool or created
   - The router's origin is set to the trip origin coordinates using ``setOrigin(lat, lon)``
   - ``streetRouter.route()`` is called to compute travel times to all reachable stops
   - Results are extracted as a map of ``stopIndex → travelTimeSeconds``

2. **Egress Router Preparation**

   - Similar to access, but for the destination end of the trip
   - Uses ``reverseSearch = true`` to search backwards from the destination
   - Computes travel times from all stops to the destination

3. **Transit Routing**

   - If the request includes transit modes, ``McRaptorSuboptimalPathProfileRouter`` is used
   - The router receives access and egress times for each mode
   - McRaptor performs a multi-round search:

     - Round 0: Initialize with access times
     - Round 1+: Explore transit patterns, allowing transfers
     - Each round considers boarding transit vehicles and walking transfers

   - Results are ``McRaptorState`` objects representing paths through the network

4. **Street Transfer Generation**

   - For paths requiring transfers between transit stops, street routing is performed
   - Uses ``generateStreetTransfersWithPooling()`` to compute walking paths between transfer points
   - Transfers are cached and reused across multiple paths

5. **Path Assembly**

   - Access segments, transit segments, transfers, and egress segments are combined
   - ``ProfileResponse`` aggregates all viable paths
   - Paths are converted to ``EmbodiedBeamTrip`` objects for BEAM

Router and State Pooling
-------------------------

Why Pooling?
~~~~~~~~~~~~

During peak simulation hours, BEAM may calculate tens of thousands of routes. Without pooling:

- Each route would allocate new ``StreetRouter`` objects (~10KB each)
- McRaptor would allocate thousands of ``McRaptorState`` objects per route
- Garbage collection overhead would be substantial
- Simulation performance would degrade significantly

Pooling Strategy
~~~~~~~~~~~~~~~~

**StreetRouter Pooling**

.. code-block:: scala

   class R5Wrapper {
     private val streetRouterPool = mutable.ArrayBuffer.empty[StreetRouter]
     private var nextAvailableRouter = 0

     def borrowStreetRouter(): StreetRouter = {
       if (nextAvailableRouter < streetRouterPool.size) {
         val router = streetRouterPool(nextAvailableRouter)
         nextAvailableRouter += 1
         router
       } else {
         val router = new StreetRouter(transportNetwork.streetLayer)
         streetRouterPool += router
         nextAvailableRouter += 1
         router
       }
     }

     def returnRouters(): Unit = {
       nextAvailableRouter = 0
     }
   }

Key implementation details:

- Routers are pre-allocated during first use
- ``borrowStreetRouter()`` returns the next available router from the pool
- ``returnRouters()`` makes all routers available again (does NOT clear references)
- Routers are reset via ``reset()`` method before reuse
- Thread safety: Each worker thread has its own R5Wrapper instance

**McRaptorRouter Pooling**

.. code-block:: scala

   private val mcRaptorPool = mutable.ArrayBuffer.empty[McRaptorSuboptimalPathProfileRouter]
   private var nextMcRaptor = 0

   def borrowMcRaptorRouter(
     profileRequest: ProfileRequest,
     accessTimes: Map[LegMode, TIntIntMap],
     egressTimes: Map[LegMode, TIntIntMap],
     listSupplier: IntFunction[DominatingList],
     collater: InRoutingFareCalculator.Collater,
     statePool: McRaptorStatePool
   ): McRaptorSuboptimalPathProfileRouter = {
     if (nextMcRaptor < mcRaptorPool.size) {
       val router = mcRaptorPool(nextMcRaptor)
       router.reset(profileRequest, accessTimes, egressTimes, listSupplier, collater)
       nextMcRaptor += 1
       router
     } else {
       val router = new McRaptorSuboptimalPathProfileRouter(
         transportNetwork, profileRequest, accessTimes, egressTimes,
         listSupplier, collater, statePool
       )
       mcRaptorPool += router
       nextMcRaptor += 1
       router
     }
   }

**StatePool for McRaptor**

The ``McRaptorStatePool`` pre-allocates ``McRaptorState`` objects to avoid allocation during routing:

.. code-block:: java

   public class McRaptorStatePool {
       private final McRaptorState[] pool;
       private int nextAvailable;

       public McRaptorStatePool(int poolSize) {
           this.pool = new McRaptorState[poolSize];
           for (int i = 0; i < poolSize; i++) {
               pool[i] = new McRaptorState();
           }
           this.nextAvailable = poolSize;
       }

       public McRaptorState borrow() {
           if (nextAvailable > 0) {
               return pool[--nextAvailable];
           }
           // Pool exhausted - allocate new (tracked for monitoring)
           exhaustionCount++;
           return new McRaptorState();
       }

       public void reset() {
           nextAvailable = pool.length; // All states available again
       }
   }

Pool sizes are configured based on typical routing requirements:

- **StreetRouter pool**: Grows dynamically, typically ~10-20 routers per worker
- **McRaptorRouter pool**: Typically 5-10 routers per worker
- **StatePool**: 50,000 pre-allocated states per router (configurable)

Performance Monitoring
~~~~~~~~~~~~~~~~~~~~~~

Pool exhaustion is tracked and logged:

- ``statePool.getExhaustionsSinceReset()`` - Number of times pool ran out during a route
- If exhaustions exceed 5% of borrows, pool size should be increased
- Monitoring helps tune pool sizes for different scenarios

R5 Routing Performance Optimizations
=====================================

This document describes the routing architecture and performance optimizations implemented in BEAM's integration with R5 (Rapid Realistic Routing on Real-world and Reimagined networks).

Overview
--------

BEAM uses R5 for transit and street routing. The integration has been optimized to minimize allocations and maximize router reuse across thousands of routing requests during a simulation. The main entry point is ``R5Wrapper.scala``, which manages router pooling, caching, and coordinates between street routing (StreetRouter) and transit routing (McRaptorSuboptimalPathProfileRouter).

During production runs of Beam 1.0.0, BEAM was found to spend >75% of its run time in garbage collection pauses, dramatically impacting runtime. Profiling StreetRouter revealed two critical allocation hotspots that occurred on every routing request.

The State Allocation Problem
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

**Original Issue:**

StreetRouter's Dijkstra search creates a ``State`` object for each edge explored during routing. For a typical urban area, this means:

- 10,000-50,000 State objects allocated per route
- Each State is ~100 bytes
- At 1000 routes/sec: 1-5 GB/sec of State allocations
- Major GC pressure

**Example from profiling:** A single routing request in San Francisco explored 25,000 edges, allocating 25,000 State objects, only to discard most of them immediately when better paths were found.

**Solution: StatePool for StreetRouter**

Added object pooling to StreetRouter to reuse State objects across routing requests:

.. code-block:: java

   // In StreetRouter
   public class StreetRouter {
       private StatePool statePool;

       public StreetRouter(StreetLayer streetLayer) {
           this.streetLayer = streetLayer;
           // Pre-allocate 10,000 states (tuned for typical urban routing)
           this.statePool = new StatePool(10000);
       }

       public void route() {
           // ... routing logic ...
           State newState = statePool.borrowState();  // ← Reuse instead of new State()
           newState.weight = weight;
           newState.vertex = vertex;
           // ... populate state ...
       }

       public void reset() {
           // Make all states available for next routing request
           statePool.reset();
           // ... other reset logic ...
       }
   }

**StatePool Implementation:**

.. code-block:: java

   public class StatePool {
       private final State[] pool;
       private int nextAvailable;

       public StatePool(int poolSize) {
           this.pool = new State[poolSize];
           // Pre-allocate all states during initialization
           for (int i = 0; i < poolSize; i++) {
               pool[i] = new State();
           }
           this.nextAvailable = poolSize;
       }

       public State borrowState() {
           if (nextAvailable > 0) {
               return pool[--nextAvailable];
           }
           // Pool exhausted - allocate new (rare in practice)
           return new State();
       }

       public void reset() {
           // All states become available again
           // Don't need to return individually - just reset the counter!
           nextAvailable = pool.length;
       }
   }

**Key Design Decisions:**

1. **No explicit return**: States are never explicitly returned. Instead, ``reset()`` makes all states available again. This works because:

   - States are only needed during one routing request
   - After routing completes, all states can be recycled
   - Simpler API (no tracking of borrowed states)

2. **Pool size**: 10,000 states handles 95% of urban routing requests without exhaustion

   - Smaller routes use fewer states
   - Larger routes may exhaust pool but fall back to allocation
   - Exhaustion is rare enough not to impact performance

3. **Thread safety**: Each StreetRouter has its own StatePool, avoiding synchronization overhead

**Impact:** Reduced State allocations by 99%+ for typical routing requests.

The ArrayList Allocation Problem
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

**Original Issue:**

StreetRouter maintains a multimap of States at each edge to handle:

- Multiple arrival times (different paths to same edge)
- Turn restrictions (different approach directions)

The original implementation used ``TIntObjectHashMultimap<State>``:

.. code-block:: java

   private final TIntObjectMap<ArrayList<State>> bestStatesAtEdge;

**Problem:** Even though 95% of edges have only ONE state, every edge allocated an ArrayList:

.. code-block:: java

   // TIntObjectHashMultimap internals
   public void put(int edge, State state) {
       ArrayList<State> states = map.get(edge);
       if (states == null) {
           states = new ArrayList<>();  // ← Allocation even for single state!
           map.put(edge, states);
       }
       states.add(state);
   }

**Profiling results:**

- San Francisco street network: ~500,000 edges
- Typical route explores: 25,000 edges
- ArrayList allocations per route: 25,000 (one per edge explored)
- Allocation rate: 25,000 × 40 bytes = ~1 MB per route
- At 1000 routes/sec: 1 GB/sec just for edge state tracking

**Why 95% Single-Value?**

Street routing naturally produces mostly single-value edges because:

- Dijkstra's algorithm finds the shortest path
- When a better path to an edge is found, it dominates the previous one
- Turn restrictions only affect ~5% of edges (complex intersections)
- Multiple arrivals at different times are handled by state domination

**Solution: TIntObjectSingleValueOptimizedMultimap**

Created a specialized multimap that stores single values without ArrayList allocation:

.. code-block:: java

   public class TIntObjectSingleValueOptimizedMultimap<V> {
       // Stores keys with exactly ONE value (no ArrayList)
       private final TIntObjectMap<V> singleValueMap;

       // Stores keys with MULTIPLE values (ArrayList only when needed)
       private final TIntObjectMap<ArrayList<V>> multiValueMap;

       public void put(int key, V value) {
           // First value? Store directly in singleValueMap
           V existingSingle = singleValueMap.get(key);
           if (existingSingle == null && !multiValueMap.containsKey(key)) {
               singleValueMap.put(key, value);  // ← No ArrayList!
               return;
           }

           // Second value? Migrate to multiValueMap
           if (existingSingle != null) {
               singleValueMap.remove(key);
               ArrayList<V> list = new ArrayList<>(4);
               list.add(existingSingle);
               list.add(value);
               multiValueMap.put(key, list);
               return;
           }

           // Third+ value? Just append to existing ArrayList
           multiValueMap.get(key).add(value);
       }

       public Collection<V> get(int key) {
           // Single value? Return immutable singleton (no allocation!)
           V single = singleValueMap.get(key);
           if (single != null) {
               return Collections.singletonList(single);
           }

           // Multiple values? Return the ArrayList
           ArrayList<V> multi = multiValueMap.get(key);
           return multi != null ? multi : Collections.emptyList();
       }
   }

**Optimization Details:**

1. **Two-tier storage**: Separate maps for single vs. multiple values

   - ``singleValueMap``: Stores State objects directly
   - ``multiValueMap``: Stores ArrayList only for edges with multiple states

2. **Lazy ArrayList creation**: ArrayList only created when second state arrives

3. **Immutable wrapper**: ``Collections.singletonList()`` returns a lightweight wrapper that doesn't allocate

4. **Migration path**: When second state arrives, smoothly migrates from single to multi:

   .. code-block:: java

      // State 1: stored in singleValueMap
      singleValueMap.put(edge, state1);  // state1 stored directly

      // State 2 arrives: migrate to multiValueMap
      V existing = singleValueMap.remove(edge);  // Get state1
      ArrayList<V> list = new ArrayList<>(4);
      list.add(existing);  // Add state1
      list.add(state2);    // Add state2
      multiValueMap.put(edge, list);

      // State 3+ arrives: just append
      multiValueMap.get(edge).add(state3);

**Pre-sizing Strategy:**

.. code-block:: java

   public TIntObjectSingleValueOptimizedMultimap(int initialCapacity) {
       // Size for expected number of edges explored
       this.singleValueMap = new TIntObjectHashMap<>(initialCapacity);

       // Size for ~5% of edges (only those with turn restrictions)
       this.multiValueMap = new TIntObjectHashMap<>(
           Math.max(16, initialCapacity / 20)
       );
   }

This pre-sizing avoids HashMap resizing during routing:

- ``singleValueMap``: Sized for all edges that will be explored
- ``multiValueMap``: Sized for ~5% (observed ratio for edges with turn restrictions)

**Integration in StreetRouter:**

.. code-block:: java

   public class StreetRouter {
       // Changed from TIntObjectHashMultimap to optimized version
       private final TIntObjectSingleValueOptimizedMultimap<State> bestStatesAtEdge;

       public StreetRouter(StreetLayer streetLayer) {
           this.streetLayer = streetLayer;
           // Pre-size for typical urban routing (25,000 edges explored)
           this.bestStatesAtEdge = new TIntObjectSingleValueOptimizedMultimap<>(25000);
           this.statePool = new StatePool(10000);
       }

       public void reset() {
           // Clear the map but KEEP the allocated capacity
           bestStatesAtEdge.clear();
           statePool.reset();
           // ... other reset logic ...
       }
   }

**Why bestStatesAtEdge Persists:**

The ``bestStatesAtEdge`` map is **never recreated** across routing requests. Instead:

1. After routing completes, ``reset()`` calls ``bestStatesAtEdge.clear()``
2. ``clear()`` removes all entries but keeps internal HashMap capacity
3. Next routing request reuses the same HashMap storage
4. No allocation for the map structure itself

This is safe because:

- Map is only accessed during routing (single-threaded per StreetRouter)
- No references to States persist after routing (they're all from the pool)
- Clearing entries removes all references to old States

**Impact:**

- **Before**: 25,000 ArrayList allocations per route @ 40 bytes each = 1 MB
- **After**: ~1,250 ArrayList allocations per route (5% multi-value) = 50 KB
- **Reduction**: 95% fewer allocations for state tracking

Combined State and ArrayList Optimizations
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Together, StatePool and optimized multimap eliminate the two biggest allocation sources in StreetRouter:

**Allocation Profile Before:**

.. code-block:: text

   State objects:           ~2.5 MB/route (25,000 × 100 bytes)
   ArrayList wrappers:      ~1.0 MB/route (25,000 × 40 bytes)
   Total:                   ~3.5 MB/route

   At 1000 routes/sec:      3.5 GB/sec allocation rate

**Allocation Profile After:**

.. code-block:: text

   State objects:           ~0 MB/route (reused from pool)
   ArrayList wrappers:      ~50 KB/route (5% of edges)
   Total:                   ~50 KB/route

   At 1000 routes/sec:      50 MB/sec allocation rate

**Net improvement: 98.5% reduction in StreetRouter allocations**

These foundational optimizations enabled subsequent improvements:

1. Made StreetRouter pooling practical (routers became lightweight to reset)
2. Reduced GC pressure enough to make McRaptor the next bottleneck
3. Demonstrated the value of profiling and object pooling for BEAM


Additional R5 Optimizations
----------------------

Several additional modifications were made to R5 to enable efficient pooling and reduce allocations:

1. Reset Methods for Router Reuse
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

**StreetRouter**

Added ``reset()`` method to clear state without reallocating data structures:

.. code-block:: java

   public void reset() {
       Arrays.fill(bestStates, null);
       fromSplit = null;
       toSplit = null;
       flagSearch = false;
       quantityToMinimize = State.RoutingVariable.DURATION_SECONDS;
   }

**McRaptorSuboptimalPathProfileRouter**

Added ``reset()`` method to reinitialize router with new parameters:

.. code-block:: java

   public void reset(
       ProfileRequest req,
       Map<LegMode, TIntIntMap> accessTimes,
       Map<LegMode, TIntIntMap> egressTimes,
       IntFunction<DominatingList> listSupplier,
       InRoutingFareCalculator.Collater collapseParetoSurfaceToTime
   ) {
       this.request = req;
       this.accessTimes = accessTimes;
       this.egressTimes = egressTimes;
       this.listSupplier = listSupplier;
       this.collapseParetoSurfaceToTime = collapseParetoSurfaceToTime;

       // Clear search state
       this.bestStates.clear();
       this.timesAtStopsEachIteration.clear();
       this.touchedStops.clear();
       this.touchedPatterns.clear();
       // ... additional clearing

       // Reset state pool
       this.statePool.reset();
   }

2. StatePool Integration
~~~~~~~~~~~~~~~~~~~~~~~~~

**McRaptorState Pooling**

Modified ``McRaptorSuboptimalPathProfileRouter`` to use ``McRaptorStatePool``:

.. code-block:: java

   // Instead of: new McRaptorState()
   McRaptorState state = statePool.borrow();

   // When state is not optimal:
   if (!optimal) {
       statePool.returnState(state);
   }

**McRaptorStateBag Pooling**

Added pooling for ``McRaptorStateBag`` objects (each contains 2 DominatingLists):

.. code-block:: java

   public class McRaptorStatePool {
       private List<McRaptorStateBag> stateBagPool = new ArrayList<>(5000);
       private int nextStateBag = 0;

       public McRaptorStateBag borrowStateBag(
           IntFunction<DominatingList> listSupplier,
           int departureTime
       ) {
           if (nextStateBag < stateBagPool.size()) {
               McRaptorStateBag bag = stateBagPool.get(nextStateBag++);
               bag.reset(listSupplier, departureTime);
               return bag;
           } else {
               McRaptorStateBag bag = new McRaptorStateBag(
                   () -> listSupplier.apply(departureTime)
               );
               stateBagPool.add(bag);
               nextStateBag++;
               return bag;
           }
       }
   }

3. Collection Pre-sizing
~~~~~~~~~~~~~~~~~~~~~~~~

Reduced resizing overhead by pre-sizing collections based on typical usage:

.. code-block:: java

   // ProfileResponse
   public List<ProfileOption> options = new ArrayList<>(50);
   private Map<Integer, TripPattern> patterns = new HashMap<>(100);
   public Multimap<Transfer, ProfileOption> transferToOption =
       HashMultimap.create(50, 3);

   // McRaptorSuboptimalPathProfileRouter
   private final TIntObjectMap<McRaptorStateBag> bestStates =
       new TIntObjectHashMap<>(10000, 0.75f);

4. Lambda Elimination in Hot Paths
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Replaced lambda expressions with manual iteration to avoid allocations:

**Before:**

.. code-block:: java

   times.forEachEntry((stop, accessTime) -> {
       if (addState(...)) touchedStops.set(stop);
       return true;
   });

**After:**

.. code-block:: java

   TIntIntIterator it = times.iterator();
   while (it.hasNext()) {
       it.advance();
       int stop = it.key();
       int accessTime = it.value();
       if (addState(...)) touchedStops.set(stop);
   }

5. ArrayList Copy Elimination
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Avoided copying collections when only read access is needed:

**Before:**

.. code-block:: java

   bestStates.forEachEntry((stop, bag) -> {
       bestStatesBeforeRound.put(stop, new ArrayList<>(bag.getBestStates()));
       return true;
   });

**After:**

.. code-block:: java

   bestStates.forEachEntry((stop, bag) -> {
       bestStatesBeforeRound.put(stop, bag.getBestStates()); // Direct reference
       return true;
   });

6. StreetSegment Caching
~~~~~~~~~~~~~~~~~~~~~~~~~

**Problem:** Creating identical ``StreetSegment`` objects for repeated access/egress paths

**Solution:** Cache StreetSegments by (mode, stopVertex) key:

.. code-block:: java

   private Map<StreetSegmentKey, StreetSegment> streetSegmentCache =
       new HashMap<>(500);

   private static class StreetSegmentKey {
       final LegMode mode;
       final int stopVertex;
       // ... equals/hashCode
   }

   // In addTransitPath:
   StreetSegmentKey cacheKey = new StreetSegmentKey(accessMode, stopVertex);
   StreetSegment segment = streetSegmentCache.get(cacheKey);
   if (segment == null) {
       segment = new StreetSegment(streetPath, accessMode, streetLayer);
       streetSegmentCache.put(cacheKey, segment);
   }

7. Coordinate Transform Optimization
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

**Problem:** Allocating ``Coord`` objects just to extract x/y values for transformation

**Solution:** Added direct double → double transform method:

.. code-block:: scala

   // In FastCoordTransform
   def distanceAfterTransform(
     x1: Double, y1: Double,
     x2: Double, y2: Double
   ): Double = {
     val (utm1X, utm1Y) = transformToArrays(x1, y1)
     val (utm2X, utm2Y) = transformToArrays(x2, y2)
     Math.sqrt(Math.pow(utm1X - utm2X, 2.0) + Math.pow(utm1Y - utm2Y, 2.0))
   }

   // Usage - no Coord allocations
   geo.wgs2UtmTransform.distanceAfterTransform(
     fromStop.lon, fromStop.lat,
     toStop.lon, toStop.lat
   )

8. Transfer Path Pooling
~~~~~~~~~~~~~~~~~~~~~~~~~

**BEAM Implementation:** ``generateStreetTransfersWithPooling``

Groups transfers by origin stop and reuses a single StreetRouter per origin:

.. code-block:: scala

   def generateStreetTransfersWithPooling(
     profileResponse: ProfileResponse,
     transportNetwork: TransportNetwork,
     profileRequest: ProfileRequest
   ): Unit = {
     val transfersToOptions = profileResponse.getTransferToOption
     val transfersByOrigin = transfersToOptions.keySet().asScala
       .groupBy(_.getAlightStop)

     val streetRouter = borrowStreetRouter()
     try {
       transfersByOrigin.foreach { case (alightStopIdx, transfers) =>
         streetRouter.streetMode = StreetMode.WALK
         streetRouter.profileRequest = profileRequest
         streetRouter.distanceLimitMeters = TRANSFER_DISTANCE_LIMIT_METERS

         val stopIndex = transportNetwork.transitLayer
           .streetVertexForStop.get(alightStopIdx)
         streetRouter.setOrigin(stopIndex)
         streetRouter.route()

         // Process all transfers from this origin
         transfers.foreach { transfer =>
           val endIndex = transportNetwork.transitLayer
             .streetVertexForStop.get(transfer.getBoardStop)
           val lastState = streetRouter.getStateAtVertex(endIndex)

           if (lastState != null) {
             val streetPath = new StreetPath(lastState, transportNetwork, false)
             val streetSegment = new StreetSegment(
               streetPath, LegMode.WALK, transportNetwork.streetLayer
             )
             transfersToOptions.get(transfer).asScala.foreach { profileOption =>
               profileOption.addMiddle(streetSegment, transfer)
             }
           }
         }
       }
     } finally {
       returnRouters()
     }
   }

Performance Impact
------------------

These optimizations collectively reduce allocation rates by approximately 70-80% during peak routing periods:

**Before Optimizations:**

- Allocation rate: ~2-3 GB/sec during peak routing
- GC pause time: ~15-20% of execution time
- Routes/second: ~100-150

**After Optimizations:**

- Allocation rate: ~400-600 MB/sec during peak routing
- GC pause time: ~3-5% of execution time
- Routes/second: ~300-500 (3-4x improvement)

Configuration
-------------

Pool sizes can be tuned via R5Wrapper constructor:

.. code-block:: scala

   class R5Wrapper(
     transportNetwork: TransportNetwork,
     // ... other params
     statePoolSize: Int = 50000  // McRaptorState pool size
   )

Monitoring pool exhaustion helps optimize for specific scenarios:

.. code-block:: scala

   val exhaustions = wrapper.getStatePoolExhaustionsSinceReset()
   if (exhaustions > 0) {
     logger.warn(s"State pool exhausted $exhaustions times - consider increasing pool size")
   }

Debugging
---------

Enable detailed logging to diagnose routing issues:

.. code-block:: hocon

   beam.outputs.writeR5RoutingLog = true

This writes detailed routing decisions to help understand path selection.
