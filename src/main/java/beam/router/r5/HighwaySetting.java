package beam.router.r5;

import java.util.Collections;
import java.util.Map;

public class HighwaySetting {
    public final Map<HighwayType, Double> speedsMeterPerSecondMap;
    public final Map<HighwayType, Integer> capacityMap;
    public final Map<HighwayType, Integer> lanesMap;
    public final Map<HighwayType, Double> alphaMap;
    public final Map<HighwayType, Double> betaMap;


    public HighwaySetting(Map<HighwayType, Double> speedsMeterPerSecondMap, Map<HighwayType, Integer> capacityMap, Map<HighwayType, Integer> lanesMap, Map<HighwayType, Double> alphaMap, Map<HighwayType, Double> betaMap) {
        this.speedsMeterPerSecondMap = speedsMeterPerSecondMap;
        this.capacityMap = capacityMap;
        this.lanesMap = lanesMap;
        this.alphaMap = alphaMap;
        this.betaMap = betaMap;
    }
    public static HighwaySetting empty() {
        return new HighwaySetting(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
    }
}
