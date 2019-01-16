package beam.analysis;

import beam.analysis.plots.*;
import beam.analysis.summary.*;
import beam.sim.BeamServices;
import beam.sim.config.BeamConfig;

import java.beans.Beans;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class StatsFactory {
    public enum StatsType {
        RideHailWaiting,
        RideHailWaitingTaz,
        ModeChosen,
        PersonVehicleTransition,
        PersonTravelTime,
        PersonCost,
        RealizedMode,
        FuelUsage,
        DeadHeading,
        MotorizedVehicleMilesTraveled,
        VehicleHoursTraveled,
        NumberOfVehicles,
        AgentDelay,
        AboveCapacityPtUsageDuration,
        TollRevenue,
        AgencyRevenue
    }

    private final BeamConfig beamConfig;
    private final BeamServices beamServices;
    private Map<StatsType, BeamAnalysis> beamStatsMap = new HashMap<>();

    public StatsFactory(BeamServices services) {
        this.beamServices = services;
        this.beamConfig = services.beamConfig();
    }

    public BeamAnalysis getAnalysis(StatsType statsType) {
        BeamAnalysis stats = beamStatsMap.getOrDefault(statsType, createStats(statsType));
        beamStatsMap.putIfAbsent(statsType, stats);
        return stats;
    }

    public Collection<BeamAnalysis> getBeamAnalysis() {
        return beamStatsMap.values();
    }

    public Collection<GraphAnalysis> getGraphAnalysis() {
        return beamStatsMap.values()
                .stream()
                .filter(s -> Beans.isInstanceOf(s, GraphAnalysis.class))
                .map(s -> (GraphAnalysis) s).collect(Collectors.toList());
    }

    public Collection<IterationSummaryAnalysis> getSummaryAnalysis() {
        return beamStatsMap.values()
                .stream()
                .filter(s -> Beans.isInstanceOf(s, IterationSummaryAnalysis.class))
                .map(s -> (IterationSummaryAnalysis) s).collect(Collectors.toList());
    }

    public void createStats() {
        Arrays.stream(StatsType.values()).forEach(this::getAnalysis);
    }

    private BeamAnalysis createStats(StatsType statsType) {
        boolean writeGraphs = beamConfig.beam().outputs().writeGraphs();
        switch (statsType) {
            case RideHailWaiting:
                return new RideHailWaitingAnalysis(new RideHailWaitingAnalysis.WaitingStatsComputation(), beamConfig);
            case RideHailWaitingTaz:
                return new RideHailWaitingTazAnalysis(beamServices);
            case ModeChosen:
                return new ModeChosenAnalysis(new ModeChosenAnalysis.ModeChosenComputation(), beamConfig);
            case PersonVehicleTransition:
                return new PersonVehicleTransitionAnalysis(beamConfig);
            case FuelUsage:
                return new FuelUsageAnalysis(new FuelUsageAnalysis.FuelUsageStatsComputation(),writeGraphs);
            case PersonTravelTime:
                return new PersonTravelTimeAnalysis(new PersonTravelTimeAnalysis.PersonTravelTimeComputation(),writeGraphs);
            case RealizedMode:
                return new RealizedModeAnalysis(new RealizedModeAnalysis.RealizedModesStatsComputation(), writeGraphs);
            case DeadHeading:
                return new DeadHeadingAnalysis(writeGraphs);
            case VehicleHoursTraveled:
                return new VehicleTravelTimeAnalysis(beamServices.matsimServices().getScenario(), beamServices.vehicleTypes().keySet());
            case MotorizedVehicleMilesTraveled:
                return new MotorizedVehicleMilesTraveledAnalysis(beamServices.vehicleTypes().keySet());
            case NumberOfVehicles:
                return new NumberOfVehiclesAnalysis();
            case AgentDelay:
                return new AgentDelayAnalysis(beamServices.matsimServices().getEvents(), beamServices.matsimServices().getScenario(), beamServices.matsimServices().getControlerIO() , beamServices,beamConfig);
            case PersonCost:
                return new PersonCostAnalysis();
            case AboveCapacityPtUsageDuration:
                return new AboveCapacityPtUsageDurationAnalysis();
            case TollRevenue:
                return new TollRevenueAnalysis();
            case AgencyRevenue:
                return new AgencyRevenueAnalysis();
            default:
                return null;
        }
    }
}
