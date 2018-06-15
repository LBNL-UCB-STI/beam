package beam.analysis.plots.modality;

import java.util.Map;

public class RideHailDistanceRowModel {
    private double rideHailWaitingTimeSum;
    private int totalRideHailCount;
    private double rideHailRevenue;
    private double totalSurgePricingLevel;
    private double surgePricingLevelCount;
    private double maxSurgePricingLevel;
    private int reservationCount;
    private Map<GraphType, Double> rideHailDistanceStatMap;

    public RideHailDistanceRowModel() {
        this.rideHailRevenue = 0;
        this.totalRideHailCount = 0;
        this.rideHailRevenue = 0;
    }

    public double getRideHailWaitingTimeSum() {
        return rideHailWaitingTimeSum;
    }

    public void setRideHailWaitingTimeSum(double rideHailWaitingTimeSum) {
        this.rideHailWaitingTimeSum = rideHailWaitingTimeSum;
    }

    public int getTotalRideHailCount() {
        return totalRideHailCount;
    }

    public void setTotalRideHailCount(int totalRideHailCount) {
        this.totalRideHailCount = totalRideHailCount;
    }

    public double getRideHailRevenue() {
        return rideHailRevenue;
    }

    public void setRideHailRevenue(double rideHailRevenue) {
        this.rideHailRevenue = rideHailRevenue;
    }

    public Map<GraphType, Double> getRideHailDistanceStatMap() {
        return rideHailDistanceStatMap;
    }

    public void setRideHailDistanceStatMap(Map<GraphType, Double> rideHailDistanceStatMap) {
        this.rideHailDistanceStatMap = rideHailDistanceStatMap;
    }

    public void setReservationCount(int reservationCount){
        this.reservationCount = reservationCount;
    }

    public int getReservationCount(){
        return reservationCount;
    }

    public double getTotalSurgePricingLevel() {
        return totalSurgePricingLevel;
    }

    public void setTotalSurgePricingLevel(double totalSurgePricingLevel) {
        this.totalSurgePricingLevel = totalSurgePricingLevel;
    }

    public double getSurgePricingLevelCount() {
        return surgePricingLevelCount;
    }

    public void setSurgePricingLevelCount(double surgePricingLevelCount) {
        this.surgePricingLevelCount = surgePricingLevelCount;
    }

    public double getMaxSurgePricingLevel() {
        return maxSurgePricingLevel;
    }

    public void setMaxSurgePricingLevel(double maxSurgePricingLevel) {
        this.maxSurgePricingLevel = maxSurgePricingLevel;
    }

    public enum GraphType {
        PASSENGER_VKT("passengerVKT"),
        REPOSITIONING_VKT("repositioningVKT"),
        DEAD_HEADING_VKT("deadHeadingVKT");

        private final String name;

        GraphType(final String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
