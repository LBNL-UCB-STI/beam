package beam.analysis.plots;

import beam.sim.config.BeamConfig;

import java.beans.Beans;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class StatsFactory {
    public static final String RideHailWaiting = "RideHailWaiting";
    public static final String RideHailingWaitingSingle = "RideHailingWaitingSingle";
    public static final String ModeChosen = "ModeChosen";
    public static final String PersonVehicleTransition = "PersonVehicleTransition";
    public static final String FuelUsage = "FuelUsage";
    public static final String PersonTravelTime = "PersonTravelTime";
    public static final String RealizedMode = "RealizedMode";
    public static final String DeadHeading = "DeadHeading";
    public static final String VehicleMilesTraveled = "VehicleMilesTraveled";
    public static final String NumberOfVehicles = "NumberOfVehicles";
    public static final String PersonCost = "PersonCost";

    private BeamConfig beamConfig;
    private Map<String, BeamStats> beamStatsMap = new HashMap<>();

    public StatsFactory(BeamConfig beamConfig) {
        this.beamConfig = beamConfig;
    }

    public BeamStats getStats(String statsType) {
        BeamStats stats = beamStatsMap.getOrDefault(statsType, createStats(statsType));
        beamStatsMap.putIfAbsent(statsType, stats);
        return stats;
    }

    public Collection<BeamStats> getBeamStats() {
        return beamStatsMap.values();
    }

    public Collection<IterationSummaryStats> getSummaryStats() {
        return beamStatsMap.values().stream().filter(s -> Beans.isInstanceOf(s, IterationSummaryStats.class)).map(s -> (IterationSummaryStats)s).collect(Collectors.toList());
    }

    public void createStats() {
        getStats(DeadHeading);
        getStats(StatsFactory.FuelUsage);
        getStats(StatsFactory.PersonTravelTime);
        getStats(StatsFactory.RideHailWaiting);
        getStats(StatsFactory.RideHailingWaitingSingle);
        getStats(StatsFactory.ModeChosen);
        getStats(StatsFactory.PersonVehicleTransition);
        getStats(StatsFactory.RealizedMode);
        getStats(StatsFactory.VehicleMilesTraveled);
        getStats(StatsFactory.NumberOfVehicles);
        getStats(StatsFactory.PersonCost);
    }
    
    private BeamStats createStats(String statsType) {
        switch (statsType) {
            case RideHailWaiting:
                return new RideHailWaitingStats(new RideHailWaitingStats.WaitingStatsComputation(), beamConfig);
            case RideHailingWaitingSingle:
                return new RideHailingWaitingSingleStats(beamConfig, new RideHailingWaitingSingleStats.RideHailingWaitingSingleComputation());
            case ModeChosen:
                return new ModeChosenStats(new ModeChosenStats.ModeChosenComputation(), beamConfig);
            case PersonVehicleTransition:
                return new PersonVehicleTransitionStats(beamConfig);
            case FuelUsage:
                return new FuelUsageStats(new FuelUsageStats.FuelUsageStatsComputation());
            case PersonTravelTime:
                return new PersonTravelTimeStats(new PersonTravelTimeStats.PersonTravelTimeComputation());
            case RealizedMode:
                return new RealizedModeStats(new RealizedModeStats.RealizedModesStatsComputation());
            case DeadHeading:
                return new DeadHeadingStats();
            case VehicleMilesTraveled:
                return new VehicleMilesTraveledStats();
            case NumberOfVehicles:
                return new NumberOfVehiclesStats();
            case PersonCost:
                return new PersonCostStats();
            default:
                return null;
        }
    }
}
