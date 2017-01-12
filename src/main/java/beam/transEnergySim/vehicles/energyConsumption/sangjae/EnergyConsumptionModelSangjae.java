package beam.transEnergySim.vehicles.energyConsumption.sangjae;

import beam.transEnergySim.vehicles.energyConsumption.AbstractInterpolatedEnergyConsumptionModel;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

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
        // Get EV parameters
        initModel(null);
    }

    /**
     * When EV model is given
     * @param evModel
     */
    public EnergyConsumptionModelSangjae(String evModel){
        // Get EV parameters
        initModel(evModel);
    }

    /**
     * Initialize consumption model
     */
    private void initModel(String evModel) {
        // Load EV params; make sure to dynamically designate the model file path (from Google drive? dropBox?)
        String fPath ="/Users/mygreencar/Google Drive/beam-developers/model-inputs/vehicles/electric-vehicle-params.xlsx";
        hmEvParams = loadEvParams(fPath, evModel);
        System.out.println(hmEvParams.toString());
    }

    /**
     * Load params used to calculate Energy consumptions of EV models
     */
    private HashMap<String,Double> loadEvParams(String fPath, String evModel) {
        HashMap<String,Double> hmEvParams = new HashMap<>();

        try{
            FileInputStream file = new FileInputStream(new File(fPath));

            // Create Workbook instance holding reference to .xlsx file
            XSSFWorkbook workbook = new XSSFWorkbook(file);

            // Get first/desired sheet from the workbook
            XSSFSheet sheet = workbook.getSheetAt(0);

            // Get the row number for the given ev Model;
            // - select Nissan leaf if no EV model is given
            // - select Nissan leaf if the given EV model does not exist in the ev data sheet
            int rowNumForEv = sheet.getLastRowNum();
            if(evModel != null){ // if EV model is given
                for(int i = 1; i<sheet.getLastRowNum();i++){
                    if(sheet.getRow(i).getCell(1).getStringCellValue().contains(evModel)){
                        rowNumForEv = i;
                        break;
                    }
                }
            }
            System.out.println("\nRow num for EV: " + rowNumForEv);
            System.out.println("Selected EV model: " + sheet.getRow(rowNumForEv).getCell(1).getStringCellValue() + "\n");

            // Parse params of the selected EV from the sheet
            double mass = Double.valueOf(sheet.getRow(rowNumForEv).getCell(4).getRawValue());
            double coefA = Double.valueOf(sheet.getRow(rowNumForEv).getCell(5).getRawValue());
            double coefB = Double.valueOf(sheet.getRow(rowNumForEv).getCell(6).getRawValue());
            double coefC = Double.valueOf(sheet.getRow(rowNumForEv).getCell(7).getRawValue());

            // Put params in hashMap
            hmEvParams.put("mass",mass);
            hmEvParams.put("coefA",coefA);
            hmEvParams.put("coefB",coefB);
            hmEvParams.put("coefC",coefC);

            // Do not forget to close the file after use
            file.close();
        }catch (Exception e){
            e.printStackTrace();
        }

        // Return the EV parameters; it returns an empty HashMap if the input file does not exist or inaccessible. A crash will occur.
        return hmEvParams;
    }

    /**
     * Get energy consumption
     */
    public double getEnergyConsumption(HashMap<String, Double> hmInputTrip) {

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
        double massKg = hmEvParams.get("mass") * lbs2kg;
        double powerAvgKw = ((hmEvParams.get("coefA")
                + hmEvParams.get("coefB")*linkAvgVelocityMph
                + hmEvParams.get("coefC")*Math.pow(linkAvgVelocityMph,2))*lbf2N
                + massKg * gravity * Math.sin(hmInputTrip.get("linkAvgGrade")))*hmInputTrip.get("linkAvgVelocity")/1000;
        System.out.println("Average power (kW): " + powerAvgKw +"\n");

        // Calculate average energy consumption with the average power
        double periodS = hmInputTrip.get("linkLength")/hmInputTrip.get("linkAvgVelocity");
        double linkEnergyKwhIdeal = powerAvgKw * periodS / 3600;
        double linkEnergyKwh = linkEnergyKwhIdeal/coeff;
        System.out.println("Energy consumption (kWh): " + linkEnergyKwh + "\n");

        return linkEnergyKwh;
    }

    /**
     * NOT SURE HOW IT FUNCTIONS, YET, JAN 11.
     * @return
     */
    @Override
    public double getEnergyConsumptionRateInJoulesPerMeter() {
        return 0;
    }
}
