package beam.analysis.plots;

import beam.analysis.stats.AboveCapacityPtUsageDurationInSec;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;

import java.beans.Beans;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class StatsFactory {
    enum StatsType {
        RideHailWaiting,
        RideHailingWaitingSingle,
        ModeChosen,
        PersonVehicleTransition,
        PersonTravelTime,
        PersonCost,
        RealizedMode,
        FuelUsage,
        DeadHeading,
        VehicleMilesTraveled,
        VehicleHoursTraveled,
        NumberOfVehicles,
        AgentDelay,
        AboveCapacityPtUsageDuration
    }

    private BeamConfig beamConfig;
    private BeamServices beamServices;
    private Map<StatsType, BeamStats> beamStatsMap = new HashMap<>();

    public StatsFactory(BeamServices services) {
        this.beamServices = services;
        this.beamConfig = services.beamConfig();
    }

    public BeamStats getStats(StatsType statsType) {
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
        Arrays.stream(StatsType.values()).forEach(this::getStats);
    }
    
    private BeamStats createStats(StatsType statsType) {
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
            case VehicleHoursTraveled:
                return new VehicleTravelTimeStats();
            case VehicleMilesTraveled:
                return new VehicleMilesTraveledStats();
            case NumberOfVehicles:
                return new NumberOfVehiclesStats();
            case AgentDelay:
                return new AgentDelayStats(beamServices.matsimServices().getEvents(), beamServices.matsimServices().getScenario());
            case PersonCost:
                return new PersonCostStats();
            case AboveCapacityPtUsageDuration:
                return new AboveCapacityPtUsageDurationInSec();
            default:
                return null;
        }
    }
}
