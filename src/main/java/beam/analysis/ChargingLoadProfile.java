package beam.analysis;

import beam.utils.CSVUtil;
import beam.utils.MathUtil;
import org.matsim.core.api.experimental.events.EventsManager;

import com.google.inject.Inject;

import beam.EVGlobalData;
import beam.events.ArrivalChargingDecisionEvent;
import beam.events.ArrivalChargingDecisionEventHandler;
import beam.events.BeginChargingSessionEvent;
import beam.events.BeginChargingSessionEventHandler;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.DepartureChargingDecisionEventHandler;
import beam.events.EndChargingSessionEvent;
import beam.events.EndChargingSessionEventHandler;
import beam.events.UnplugEvent;
import beam.events.UnplugEventHandler;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

//TODO: perform test that disaggregate charging load profile is consistent with the profile in the event file
public class ChargingLoadProfile implements BeginChargingSessionEventHandler, EndChargingSessionEventHandler,
		DepartureChargingDecisionEventHandler, ArrivalChargingDecisionEventHandler, UnplugEventHandler {
	private EventsManager eventsManager;
	private FileWriter writer;

	private Double writeInteval = 15.0*60.0; 	// time interval of tracking charging load and number of plugged-in vehicles
	private Double chargingLoadInKw = 0.0;	 	// aggregate charging load
	private Integer numPluggedIn = 0; 			// aggregate number of plugged-in vehicles
	private List<String> chargingLoadFileHeader = Arrays.asList("time","spatial.group","charger.type","charging.load.in.kw","num.plugged.in");

	@Inject
	public ChargingLoadProfile(EventsManager eventsManager) {
		this.eventsManager = eventsManager;
		this.eventsManager.addHandler(this);
		this.writer = initFileWriter();

		EVGlobalData.data.scheduler.addCallBackMethod(0.0, this ,"writeChargingLoadDataToFile", 0.0, this);
	}

	/**
	 * Initialize charging load csv file
	 */
	private FileWriter initFileWriter() {
		//TODO This should be created in every iter directory
		String fileName = EVGlobalData.data.OUTPUT_DIRECTORY + File.separator + "run0.aggregateLoadProfile.csv";
		try {
			FileWriter writer = new FileWriter(fileName);
			CSVUtil.writeLine(writer, chargingLoadFileHeader);
			return writer;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Write charging load data in CSV file: loadProfile.csv
	 */
	public void writeChargingLoadDataToFile(){
		// Log aggregate plugged-in num and charging load
		try {
			CSVUtil.writeLine(writer, Arrays.asList(String.valueOf(EVGlobalData.data.now/3600.0), "", "", String.valueOf(chargingLoadInKw), String.valueOf(numPluggedIn)));
			writer.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		//TODO: log disaggregate plugged-in num and charging load

		// Reschedule this same method to be executed in future
		EVGlobalData.data.scheduler.addCallBackMethod(MathUtil.roundUpToNearestInterval(EVGlobalData.data.now + writeInteval,writeInteval), this ,"writeChargingLoadDataToFile", 0.0, this);
	}

	@Override
	public void reset(int iteration) {
	}

	@Override
	public void handleEvent(BeginChargingSessionEvent event) {
		// Aggregate
		numPluggedIn++;
		chargingLoadInKw += event.getChargingPowerInKw();

		// Disaggregate
		event.getChargingSiteSpatialGroup().addChargingLoadInKw(event.getNominalChargingLevel(),event.getChargingPowerInKw()); // power
		event.getChargingSiteSpatialGroup().addNumPluggedIn(event.getNominalChargingLevel(),1); // number of plugged-in vehicle
	}

	@Override
	public void handleEvent(EndChargingSessionEvent event) {
		// Aggregate
		chargingLoadInKw -= event.getChargingPowerInKw();

		// Disaggregate
		event.getChargingSiteSpatialGroup().addChargingLoadInKw(event.getNominalChargingLevel(),event.getChargingPowerInKw()); // power
	}

	@Override
	public void handleEvent(DepartureChargingDecisionEvent event) {
		// Do nothing
	}

	@Override
	public void handleEvent(ArrivalChargingDecisionEvent event) {
		// Do nothing
	}

	@Override
	public void handleEvent(UnplugEvent event) {
		// Aggregate
		numPluggedIn--;

		// Disaggregate
		event.getChargingSiteSpatialGroup().addNumPluggedIn(event.getNominalChargingLevel(),-1);
	}

}
