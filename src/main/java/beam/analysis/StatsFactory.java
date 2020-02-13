package beam.analysis;

import beam.analysis.cartraveltime.PersonAverageTravelTimeAnalysis;
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
        VehicleMilesTraveled,
        VehicleHoursTraveled,
        NumberOfVehicles,
        AboveCapacityPtUsageDuration,
        TollRevenue,
        AgencyRevenue,
        ParkingDelay,
        RideHailUtilization,
        ParkingType,
        ActivityType,
        VehicleChargingAnalysis,
        RideHailSummary,
        LoadOverTimeAnalysis,
        ChargingAnalysis,
        PersonAverageTravelTimeAnalysis
    }

    private final BeamConfig beamConfig;
    private final BeamServices beamServices;
    private Map<StatsType, BeamAnalysis> beamStatsMap = new HashMap<>();

    public StatsFactory(BeamServices services) {
        this.beamServices = services;
        this.beamConfig = services.beamConfig();
    }

    public BeamAnalysis getAnalysis(StatsType statsType) {
        return beamStatsMap.computeIfAbsent(statsType, this::createStats);
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
                return new RealizedModeAnalysis(new RealizedModeAnalysis.RealizedModesStatsComputation(), writeGraphs, beamConfig);
            case DeadHeading:
                return new DeadHeadingAnalysis(writeGraphs);
            case VehicleHoursTraveled:
                return new VehicleTravelTimeAnalysis(beamServices.matsimServices().getScenario(),
                        beamServices.networkHelper(), beamServices.beamScenario().vehicleTypes().keySet());
            case VehicleMilesTraveled:
                return new VehicleMilesTraveledAnalysis(beamServices.beamScenario().vehicleTypes().keySet());
            case NumberOfVehicles:
                return new NumberOfVehiclesAnalysis(beamServices.beamScenario());
            case PersonCost:
                return new PersonCostAnalysis(beamServices);
            case AboveCapacityPtUsageDuration:
                return new AboveCapacityPtUsageDurationAnalysis();
            case TollRevenue:
                return new TollRevenueAnalysis();
            case AgencyRevenue:
                return new AgencyRevenueAnalysis();
            case ParkingDelay:
                return new ParkingStatsCollector(beamServices);
            case RideHailUtilization:
                return new SimpleRideHailUtilization();
            case ParkingType:
                return new ParkingTypeAnalysis(beamServices.matsimServices().getConfig().travelTimeCalculator().getMaxTime());
            case ActivityType:
                return new ActivityTypeAnalysis(beamServices.matsimServices().getConfig().travelTimeCalculator().getMaxTime());
            case VehicleChargingAnalysis:
                return new VehicleChargingAnalysis();
            case RideHailSummary:
                return new RideHailSummary();
            case LoadOverTimeAnalysis:
                return new LoadOverTimeAnalysis();
            case ChargingAnalysis:
                return new ChargingAnalysis();
            case PersonAverageTravelTimeAnalysis:
                return new PersonAverageTravelTimeAnalysis(beamConfig);
            default:
                return null;
        }
    }
}
