package beam.charging.vehicle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import beam.transEnergySim.vehicles.energyConsumption.EnergyConsumption;

import beam.utils.CSVUtil;
import org.matsim.api.core.v01.Id;
import org.matsim.core.utils.io.tabularFileParser.TabularFileHandler;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParser;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParserConfig;

import beam.EVGlobalData;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.vehicles.energyConsumption.EnergyConsumptionModel;
import beam.transEnergySim.vehicles.energyConsumption.EnergyConsumptionModelConstant;
import beam.transEnergySim.vehicles.energyConsumption.galus.EnergyConsumptionModelGalus;
import beam.transEnergySim.vehicles.energyConsumption.myGreenCar.EnergyConsumptionModelMyGreenCar;
import beam.transEnergySim.vehicles.energyConsumption.ricardoFaria2012.EnergyConsumptionModelRicardoFaria2012;

public class ParseVehicleTypes {
	
	public static void vehicleTypeLoader(){
		EVGlobalData.data.vehiclePropertiesMap = new LinkedHashMap<String,LinkedHashMap>();
		EVGlobalData.data.personToVehicleTypeMap = new LinkedHashMap<String,String>();
		EVGlobalData.data.personHomeProperties = new LinkedHashMap<String,LinkedHashMap<String,String>>();
		TabularFileParser fileParser = new TabularFileParser();
		TabularFileParserConfig fileParserConfig = new TabularFileParserConfig();

		/*
		 * PARSE VEHICLE TYPES
		 */
		fileParserConfig.setFileName(EVGlobalData.data.VEHICLE_TYPES_FILEPATH);
		fileParserConfig.setDelimiterRegex(",");
		TabularFileHandler handler = new TabularFileHandler() {
			public LinkedHashMap<String,Integer> headerMap;
			@Override
			public void startRow(String[] row) {
				if(headerMap==null){
					headerMap = new LinkedHashMap<String,Integer>();
					for(int i =0; i<row.length; i++){
						String colName = row[i].toLowerCase();
						if(colName.startsWith("\"")){
							colName = colName.substring(1, colName.length()-1);
						}
						headerMap.put(colName, i);
					}
				}else{
					String newId = CSVUtil.getValue("id",row,headerMap);
					EnergyConsumptionModel electricConsumptionModel = null, petroleumConsumptionModel = null;
					// TODO Replace with final energy consumption model(s)
					if(CSVUtil.getValue("electricenergyconsumptionmodelclassname",row,headerMap).equals("EnergyConsumptionModelRicardoFaria2012")){
						electricConsumptionModel = new EnergyConsumptionModelRicardoFaria2012();
					}else if(CSVUtil.getValue("electricenergyconsumptionmodelclassname",row,headerMap).equals("EnergyConsumptionModelMyGreenCar")){
						electricConsumptionModel = new EnergyConsumptionModelMyGreenCar();
					}else if(CSVUtil.getValue("electricenergyconsumptionmodelclassname",row,headerMap).equals("EnergyConsumptionModelConstant")){
						electricConsumptionModel = new EnergyConsumptionModelConstant();
					}else{
						throw new RuntimeException("Cannot find class that inherits EnergyConsumptionModel named "+CSVUtil.getValue("electricenergyconsumptionmodelclassname",row,headerMap));
					}
					if(CSVUtil.getValue("petroleumenergyconsumptionmodelclassname",row,headerMap).equals("EnergyConsumptionModelGalus")){
						petroleumConsumptionModel = new EnergyConsumptionModelGalus();
					}else if(!CSVUtil.getValue("petroleumenergyconsumptionmodelclassname",row,headerMap).equals("")){
						throw new RuntimeException("Cannot find class that inherits EnergyConsumptionModel named "+CSVUtil.getValue("petroleumenergyconsumptionmodel",row,headerMap));
					}
			
					LinkedHashSet<ChargingPlugType> compatiblePlugTypes = new LinkedHashSet<ChargingPlugType>();
					for(String plugTypeName : CSVUtil.getValue("compatibleplugtypesasspaceseparatedtext",row,headerMap).split(" ")){
						compatiblePlugTypes.add(EVGlobalData.data.chargingInfrastructureManager.getChargingPlugTypeByName(plugTypeName.toLowerCase()));
					}
					
					LinkedHashMap<String,Object> vehicleProperties = new LinkedHashMap<>();
					vehicleProperties.put("batterycapacityinkwh",Double.parseDouble(CSVUtil.getValue("batterycapacityinkwh",row,headerMap)));
					vehicleProperties.put("maxdischargingpowerinkw",Double.parseDouble(CSVUtil.getValue("maxdischargingpowerinkw",row,headerMap)));
					vehicleProperties.put("maxlevel2chargingpowerinkw",Double.parseDouble(CSVUtil.getValue("maxlevel2chargingpowerinkw",row,headerMap)));
					vehicleProperties.put("maxlevel3chargingpowerinkw",Double.parseDouble(CSVUtil.getValue("maxlevel3chargingpowerinkw",row,headerMap)));
					vehicleProperties.put("compatibleplugtypes",compatiblePlugTypes);
					vehicleProperties.put("vehicleclassname",CSVUtil.getValue("vehicleclassname",row,headerMap));
					vehicleProperties.put("vehicletypename",CSVUtil.getValue("vehicletypename",row,headerMap));
					vehicleProperties.put("electricenergyconsumptionmodel",electricConsumptionModel);
					vehicleProperties.put("petroleumenergyconsumptionmodel",petroleumConsumptionModel);

					if(headerMap.get("equivalenttestweight")==null){
						vehicleProperties.put("equivalenttestweight",String.valueOf(3746));
						vehicleProperties.put("targetcoefa",String.valueOf(41.06));
						vehicleProperties.put("targetcoefb",String.valueOf(-0.3082));
						vehicleProperties.put("targetcoefc",String.valueOf(0.02525));
					}else{
						vehicleProperties.put("equivalenttestweight",CSVUtil.getValue("equivalenttestweight",row,headerMap));
						vehicleProperties.put("targetcoefa",CSVUtil.getValue("targetcoefa",row,headerMap));
						vehicleProperties.put("targetcoefb",CSVUtil.getValue("targetcoefb",row,headerMap));
						vehicleProperties.put("targetcoefc",CSVUtil.getValue("targetcoefc",row,headerMap));
					}
					vehicleProperties.put("fueleconomyinkwhpermile",CSVUtil.getValue("fueleconomyinkwhpermile",row,headerMap));
					
					EVGlobalData.data.vehiclePropertiesMap.put(CSVUtil.getValue("id",row,headerMap),vehicleProperties);
				}
			}
		};
		fileParser.parse(fileParserConfig, handler);
		
		/*
		 * PARSE PERSON <-> VEHICLE TYPE MAPPING
		 */
		fileParserConfig.setFileName(EVGlobalData.data.PERSON_VEHICLE_TYPES_FILEPATH);
		fileParserConfig.setDelimiterRegex(",");
		handler = new TabularFileHandler() {
			public LinkedHashMap<String,Integer> headerMap;
			@Override
			public void startRow(String[] row) {
				if(headerMap==null){
					headerMap = new LinkedHashMap<String,Integer>();
					for(int i =0; i<row.length; i++){
						String colName = row[i].toLowerCase();
						if(colName.startsWith("\"")){
							colName = colName.substring(1, colName.length()-1);
						}
						headerMap.put(colName, i);
					}
				}else{
					String personIdString = CSVUtil.getValue("personid",row,headerMap);
					EVGlobalData.data.personToVehicleTypeMap.put(personIdString,CSVUtil.getValue("vehicletypeid",row,headerMap));
					LinkedHashMap<String,String> homeProperties = new LinkedHashMap<>();
					homeProperties.put("homeChargingPlugTypeId",(row.length >= headerMap.get("homechargingplugtypeid") + 1) ? CSVUtil.getValue("homechargingplugtypeid",row,headerMap) : "" );
					homeProperties.put("homeChargingPolicyId",(row.length >= headerMap.get("homechargingpolicyid") + 1) ? CSVUtil.getValue("homechargingpolicyid",row,headerMap) : "" );
					homeProperties.put("homeChargingNetworkOperatorId",(row.length >= headerMap.get("homechargingnetworkoperatorid") + 1) ? CSVUtil.getValue("homechargingnetworkoperatorid",row,headerMap) : "" );
					homeProperties.put("homeChargingSpatialGroup",CSVUtil.getValue("spatialgroup",row,headerMap));
					homeProperties.put("useInCalibration",CSVUtil.getValue("useincalibration",row,headerMap));
					EVGlobalData.data.personHomeProperties.put(personIdString,homeProperties);
					EVGlobalData.data.simulationStartSocFraction.put(Id.createPersonId(personIdString),Double.parseDouble((row.length >= headerMap.get("simulationstartsocfraction") + 1) ? CSVUtil.getValue("simulationstartsocfraction",row,headerMap) : "1.0" ));
				}
			}
		};
		fileParser.parse(fileParserConfig, handler);
		
	}
	

}
