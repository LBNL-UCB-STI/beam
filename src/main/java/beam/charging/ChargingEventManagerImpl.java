package beam.charging;

import java.util.HashMap;
import java.util.HashSet;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.mobsim.qsim.QSim;

import beam.EVGlobalData;
import beam.controller.EVController;
import beam.transEnergySim.events.ChargingEventManager;
import beam.transEnergySim.vehicles.api.Vehicle;

public class ChargingEventManagerImpl extends ChargingEventManager {

	public ChargingEventManagerImpl(HashMap<Id<Person>, Id<Vehicle>> personToVehicleMapping, EVController controler, HashSet<String> travelModeFilter) {
		super(personToVehicleMapping, controler, travelModeFilter);
	}

	// TODO: figure out, if this can be packed into listeners in abstract class!
	public void addEngine(QSim qsim) {
		qsim.addMobsimEngine(delegate);
	}

}
