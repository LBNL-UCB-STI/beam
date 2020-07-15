package beam.physsim.jdeqsim.cacc.roadcapacityadjustmentfunctions;

import beam.sim.BeamConfigChangesObservable;
import beam.sim.BeamConfigChangesObserver;
import beam.sim.config.BeamConfig;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.utils.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.supercsv.io.CsvMapWriter;
import org.supercsv.io.ICsvMapWriter;
import org.supercsv.prefs.CsvPreference;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/*

CACC regression function derived from (Figure 8, Simulation):

Liu, Hao, et al. "Modeling impacts of Cooperative Adaptive Cruise Control on mixed traffic flow
in multi-lane freeway facilities." Transportation Research Part C: Emerging Technologies 95 (2018): 261-279.

 */

public class Hao2018CaccRoadCapacityAdjustmentFunction implements RoadCapacityAdjustmentFunction, BeamConfigChangesObserver {
    private final static Logger log = LoggerFactory.getLogger(Hao2018CaccRoadCapacityAdjustmentFunction.class);

    private final double caccMinRoadCapacity;
    private final double caccMinSpeedMetersPerSec;
    private final int currentIterationNumber;
    private final boolean writeGraphs;
    private final OutputDirectoryHierarchy controllerIO;
    private final MultiValuedMap<Double, Double> caccCapacityIncrease = new ArrayListValuedHashMap<>();
    private final Map<String, Double> caccLinkCapacityIncrease = new HashMap<>();
    private final Map<String, Double> allLinksCapacityIncrease = new HashMap<>();
    private final Optional<ICsvMapWriter> csvWriter;

    private int numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads = 0;
    private int numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads = 0;
    private int numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads = 0;
    private double capacityIncreaseSum = 0;
    private double percentageCapacityIncreaseSum = 0;
    private int writeInterval;
    private int nonCACCCategoryRoadsTravelled = 0;
    private int caccCategoryRoadsTravelled = 0;

    public Hao2018CaccRoadCapacityAdjustmentFunction(BeamConfig beamConfig, int iterationNumber, OutputDirectoryHierarchy controllerIO, BeamConfigChangesObservable beamConfigChangesObservable) {
        double caccMinRoadCapacity = beamConfig.beam().physsim().jdeqsim().cacc().minRoadCapacity();
        double caccMinSpeedMetersPerSec = beamConfig.beam().physsim().jdeqsim().cacc().minSpeedMetersPerSec();
        log.info("caccMinRoadCapacity: {}, caccMinSpeedMetersPerSec: {}", caccMinRoadCapacity, caccMinSpeedMetersPerSec);
        this.caccMinRoadCapacity = caccMinRoadCapacity;
        this.caccMinSpeedMetersPerSec = caccMinSpeedMetersPerSec;
        this.currentIterationNumber = iterationNumber;
        this.controllerIO = controllerIO;
        this.writeInterval = beamConfig.beam().physsim().jdeqsim().cacc().capacityPlansWriteInterval();
        this.writeGraphs = beamConfig.beam().outputs().writeGraphs();
        beamConfigChangesObservable.addObserver(this);

        csvWriter = isWriteEnabled(iterationNumber) ? getCsvWriter(iterationNumber) : Optional.empty();
    }

    public boolean isCACCCategoryRoad(Link link) {
        double initialCapacity = link.getCapacity();
        return initialCapacity >= caccMinRoadCapacity && link.getFreespeed() >= caccMinSpeedMetersPerSec;
    }

