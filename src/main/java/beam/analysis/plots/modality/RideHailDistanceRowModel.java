package beam.analysis.plots.modality;

public class RideHailDistanceRowModel {
    private double rideHailWaitingTimeSum;
    private int totalRideHailCount;
    private double rideHailRevenue;
    private double totalSurgePricingLevel;
    private double surgePricingLevelCount;
    private double maxSurgePricingLevel;
    private int reservationCount;
    private double passengerVkt;
    private double repositioningVkt;
    private double deadheadingVkt;

    public RideHailDistanceRowModel() {
        this.rideHailRevenue = 0;
        this.totalRideHailCount = 0;
        this.rideHailRevenue = 0;
        this.passengerVkt = 0d;
        this.repositioningVkt = 0d;
        this.deadheadingVkt = 0d;
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

    public int getReservationCount() {
        return reservationCount;
    }

    public void setReservationCount(int reservationCount) {
        this.reservationCount = reservationCount;
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

    public double getPassengerVkt() {
        return passengerVkt;
    }

    public void setPassengerVkt(double passengerVkt) {
        this.passengerVkt = passengerVkt;
    }

    public double getRepositioningVkt() {
        return repositioningVkt;
    }

    public void setRepositioningVkt(double repositioningVkt) {
        this.repositioningVkt = repositioningVkt;
    }

    public double getDeadheadingVkt() {
        return deadheadingVkt;
    }

    public void setDeadheadingVkt(double deadheadingVkt) {
        this.deadheadingVkt = deadheadingVkt;
    }
}
