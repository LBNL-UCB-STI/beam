package beam.utils;

import beam.router.BeamTravelTime;
import beam.utils.logging.ExponentialLoggerWrapperImpl;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class TravelTimeCalculatorHelper {
    public static class TravelTimePerHour implements BeamTravelTime {
        private final Logger log = LoggerFactory.getLogger(TravelTimePerHour.class);

        private final double[][] _linkIdToTravelTimeArray;
        private final int _timeBinSizeInSeconds;
        private final double _inverseTimeBinSize;  // ← NEW: precompute
        private int numWarnings = 0;

        public TravelTimePerHour(int timeBinSizeInSeconds, final Map<String, double[]> linkIdToTravelTimeData) {
            _timeBinSizeInSeconds = timeBinSizeInSeconds;
            _inverseTimeBinSize = 1.0 / timeBinSizeInSeconds;
            _linkIdCache = buildLinkIdCache(linkIdToTravelTimeData.keySet());
            _linkIdToTravelTimeArray = initTravelTime(linkIdToTravelTimeData);
        }

        private final Object2IntOpenHashMap<Id<Link>> _linkIdCache;

        private static Object2IntOpenHashMap<Id<Link>> buildLinkIdCache(Set<String> linkIdStrings) {
            Object2IntOpenHashMap<Id<Link>> cache = new Object2IntOpenHashMap<>(linkIdStrings.size());
            cache.defaultReturnValue(-1);
            for (String linkIdStr : linkIdStrings) {
                cache.put(Id.createLinkId(linkIdStr), Integer.parseInt(linkIdStr));
            }
            return cache;
        }

        @Override
        public double getLinkTravelTime(int linkId, double time) {
            // Fast path
            if (linkId < _linkIdToTravelTimeArray.length) {
                double[] timePerHour = _linkIdToTravelTimeArray[linkId];
                if (timePerHour != null) {
                    int idx = (int) (time * _inverseTimeBinSize);
                    if (idx < timePerHour.length) {
                        return timePerHour[idx];
                    }
                }
            }
            return handleInvalidLookup(linkId, time);
        }

        @Override
        public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
            int linkId = _linkIdCache.getInt(link.getId());
            return getLinkTravelTime(linkId, time);
        }

        private double handleInvalidLookup(int linkId, double time) {
            if (linkId == -1) {
                return 0d;  // Not in cache
            }
            if (linkId >= _linkIdToTravelTimeArray.length || _linkIdToTravelTimeArray[linkId] == null) {
                if (ExponentialLoggerWrapperImpl.isNumberPowerOfTwo(++numWarnings)) {
                    log.warn("Invalid linkId {} or missing travel time data", linkId);
                }
                return 0d;
            }
            int idx = (int) (time * _inverseTimeBinSize);
            if (idx >= _linkIdToTravelTimeArray[linkId].length) {
                if (ExponentialLoggerWrapperImpl.isNumberPowerOfTwo(++numWarnings)) {
                    log.warn("Got offset which is out of array for the link {}. idx: {}, time: {},  _timeBinSizeInSeconds: '{}'",
                            linkId, idx, time, _timeBinSizeInSeconds);
                }
                return 0d;
            }
            return _linkIdToTravelTimeArray[linkId][idx];
        }

        private int getOffset(double time) {
            return (int) (time / _timeBinSizeInSeconds);
        }

        public static double[][] initTravelTime(final Map<String, double[]> linkIdToTravelTimeData) {
            if (linkIdToTravelTimeData == null) throw new NullPointerException("linkIdToTravelTimeData == null");
            if (linkIdToTravelTimeData.isEmpty()) throw new IllegalStateException("linkIdToTravelTimeData is empty");

            int maxLinkId = linkIdToTravelTimeData.keySet().stream()
                    .map(Integer::parseInt)
                    .max(Comparator.naturalOrder())
                    .get();
            final int travelTimeArraySize = linkIdToTravelTimeData.values().stream().findFirst().get().length;
            final double[][] linkIdToTravelTimeArray = new double[maxLinkId + 1][travelTimeArraySize];
            linkIdToTravelTimeData.forEach((key, value) -> {
                final int idx = Integer.parseInt(key);
                linkIdToTravelTimeArray[idx] = value.clone();
            });
            return linkIdToTravelTimeArray;
        }

        @Override
        public double getLinkTravelTime(int linkId, double time, double linkLengthMeters) {
            return getLinkTravelTime(linkId, time);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(TravelTimeCalculatorHelper.class);

    public static Map<String, double[]> GetLinkIdToTravelTimeArray(Collection<? extends Link> links, TravelTime travelTime, int maxHour) {
        long start = System.currentTimeMillis();
        Map<String, double[]> result = new HashMap<>();
        for (Link link : links) {
            Id<Link> linkId = link.getId();
            double[] times = new double[maxHour];
            for (int hour = 0; hour < maxHour; hour++) {
                int hourInSeconds = hour * 3600;
                times[hour] = travelTime.getLinkTravelTime(link, hourInSeconds, null, null);
            }
            result.put(linkId.toString(), times);

        }
        long end = System.currentTimeMillis();
        long diff = end - start;
        log.info("GetLinkIdToTravelTimeArray for {} links with maxHour = {} executed in {} ms", links.size(), maxHour, diff);
        return result;
    }

    public static Map<String, double[]> GetLinkIdToTravelTimeAvgArray(Collection<? extends Link> links, TravelTime travelTime1, TravelTime travelTime2, int maxHour) {
        long start = System.currentTimeMillis();
        Map<String, double[]> res1 = GetLinkIdToTravelTimeArray(links, travelTime1, maxHour);
        Map<String, double[]> res2 = GetLinkIdToTravelTimeArray(links, travelTime2, maxHour);
        assert res1.size() == res2.size();

        Map<String, double[]> result = AverageTravelTimesMap(res1, res2, maxHour);
        long end = System.currentTimeMillis();
        long diff = end - start;
        log.info("GetLinkIdToTravelTimeAvgArray for {} links with maxHour = {} executed in {} ms", links.size(), maxHour, diff);
        return result;
    }

    public static Map<String, double[]> AverageTravelTimesMap(Map<String, double[]> res1, Map<String, double[]> res2, int maxHour) {
        Map<String, double[]> result = new HashMap<>();
        for (String linkId : res1.keySet()) {
            double[] times1 = res1.get(linkId);
            double[] times2 = res2.get(linkId);
            double[] times = new double[maxHour];
            for (int hour = 0; hour < maxHour; hour++) {
                double t1 = times1[hour];
                double t2 = times2[hour];
                times[hour] = (t1 + t2) / 2;
            }
            result.put(linkId, times);
        }
        return result;
    }

    public static BeamTravelTime CreateTravelTimeCalculator(int timeBinSizeInSeconds, Map<String, double[]> linkIdToTravelTimeData) {
        return new TravelTimePerHour(timeBinSizeInSeconds, linkIdToTravelTimeData);
    }
}