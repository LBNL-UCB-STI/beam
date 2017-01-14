package beam.transEnergySim.vehicles.energyConsumption.sangjae;

import beam.transEnergySim.vehicles.api.VehicleWithBattery;
import beam.transEnergySim.vehicles.energyConsumption.AbstractInterpolatedEnergyConsumptionModel;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.matsim.api.core.v01.network.Link;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;

/**
 * This class is to calculate Energy consumption of EV with the input of average speed, trip length, and average grade
 *
 //========================= <EXAMPLE> input data ===============================//
 //        HashMap<String, Double> hmInputTrip = new HashMap<>();
 //        hmInputTrip.put("linkLength", 11990d);    // link length
 //        hmInputTrip.put("linkAvgVelocity",8.75);  // link average speed
 //        hmInputTrip.put("linkAvgGrade",0d);       // link average grade
 //==============================================================================//
 *
 * Created by Sangjae Bae on 1/10/17.
 */
public class EnergyConsumptionModelSangjae extends AbstractInterpolatedEnergyConsumptionModel{

    HashMap<String, Double> hmEvParams;

    /**
     * When EV model is not given: select Nissan leaf by default
     */
    public EnergyConsumptionModelSangjae(){
    }

    /**
     * Get energy consumption in kWH
     */
    private double getEnergyConsumptionInKwh(HashMap<String, Double> hmEvParams, HashMap<String, Double> hmInputTrip) {

        //========================= <EXAMPLE> input data ===============================//
//        HashMap<String, Double> hmInputTrip = new HashMap<>();
//        hmInputTrip.put("linkLength", 11990d);    // link length
//        hmInputTrip.put("linkAvgVelocity",8.75);  // link average speed
//        hmInputTrip.put("linkAvgGrade",0d);       // link average grade
        //==============================================================================//

        // Initialize conversion/general params
        double coeff = 0.64;
        double mps2mph = 2.236936;
        double lbf2N = 4.448222;
        double lbs2kg = 0.453592;
        double gravity = 9.8;

        // Calculate average power of the itinerary
        double linkAvgVelocityMph = hmInputTrip.get("linkAvgVelocity") * mps2mph;
        double massKg = hmEvParams.get("EquivalentTestWeight") * lbs2kg;
        double powerAvgKw = ((hmEvParams.get("TargetCoefA")
                + hmEvParams.get("TargetCoefB")*linkAvgVelocityMph
                + hmEvParams.get("TargetCoefC")*Math.pow(linkAvgVelocityMph,2))*lbf2N
                + massKg * gravity * Math.sin(hmInputTrip.get("linkAvgGrade")))*hmInputTrip.get("linkAvgVelocity")/1000;
//        System.out.println("Average power (kW): " + powerAvgKw +"\n");

        // Calculate average energy consumption with the average power
        double periodS = hmInputTrip.get("linkLength")/hmInputTrip.get("linkAvgVelocity");
        double linkEnergyKwhIdeal = powerAvgKw * periodS / 3600;
        double linkEnergyKwh = linkEnergyKwhIdeal/coeff;
        System.out.println("Energy consumption (kWh): " + linkEnergyKwh + "\n");

        return linkEnergyKwh;
    }

    @Override
    public double getEnergyConsumptionRateInJoulesPerMeter() {
        return 0;
    }

    @Override
    public double getEnergyConsumptionForLinkInJoule(Link link, VehicleWithBattery vehicle, double averageSpeed) {

        // Set up input data
        double linkLength = link.getLength();  // meter
        double linkAvgGrade = Math.toRadians(Math.atan(Double.valueOf(link.getAttributes().getAttribute("gradient").toString())));
        HashMap<String, Double> hmInputTrip = new HashMap<>();
        hmInputTrip.put("linkLength", linkLength);          // link length
        hmInputTrip.put("linkAvgVelocity", averageSpeed); // link average speed
        hmInputTrip.put("linkAvgGrade",linkAvgGrade);       // link average grade

        return getEnergyConsumptionInKwh(vehicle.getEnergyConsumptionParameters(),hmInputTrip)*3600000; // in Joule (1kWh = 3600000 Joules)
    }
}
