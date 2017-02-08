package beam.analysis;

import beam.utils.CSVUtil;
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

public class ChargingLoadProfile implements BeginChargingSessionEventHandler, EndChargingSessionEventHandler,
		DepartureChargingDecisionEventHandler, ArrivalChargingDecisionEventHandler, UnplugEventHandler {
	private EventsManager eventsManager;
	private FileWriter writer;

	private Double writeInteval = 15.0*60.0;
	private Double chargingLoadInKw = 0.0;
	private Integer numPluggedIn = 0;
	private double timeHour = 0;
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
		// This should be created in every iter directory
		String fileName = EVGlobalData.data.OUTPUT_DIRECTORY + File.separator + "run0.loadProfile.csv";
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
	 * Write charging load data in CSV file: load-profile-outputs.csv
	 */
	public void writeChargingLoadDataToFile(){
		// write commands
		try {
			CSVUtil.writeLine(writer, Arrays.asList(String.valueOf(timeHour), "", "", String.valueOf(chargingLoadInKw), String.valueOf(numPluggedIn)));
			writer.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Reschedule this same method to be executed in future
		EVGlobalData.data.scheduler.addCallBackMethod(EVGlobalData.data.now + writeInteval, this ,"writeChargingLoadDataToFile", 0.0, this);

		timeHour += writeInteval/60.0/60.0;
	}

	@Override
	public void reset(int iteration) {
	}

	@Override
	public void handleEvent(BeginChargingSessionEvent event) {
		numPluggedIn++;
		chargingLoadInKw += event.getChargingPowerInKw();
	}

	@Override
	public void handleEvent(EndChargingSessionEvent event) {
		chargingLoadInKw -= event.getChargingPowerInKw();
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
		numPluggedIn--;
	}

}
