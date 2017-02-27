package beam.analysis;

import beam.charging.infrastructure.ChargingInfrastructureManagerImpl;
import beam.utils.CSVUtil;
import beam.utils.MathUtil;
import org.apache.log4j.Logger;
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
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

//TODO: perform test that disaggregate charging load profile is consistent with the profile in the event file
public class ChargingLoadProfile implements BeginChargingSessionEventHandler, EndChargingSessionEventHandler,
		DepartureChargingDecisionEventHandler, ArrivalChargingDecisionEventHandler, UnplugEventHandler, BeforeMobsimListener {
	private static final Logger log = Logger.getLogger(ChargingLoadProfile.class);
	private EventsManager eventsManager;
	private FileWriter aggWriter;
	private FileWriter disaggWriter;
	private HashMap<String,HashMap<String, HashMap<String, Integer>>> numPluggedinMap = new HashMap<>();
	private HashMap<String,HashMap<String, HashMap<String, Double>>> chargingLoadInKwMap = new HashMap<>();

	private Double writeInterval = 15.0*60.0; 	// time interval of tracking charging load and number of plugged-in vehicles
	private Double chargingLoadInKw = 0.0;	 	// aggregate charging load
	private Integer numPluggedIn = 0; 			// aggregate number of plugged-in vehicles
	private List<String> aggChargingLoadFileHeader = Arrays.asList("time","spatial.group","charger.type","charging.load.in.kw","num.plugged.in");
	private List<String> disaggChargingLoadFileHeader = Arrays.asList("time","spatial.group","site.type","charger.type","charging.load.in.kw","num.plugged.in");

	@Inject
	public ChargingLoadProfile(EventsManager eventsManager) {
		this.eventsManager = eventsManager;
		this.eventsManager.addHandler(this);
	}

	/**
	 * Initialize charging load csv file
	 */
	private FileWriter initAggFileWriter(int iteration) {
		String fileName = EVGlobalData.data.OUTPUT_DIRECTORY + File.separator
				+ "ITERS" + File.separator + "it." + iteration +File.separator
				+"run0."+iteration + ".aggregateLoadProfile.csv";
		try {
			FileWriter writer = new FileWriter(fileName);
			CSVUtil.writeLine(writer, aggChargingLoadFileHeader);
			log.warn(fileName + " has Created and returned writer!");
			return writer;
		} catch (IOException e) {
			e.printStackTrace();
			log.warn(fileName + " has Created but we see the error!!!");
			return null;
		}
	}

	/**
	 * Initialize charging load csv file
	 */
	private FileWriter initDisaggFileWriter(int iteration) {
		String fileName = EVGlobalData.data.OUTPUT_DIRECTORY + File.separator
				+ "ITERS" + File.separator + "it." + iteration + File.separator
				+"run0."+iteration + ".disaggregateLoadProfile.csv";
		try {
			FileWriter writer = new FileWriter(fileName);
			CSVUtil.writeLine(writer, disaggChargingLoadFileHeader);
			log.warn(fileName + " has Created and returned writer!");
			return writer;
		} catch (IOException e) {
			e.printStackTrace();
			log.warn(fileName + " has Created but we see the error!!!");
			return null;
		}
	}

	/**
	 * Get iteration number
	 * @return
	 */
	private int getIterationNumber() {
		int count = 0;
		for(File file : EVGlobalData.data.OUTPUT_DIRECTORY.listFiles()){
			if(file.getName().contains("disaggregateLoadProfile")) count++;
		}
		return count;
	}

	/**
	 * Write charging load data in CSV file: loadProfile.csv
	 */
	public void writeChargingLoadDataToFile(){
		String timeNow = String.valueOf(EVGlobalData.data.now/3600.0);
		// Log aggregate plugged-in num and charging load
		try {
			CSVUtil.writeLine(aggWriter, Arrays.asList(timeNow, "", "", String.valueOf(chargingLoadInKw), String.valueOf(numPluggedIn)));
			aggWriter.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
		// Log disaggregate plugged-in num and charging load
		try {
			for(String spatialKey : numPluggedinMap.keySet()){
				for(String siteTypeKey : numPluggedinMap.get(spatialKey).keySet()){
					for(String plugTypeKey : numPluggedinMap.get(spatialKey).get(siteTypeKey).keySet()){
						CSVUtil.writeLine(disaggWriter, Arrays.asList(timeNow, spatialKey, siteTypeKey, plugTypeKey,
								String.valueOf(chargingLoadInKwMap.get(spatialKey).get(siteTypeKey).get(plugTypeKey)),
								String.valueOf(numPluggedinMap.get(spatialKey).get(siteTypeKey).get(plugTypeKey))));
					}
				}
			}
			disaggWriter.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Reschedule this same method to be executed in future
		EVGlobalData.data.scheduler.addCallBackMethod(MathUtil.roundUpToNearestInterval(EVGlobalData.data.now + writeInterval, writeInterval), this ,"writeChargingLoadDataToFile", 0.0, this);
	}

	@Override
	public void reset(int iteration) {
		numPluggedIn = 0;
		chargingLoadInKw = 0.;

		numPluggedinMap = new HashMap<>();
		chargingLoadInKwMap = new HashMap<>();

		this.aggWriter = initAggFileWriter(iteration);
		this.disaggWriter = initDisaggFileWriter(iteration);

		EVGlobalData.data.scheduler.addCallBackMethod(0.0, this ,"writeChargingLoadDataToFile", 0.0, this);
	}

	@Override
	public void handleEvent(BeginChargingSessionEvent event) {
		if(!event.getPlug().useInCalibration())return;
		// Aggregate
		numPluggedIn++;
		chargingLoadInKw += event.getChargingPowerInKw();

		// Disaggregate
		if(numPluggedinMap.containsKey(event.getSpatialGroup())){
			if(numPluggedinMap.get(event.getSpatialGroup()).containsKey(event.getSiteType())){
				if(numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).containsKey(event.getPlugType())){
					numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),
							numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).get(event.getPlugType())+1);
				}else{
					numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),1);
				}
			}else{
				numPluggedinMap.get(event.getSpatialGroup()).put(event.getSiteType(),new HashMap<>());
				numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),1);
			}
		}else{
			numPluggedinMap.put(event.getSpatialGroup(),new HashMap<>());
			numPluggedinMap.get(event.getSpatialGroup()).put(event.getSiteType(), new HashMap<>());
			numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),1);
		}

		if(chargingLoadInKwMap.containsKey(event.getSpatialGroup())){
			if(chargingLoadInKwMap.get(event.getSpatialGroup()).containsKey(event.getSiteType())){
				if(chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).containsKey(event.getPlugType())){
					chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),
							chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).get(event.getPlugType())+event.getChargingPowerInKw());
				}else{
					chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),event.getChargingPowerInKw());
				}
			}else{
				chargingLoadInKwMap.get(event.getSpatialGroup()).put(event.getSiteType(),new HashMap<>());
				chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),event.getChargingPowerInKw());
			}
		}else{
			chargingLoadInKwMap.put(event.getSpatialGroup(),new HashMap<>());
			chargingLoadInKwMap.get(event.getSpatialGroup()).put(event.getSiteType(), new HashMap<>());
			chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),event.getChargingPowerInKw());
		}
	}

	@Override
	public void handleEvent(EndChargingSessionEvent event) {
		if(!event.getPlug().useInCalibration())return;
		// Aggregate
		chargingLoadInKw -= event.getChargingPowerInKw();

		// Disaggregate
		if(chargingLoadInKwMap.containsKey(event.getSpatialGroup())){
			if(chargingLoadInKwMap.get(event.getSpatialGroup()).containsKey(event.getSiteType())){
				if(chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).containsKey(event.getPlugType())){
					chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),
							chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).get(event.getPlugType())-event.getChargingPowerInKw());
				}else{
					chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),-event.getChargingPowerInKw());
				}
			}else{
				chargingLoadInKwMap.get(event.getSpatialGroup()).put(event.getSiteType(),new HashMap<>());
				chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),-event.getChargingPowerInKw());
			}
		}else{
			chargingLoadInKwMap.put(event.getSpatialGroup(),new HashMap<>());
			chargingLoadInKwMap.get(event.getSpatialGroup()).put(event.getSiteType(), new HashMap<>());
			chargingLoadInKwMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),-event.getChargingPowerInKw());
		}
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
		if(!event.getPlug().useInCalibration())return;
		// Aggregate
		numPluggedIn--;

		// Disaggregate
		if(numPluggedinMap.containsKey(event.getSpatialGroup())){
			if(numPluggedinMap.get(event.getSpatialGroup()).containsKey(event.getSiteType())){
				if(numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).containsKey(event.getPlugType())){
					numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),
							numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).get(event.getPlugType())-1);
				}else{
					numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),-1);
				}
			}else{
				numPluggedinMap.get(event.getSpatialGroup()).put(event.getSiteType(),new HashMap<>());
				numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),-1);
			}
		}else{
			numPluggedinMap.put(event.getSpatialGroup(),new HashMap<>());
			numPluggedinMap.get(event.getSpatialGroup()).put(event.getSiteType(), new HashMap<>());
			numPluggedinMap.get(event.getSpatialGroup()).get(event.getSiteType()).put(event.getPlugType(),-1);
		}
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {

	}
}
