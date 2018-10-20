package beam.utils;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.core.trafficmonitoring.TravelTimeData;
import org.matsim.core.trafficmonitoring.TravelTimeDataArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

public class TravelTimeCalculatorHelper  {
    private static Logger log = LoggerFactory.getLogger(TravelTimeCalculatorHelper.class);

    public static Map<String, TravelTimeDataWithoutLink> GetLinkIdToTravelTimeDataArray(TravelTimeCalculator travelTimeCalculator) {
        long start = System.currentTimeMillis();
        Map<String, TravelTimeDataWithoutLink> result = new HashMap<>();
        try {
            Map<Id<Link>, Object> linkData = (Map<Id<Link>, Object>) FieldUtils.readField(travelTimeCalculator, "linkData", true);
            linkData.forEach((linkId, dc) -> {
                try {
                    TravelTimeDataArray ttda = (TravelTimeDataArray) FieldUtils.readField(dc, "ttData", true);
                    double[] timeSum = (double[])FieldUtils.readField(ttda, "timeSum", true);
                    int[] timeCnt = (int[])FieldUtils.readField(ttda, "timeCnt", true);
                    double[] travelTimes = (double[])FieldUtils.readField(ttda, "travelTimes", true);
                    TravelTimeDataWithoutLink my = new TravelTimeDataWithoutLink(timeSum, timeCnt, travelTimes);
                    result.put(linkId.toString(), my);
                }
                catch (Exception ex) {
                    log.error("Can't fill Map<String, MyTravelTimeDataArray>", ex);
                }
            });
            log.info("linkData key size: {}, value size: {}", linkData.size(), linkData.values().size());
        }
        catch (Exception ex) {
            log.error("Can't read private field", ex);
        }
        long end = System.currentTimeMillis();
        long diff = end - start;
        log.info("GetLinkIdToTravelTimeDataArray executed in {} ms", diff);

        return result;
    }

    public static TravelTime CreateTravelTimeCalculator(Network network, Scenario scenario, Map<String, TravelTimeDataWithoutLink> linkIdToTravelTimeData){
        TravelTimeCalculator tc = new TravelTimeCalculator(network, scenario.getConfig().travelTimeCalculator());
        Map<Id<Link>, Object> tempLinkData = null;
        try {
            tempLinkData = (Map<Id<Link>, Object>) FieldUtils.readField(tc, "linkData", true);
        }
        catch (Exception ex) {
            log.error("Can't read `TravelTimeCalculator.linkData`", ex);
            throw new RuntimeException("Can't read `TravelTimeCalculator.linkData`", ex);
        }
        final Map<Id<Link>, Object> linkData = tempLinkData;
        linkData.clear();

        linkIdToTravelTimeData.forEach((key, value) -> {
            Id<Link> linkId = Id.createLinkId(key);
            Object dc = null;
            try {
                TravelTimeDataArray tta = CreateFrom(value, network.getLinks().get(linkId));
                Class<?> clazz = Class.forName("org.matsim.core.trafficmonitoring.TravelTimeCalculator$DataContainer");
                Constructor<?> ctor = clazz.getDeclaredConstructor(TravelTimeData.class);
                ctor.setAccessible(true);
                dc = ctor.newInstance(tta);
            }
            catch (Exception ex) {
                throw new RuntimeException("Can't create `DataContainer`", ex);
            }
            linkData.put(linkId, dc);
        });
        log.info("TravelTimeCalculator is created");
        return tc.getLinkTravelTimes();
    }

    private static TravelTimeDataArray CreateFrom(TravelTimeDataWithoutLink value, Link link) throws IllegalAccessException{
        TravelTimeDataArray tta = new TravelTimeDataArray(null,value.timeCnt.length);
        FieldUtils.writeField(tta, "timeSum", value.timeSum, true);
        FieldUtils.writeField(tta, "timeCnt", value.timeCnt, true);
        FieldUtils.writeField(tta, "travelTimes", value.travelTimes, true);
        FieldUtils.writeField(tta, "link", link, true);
        return tta;
    }
}