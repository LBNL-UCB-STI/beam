package beam.utils;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class TravelTimeCalculatorHelper {
    public static class TravelTimePerHour implements TravelTime {
        private Logger log = LoggerFactory.getLogger(TravelTimePerHour.class);

        private final Map<Id<Link>, double[]> _linkIdToTravelTimeArray;
        private final int _timeBinSizeInSeconds;

        public TravelTimePerHour(int timeBinSizeInSeconds, Map<String, double[]> linkIdToTravelTimeData) {
            _timeBinSizeInSeconds = timeBinSizeInSeconds;
            _linkIdToTravelTimeArray = new HashMap<>();
            linkIdToTravelTimeData.forEach((key, value) -> {
                Id<Link> linkId = Id.createLinkId(key);
                _linkIdToTravelTimeArray.put(linkId, value);
            });
        }
        @Override
        public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
            Id<Link> linkId = link.getId();
            double[] timePerHour = _linkIdToTravelTimeArray.get(linkId);
            if (null == timePerHour){
                log.warn("Can't find travel times for link '{}'", linkId);
                return link.getFreespeed();
            }
            int idx = getOffset(time);
            if (idx >= timePerHour.length) {
                log.warn("Got offset which is out of array for the link {}. Something wrong. idx: {}, time: {},  _timeBinSizeInSeconds: '{}'",
                        linkId, idx, time, _timeBinSizeInSeconds);
                return link.getFreespeed();
            }
            return timePerHour[idx];
        }
        private int getOffset(double time){
            return (int)Math.round(Math.floor(time / _timeBinSizeInSeconds));
        }
    }
    private static Logger log = LoggerFactory.getLogger(TravelTimeCalculatorHelper.class);

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

    public static TravelTime CreateTravelTimeCalculator(int timeBinSizeInSeconds, Map<String, double[]> linkIdToTravelTimeData) {
        return new TravelTimePerHour(timeBinSizeInSeconds, linkIdToTravelTimeData);
    }
}