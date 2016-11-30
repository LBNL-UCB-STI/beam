package beam.charging.vehicle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import org.matsim.api.core.v01.Id;
import org.matsim.core.utils.io.tabularFileParser.TabularFileHandler;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParser;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParserConfig;

import beam.EVGlobalData;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.vehicles.energyConsumption.EnergyConsumptionModel;
import beam.transEnergySim.vehicles.energyConsumption.galus.EnergyConsumptionModelGalus;
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
			public HashMap<String,Integer> headerMap;
			@Override
			public void startRow(String[] row) {
				if(headerMap==null){
					headerMap = new HashMap<String,Integer>();
					for(int i =0; i<row.length; i++){
						String colName = row[i].toLowerCase();
						if(colName.startsWith("\"")){
							colName = colName.substring(1, colName.length()-1);
						}
						headerMap.put(colName, i);
					}
				}else{
					String newId = row[headerMap.get("id")];
					EnergyConsumptionModel electricConsumptionModel = null, petroleumConsumptionModel = null;
					// TODO Replace with final energy consumption model(s)
					if(row[headerMap.get("electricenergyconsumptionmodelclassname")].trim().equals("EnergyConsumptionModelRicardoFaria2012")){
						electricConsumptionModel = new EnergyConsumptionModelRicardoFaria2012();
					}else{
						throw new RuntimeException("Cannot find class that inherits EnergyConsumptionModel named "+row[headerMap.get("electricenergyconsumptionmodelclassname")]);
					}
					if(row[headerMap.get("petroleumenergyconsumptionmodelclassname")].trim().equals("EnergyConsumptionModelGalus")){
						petroleumConsumptionModel = new EnergyConsumptionModelGalus();
					}else if(!row[headerMap.get("petroleumenergyconsumptionmodelclassname")].trim().equals("")){
						throw new RuntimeException("Cannot find class that inherits EnergyConsumptionModel named "+row[headerMap.get("petroleumenergyconsumptionmodel")]);
					}
			
					LinkedHashSet<ChargingPlugType> compatiblePlugTypes = new LinkedHashSet<ChargingPlugType>();
					for(String plugTypeName : row[headerMap.get("compatibleplugtypesasspaceseparatedtext")].trim().split(" ")){
						compatiblePlugTypes.add(EVGlobalData.data.chargingInfrastructureManager.getChargingPlugTypeByName(plugTypeName.toLowerCase()));
					}
					
					LinkedHashMap<String,Object> vehicleProperties = new LinkedHashMap<>();
					vehicleProperties.put("batterycapacityinkwh",Double.parseDouble(row[headerMap.get("batterycapacityinkwh")].trim()));
					vehicleProperties.put("maxdischargingpowerinkw",Double.parseDouble(row[headerMap.get("maxdischargingpowerinkw")].trim()));
					vehicleProperties.put("maxlevel2chargingpowerinkw",Double.parseDouble(row[headerMap.get("maxlevel2chargingpowerinkw")].trim()));
					vehicleProperties.put("maxlevel3chargingpowerinkw",Double.parseDouble(row[headerMap.get("maxlevel3chargingpowerinkw")].trim()));
					vehicleProperties.put("compatibleplugtypes",compatiblePlugTypes);
					vehicleProperties.put("vehicleclassname",row[headerMap.get("vehicleclassname")].trim());
					vehicleProperties.put("vehicletypename",row[headerMap.get("vehicletypename")].trim());
					vehicleProperties.put("electricenergyconsumptionmodel",electricConsumptionModel);
					vehicleProperties.put("petroleumenergyconsumptionmodel",petroleumConsumptionModel);
					
					EVGlobalData.data.vehiclePropertiesMap.put(row[headerMap.get("id")].trim(),vehicleProperties);
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
			public HashMap<String,Integer> headerMap;
			@Override
			public void startRow(String[] row) {
				if(headerMap==null){
					headerMap = new HashMap<String,Integer>();
					for(int i =0; i<row.length; i++){
						String colName = row[i].toLowerCase();
						if(colName.startsWith("\"")){
							colName = colName.substring(1, colName.length()-1);
						}
						headerMap.put(colName, i);
					}
				}else{
					String personIdString = row[headerMap.get("personid")].trim();
					EVGlobalData.data.personToVehicleTypeMap.put(personIdString,row[headerMap.get("vehicletypeid")].trim());
					LinkedHashMap<String,String> homeProperties = new LinkedHashMap<>();
					homeProperties.put("homeChargingPlugTypeId",(row.length >= headerMap.get("homechargingplugtypeid") + 1) ? row[headerMap.get("homechargingplugtypeid")].trim() : "" );
					homeProperties.put("homeChargingPolicyId",(row.length >= headerMap.get("homechargingpolicyid") + 1) ? row[headerMap.get("homechargingpolicyid")].trim() : "" );
					homeProperties.put("homeChargingNetworkOperatorId",(row.length >= headerMap.get("homechargingnetworkoperatorid") + 1) ? row[headerMap.get("homechargingnetworkoperatorid")].trim() : "" );
					EVGlobalData.data.personHomeProperties.put(personIdString,homeProperties);
					EVGlobalData.data.simulationStartSocFraction.put(Id.createPersonId(personIdString),Double.parseDouble((row.length >= headerMap.get("simulationstartsocfraction") + 1) ? row[headerMap.get("simulationstartsocfraction")].trim() : "1.0" ));
				}
			}
		};
		fileParser.parse(fileParserConfig, handler);
		
	}
	

}
