package beam.sim.traveltime;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.LinkToLinkTravelTime;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.DataContainerProvider;
import org.matsim.core.trafficmonitoring.TravelTimeCalculator;
import org.matsim.core.trafficmonitoring.TravelTimeDataArray;
import org.matsim.vehicles.Vehicle;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;

import beam.EVGlobalData;
import beam.parking.lib.DebugLib;

public class RelaxedTravelTime implements TravelTime, LinkToLinkTravelTime, TravelDisutility, Serializable {
	private static final Logger log = Logger.getLogger(RelaxedTravelTime.class);
	private HashMap<Integer,double[]> linkTravelTimes = new HashMap<Integer, double[]>();
	private int numSlots;
	private double timeSlice;
	private boolean mapAbsoluteTimeToTimeOfDay = true;
	
	public RelaxedTravelTime(Boolean mapAbsoluteTimeToTimeOfDay, String serialPath) throws Exception {
		this.mapAbsoluteTimeToTimeOfDay = mapAbsoluteTimeToTimeOfDay;
		FileInputStream fileIn = new FileInputStream(serialPath);
		FSTObjectInput in = new FSTObjectInput(fileIn);
		this.linkTravelTimes = (HashMap<Integer, double[]>)in.readObject(HashMap.class);
		numSlots = this.linkTravelTimes.values().iterator().next().length;
	    in.close();
	    fileIn.close();
	}
	public RelaxedTravelTime(Boolean mapAbsoluteTimeToTimeOfDay, TravelTimeCalculator calculator){
		this.mapAbsoluteTimeToTimeOfDay = mapAbsoluteTimeToTimeOfDay;
		numSlots = calculator.getNumSlots();
		timeSlice = (new Integer(calculator.getTimeSlice())).doubleValue();
		Field providerField, traveTimeDataArrayField, travelTimesField;
		try {
			providerField = calculator.getClass().getDeclaredField("dataContainerProvider");
			providerField.setAccessible(true);
	        DataContainerProvider provider = (DataContainerProvider) providerField.get(calculator);

	        for(Id<Link> id : EVGlobalData.data.controler.getScenario().getNetwork().getLinks().keySet()){
		        Object data = provider.getTravelTimeData(id, true);
		        traveTimeDataArrayField = data.getClass().getDeclaredField("ttData");
		        traveTimeDataArrayField.setAccessible(true);
		        TravelTimeDataArray ttDataArray = (TravelTimeDataArray) traveTimeDataArrayField.get(data);
		        travelTimesField = ttDataArray.getClass().getDeclaredField("travelTimes");
		        travelTimesField.setAccessible(true);
		        double[] travelTimes = (double[])travelTimesField.get(ttDataArray);
		        this.linkTravelTimes.put(id.hashCode(), travelTimes);
	        }
	        DebugLib.emptyFunctionForSettingBreakPoint();
		} catch (NoSuchFieldException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
	}
	public RelaxedTravelTime(Network network){
		this.mapAbsoluteTimeToTimeOfDay = true;
		numSlots = 1;
		timeSlice = Double.MAX_VALUE;
	    for(Id<Link> id : network.getLinks().keySet()){
			this.linkTravelTimes.put(id.hashCode(), new double[]{network.getLinks().get(id).getLength() / network.getLinks().get(id).getFreespeed()});
		}
	}

	@Override
	public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
		if(!this.linkTravelTimes.containsKey(link.getId().hashCode())){
//			log.warn("No link travel time is available for link " + link.getId());
			double[] fakeTravelTimes = new double[numSlots];
			Arrays.fill(fakeTravelTimes,Double.MAX_VALUE);
			this.linkTravelTimes.put(link.getId().hashCode(),fakeTravelTimes);
		}
		int slot = convertTimeToBin(time);
		if(slot < 0 || slot > numSlots || this.linkTravelTimes.get(link.getId().hashCode())[slot] < 0.0){
			return Double.MAX_VALUE;
		}else{
			return this.linkTravelTimes.get(link.getId().hashCode())[slot];
		}
	}

	public int convertTimeToBin(double time) {
		if(mapAbsoluteTimeToTimeOfDay){
			return (int) (time % 86400.0 / timeSlice);
		}else{
			return (int) (time / timeSlice);
		}
	}

	/**
	 * For now ignore turning move travel time 
	 */
	//TODO quantify turning move travel time
	@Override
	public double getLinkToLinkTravelTime(Link fromLink, Link toLink, double time) {
		return this.getLinkTravelTime(fromLink, time, null, null);
	}
	@Override
	public double getLinkTravelDisutility(Link link, double time,
			Person person, Vehicle vehicle) {
		return this.getLinkTravelTime(link, time, person, vehicle);
	}
	@Override
	public double getLinkMinimumTravelDisutility(Link link) {
		return link.getLength() / link.getFreespeed();
	}
	public boolean isMapAbsoluteTimeToTimeOfDay() {
		return mapAbsoluteTimeToTimeOfDay;
	}
	public void setMapAbsoluteTimeToTimeOfDay(boolean mapAbsoluteTimeToTimeOfDay) {
		this.mapAbsoluteTimeToTimeOfDay = mapAbsoluteTimeToTimeOfDay;
	}
	public HashMap<Integer, double[]> getLinkTravelTimes() {
		return this.linkTravelTimes;
	}
	public void setLinkTravelTimes(HashMap<Integer, double[]> linkTravelTimes) {
		this.linkTravelTimes = linkTravelTimes;
	}
}
