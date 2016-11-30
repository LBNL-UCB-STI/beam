package beam.charging.policies;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import beam.transEnergySim.chargingInfrastructure.management.ChargingSitePolicy;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingLevel;
import beam.transEnergySim.chargingInfrastructure.stationary.ChargingPlugType;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;

public class ChargingSitePolicyBasic implements ChargingSitePolicy {
	double chargingPricePerKWHLevel1 = 0, chargingPricePerKWHLevel2 = 0, chargingPricePerKWHLevel3 = 0;
	double chargingPricePerHourLevel1 = 0, chargingPricePerHourLevel2 = 0, chargingPricePerHourLevel3 = 0;
	double parkingPricePerHour=0;
	boolean isMeteredByHour = true;

	public ChargingSitePolicyBasic(String extraDataAsXML) throws JDOMException, IOException{
		super();
		
		SAXBuilder saxBuilder = new SAXBuilder();
		InputStream stream = new ByteArrayInputStream(extraDataAsXML.getBytes(StandardCharsets.UTF_8));
		Document document = saxBuilder.build(stream);
		
		for(int i=0; i < document.getRootElement().getChildren().size(); i++){
			Element elem = (Element)document.getRootElement().getChildren().get(i);
			if(elem.getName().toLowerCase().equals("priceperkwh")){
				isMeteredByHour = false;
				for(int j=0; j < elem.getChildren().size(); j++){
					Element levelElem = (Element)elem.getChildren().get(j);
					if(levelElem.getName().toLowerCase().equals("level1")){
						chargingPricePerKWHLevel1 = Double.parseDouble(levelElem.getValue());
					}else if(levelElem.getName().toLowerCase().equals("level2")){
						chargingPricePerKWHLevel2 = Double.parseDouble(levelElem.getValue());
					}else if(levelElem.getName().toLowerCase().equals("level3")){
						chargingPricePerKWHLevel3 = Double.parseDouble(levelElem.getValue());
					}
				}
			}else if(elem.getName().toLowerCase().equals("priceperhour")){
				for(int j=0; j < elem.getChildren().size(); j++){
					Element levelElem = (Element)elem.getChildren().get(j);
					if(levelElem.getName().toLowerCase().equals("level1")){
						chargingPricePerHourLevel1 = Double.parseDouble(levelElem.getValue());
					}else if(levelElem.getName().toLowerCase().equals("level2")){
						chargingPricePerHourLevel2 = Double.parseDouble(levelElem.getValue());
					}else if(levelElem.getName().toLowerCase().equals("level3")){
						chargingPricePerHourLevel3 = Double.parseDouble(levelElem.getValue());
					}
				}
			}else if(elem.getName().toLowerCase().equals("parkingpriceperhour")){
				parkingPricePerHour = Double.parseDouble(elem.getValue());
			}
		}
	}
	
	@Override
	public double getParkingCost(double time, double duration) {
		return duration/3600*this.parkingPricePerHour;
	}

	@Override
	public double getChargingCost(double time, double duration, ChargingPlugType plugType, VehicleWithBattery vehicle) {
		return duration/3600.0*getCharingPricePerHour(plugType);
	}
	private double getCharingPricePerHour(ChargingPlugType plugType) {
		if(this.isMeteredByHour){
			switch(plugType.getNominalLevel()){
			case 1:
				return this.chargingPricePerHourLevel1;
			case 2:
				return this.chargingPricePerHourLevel2;
			case 3:
				return this.chargingPricePerHourLevel3;
			}
		}else{
			switch(plugType.getNominalLevel()){
			case 1:
				return this.chargingPricePerKWHLevel1;
			case 2:
				return this.chargingPricePerKWHLevel2;
			case 3:
				return this.chargingPricePerKWHLevel3;
			}
		}
		return 0.0;
	}

}
