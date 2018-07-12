package beam.analysis.plots;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.plots.modality.RideHailDistanceRowModel;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author abid
 */
public class RideHailStats implements IGraphStats {

    private Logger log = LoggerFactory.getLogger(this.getClass());
    private static final String fileName = "RideHailStats";

    private Map<String, List<PathTraversalEvent>> eventMap = new HashMap<>();

    @Override
    public void resetStats() {
        eventMap.clear();
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent) {
            String vehicleId = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
            if (vehicleId.toLowerCase().contains("ridehail")) {
                List<PathTraversalEvent> list = eventMap.getOrDefault(vehicleId, new ArrayList<>());

                list.add((PathTraversalEvent) event);
                eventMap.put(vehicleId, list);
            }
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        int reservationCount = 0;
        double passengerVkt = 0d;
        double repositioningVkt = 0d;
        double deadheadingVkt = 0d;

        for (String vehicle : eventMap.keySet()) {
            List<PathTraversalEvent> list = eventMap.get(vehicle);
            int size = list.size();

            for (int loopCounter = 0; loopCounter < size; loopCounter++) {
                Map<String, String> evAttr = list.get(loopCounter).getAttributes();
                double newDistance = Double.parseDouble(evAttr.get(PathTraversalEvent.ATTRIBUTE_LENGTH));
                int numPass = Integer.parseInt(evAttr.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS));
                if (numPass == 1) {
                    if ("car".equals(evAttr.get(PathTraversalEvent.ATTRIBUTE_MODE))) {
                        reservationCount++;
                    }
                    passengerVkt += newDistance;
                } else if (numPass == 0 && loopCounter < (size - 1) && "1".equals(list.get(loopCounter + 1).getAttributes().get(PathTraversalEvent.ATTRIBUTE_NUM_PASS))) {
                    deadheadingVkt += newDistance;
                } else if (numPass == 0) {
                    repositioningVkt += newDistance;
                }
            }
        }

        RideHailDistanceRowModel model = GraphUtils.RIDE_HAIL_REVENUE_MAP.getOrDefault(event.getIteration(), new RideHailDistanceRowModel());

        model.setReservationCount(reservationCount);
        model.setPassengerVkt(passengerVkt);
        model.setDeadheadingVkt(deadheadingVkt);
        model.setRepositioningVkt(repositioningVkt);
        GraphUtils.RIDE_HAIL_REVENUE_MAP.put(event.getIteration(), model);

        writeToCSV(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {
        throw new IOException("Not implemented");
    }

    private void writeToCSV(IterationEndsEvent event) throws IOException {

        String csvFileName = event.getServices().getControlerIO().getOutputFilename(fileName + ".csv");
        try (BufferedWriter out = new BufferedWriter(new FileWriter(new File(csvFileName)))) {

            String heading = "Iteration,rideHailRevenue,averageRideHailWaitingTime,totalRideHailWaitingTime,passengerVKT,repositioningVKT,deadHeadingVKT,averageSurgePriceLevel,maxSurgePriceLevel,reservationCount";
            out.write(heading);
            out.newLine();
            for (Integer key : GraphUtils.RIDE_HAIL_REVENUE_MAP.keySet()) {
                RideHailDistanceRowModel model = GraphUtils.RIDE_HAIL_REVENUE_MAP.get(key);
                double passengerVkt = model.getPassengerVkt();
                double repositioningVkt = model.getRepositioningVkt();
                double deadheadingVkt = model.getDeadheadingVkt();
                double maxSurgePricingLevel = model.getMaxSurgePricingLevel();
                double totalSurgePricingLevel = model.getTotalSurgePricingLevel();
                double surgePricingLevelCount = model.getSurgePricingLevelCount();
                double averageSurgePricing = surgePricingLevelCount == 0 ? 0 : totalSurgePricingLevel / surgePricingLevelCount;
                int reservationCount = model.getReservationCount();
                out.append(key.toString());
                out.append(",").append(String.valueOf(model.getRideHailRevenue()));
                out.append(",").append(String.valueOf(model.getRideHailWaitingTimeSum() / model.getTotalRideHailCount()));
                out.append(",").append(String.valueOf(model.getRideHailWaitingTimeSum()));
                out.append(",").append(String.valueOf(passengerVkt / 1000));
                out.append(",").append(String.valueOf(repositioningVkt / 1000));
                out.append(",").append(String.valueOf(deadheadingVkt / 1000));
                out.append(",").append(String.valueOf(averageSurgePricing));
                out.append(",").append(String.valueOf(maxSurgePricingLevel));
                out.append(",").append(String.valueOf(reservationCount));
                out.newLine();
            }
            out.flush();
        } catch (IOException e) {
            log.error("CSV generation failed.", e);
        }
    }
}
