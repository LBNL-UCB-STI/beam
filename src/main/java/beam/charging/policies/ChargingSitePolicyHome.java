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
import beam.transEnergySim.vehicles.api.Vehicle;
import beam.transEnergySim.vehicles.api.VehicleWithBattery;

public class ChargingSitePolicyHome extends ChargingSitePolicyBasic {

	public ChargingSitePolicyHome(String extraDataAsXML) throws JDOMException, IOException {
		super(extraDataAsXML);
	}


}