    public double getCapacityWithCACCPerSecond(Link link, double fractionCACCOnRoad, double simTime) {
        double initialCapacity = link.getCapacity();
        double updatedCapacity = initialCapacity;

        if (isCACCCategoryRoad(link)) {
            caccCategoryRoadsTravelled++;
            updatedCapacity = calculateCapacity(fractionCACCOnRoad, initialCapacity);

            if (fractionCACCOnRoad == 1) {
                numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads++;
            }

            if (fractionCACCOnRoad == 0) {
                numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads++;
            }

            if (fractionCACCOnRoad > 0 && fractionCACCOnRoad <= 1.0) {
                numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads++;
                capacityIncreaseSum += updatedCapacity - initialCapacity;
                percentageCapacityIncreaseSum += (updatedCapacity / initialCapacity - 1.0);
            }


            if (updatedCapacity < initialCapacity) {
                log.error("updatedCapacity (" + updatedCapacity + ") is lower than initialCapacity (" + initialCapacity + ").");
            }

            double finalUpdatedCapacity = updatedCapacity;
            csvWriter.ifPresent(writer -> {
                Map<String, String> row = new HashMap<>();
                row.put("linkId", link.getId().toString());
                row.put("fractionCACCOnRoad", Double.toString(fractionCACCOnRoad));
                row.put("initialCapacity", Double.toString(initialCapacity));
                row.put("updatedCapacity", Double.toString(finalUpdatedCapacity));
                try {
                    writer.write(row, "linkId", "fractionCACCOnRoad", "initialCapacity", "updatedCapacity");
                } catch (Exception ex) {
                    log.error("Could not write", ex);
                }
            });

            double capacityIncreaseForCACCEnabledRoads = (updatedCapacity / initialCapacity) - 1.0;
            caccCapacityIncrease.put(fractionCACCOnRoad * 100.0, capacityIncreaseForCACCEnabledRoads * 100.0);
            caccLinkCapacityIncrease.put(link.getId().toString(), capacityIncreaseForCACCEnabledRoads * 100.0);

        } else {
            nonCACCCategoryRoadsTravelled++;
        }

        double capacityIncreaseForAllRoads = (updatedCapacity / initialCapacity) - 1.0;
        allLinksCapacityIncrease.put(link.getId().toString(), capacityIncreaseForAllRoads * 100.0);

        return updatedCapacity / 3600;
    }

    double calculateCapacity(double fractionCACCOnRoad, double initialCapacity) {
        return (2152.777778 * fractionCACCOnRoad * fractionCACCOnRoad * fractionCACCOnRoad - 764.8809524 * fractionCACCOnRoad * fractionCACCOnRoad + 456.1507937 * fractionCACCOnRoad + 1949.047619) / 1949.047619 * initialCapacity;
    }

    public void printStats() {
        log.info("average road capacity increase: " + capacityIncreaseSum / numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads);
        log.info("average road capacity increase (%): " + percentageCapacityIncreaseSum / numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads * 100.0);
        log.info("number of mixed vehicle type encounters (non-CACC/CACC) on CACC category roads: " + numberOfMixedVehicleTypeEncountersOnCACCCategoryRoads);
        log.info("numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads: " + numberOfTimesOnlyCACCTravellingOnCACCEnabledRoads);
        log.info("numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads: " + numberOfTimesOnlyNonCACCTravellingOnCACCEnabledRoads);
        log.info("caccCategoryRoadsTravelled / nonCACCCategoryRoadsTravelled ratio: " + 1.0 * caccCategoryRoadsTravelled / nonCACCCategoryRoadsTravelled);
        if (writeGraphs) {
            CaccRoadCapacityGraphs.generateCapacityIncreaseScatterPlotGraph(caccCapacityIncrease,
                    controllerIO.getIterationFilename(currentIterationNumber, "caccRoadCapacityIncrease.png"));
            CaccRoadCapacityGraphs.generateCapacityIncreaseHistogramGraph(caccLinkCapacityIncrease,
                    controllerIO.getIterationFilename(currentIterationNumber, "caccRoadCapacityHistogram.png"),
                    "CACC Roads Capacity Increase Histogram");
            CaccRoadCapacityGraphs.generateCapacityIncreaseHistogramGraph(allLinksCapacityIncrease,
                    controllerIO.getIterationFilename(currentIterationNumber, "allCategoryRoadCapacityHistogram.png"),
                    "All Category Roads Capacity Increase Histogram");
        }
    }

    private boolean isWriteEnabled(int iterationNumber) {
        return (writeInterval > 0 && iterationNumber % writeInterval == 0);
    }

    public void reset() {
        caccCapacityIncrease.clear();
        csvWriter.ifPresent(writer -> {
            try {
                writer.close();
            } catch (Exception ex) {
                log.warn("Error closing the writer", ex);
            }
        });
    }

    private Optional<ICsvMapWriter> getCsvWriter(int iterationNumber) {
        try {
            String filePath = controllerIO.getIterationFilename(iterationNumber, "caccCapacityStats.csv.gz");
            CsvMapWriter csvMapWriter = new CsvMapWriter(IOUtils.getBufferedWriter(filePath), CsvPreference.STANDARD_PREFERENCE);
            csvMapWriter.writeHeader("linkId", "fractionCACCOnRoad", "initialCapacity", "updatedCapacity");
            csvMapWriter.flush();
            return Optional.of(csvMapWriter);
        } catch (Exception ex) {
            log.error("Could not create CsvMapWriter", ex);
            return Optional.empty();
        }
    }

    @Override
    public void update(BeamConfigChangesObservable observable, BeamConfig updatedBeamConfig) {
        this.writeInterval = updatedBeamConfig.beam().physsim().jdeqsim().cacc().capacityPlansWriteInterval();
    }
}

