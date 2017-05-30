package beam.sim.traveltime;

import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import beam.utils.CSVUtil;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.LinkToLinkTravelTime;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.utils.io.tabularFileParser.TabularFileHandler;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParser;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParserConfig;
import org.matsim.vehicles.Vehicle;

import beam.EVGlobalData;

public class ExogenousTravelTime implements TravelTime, LinkToLinkTravelTime, TravelDisutility, Serializable {
	private static final Logger log = Logger.getLogger(ExogenousTravelTime.class);
	private HashMap<Integer,double[]> linkTravelTimes = new HashMap<Integer, double[]>();
	private Integer numSlots;
	private Double timeSlice;
	private Boolean mapAbsoluteTimeToTimeOfDay = true;

	public ExogenousTravelTime(Boolean mapAbsoluteTimeToTimeOfDay){
		this.mapAbsoluteTimeToTimeOfDay = mapAbsoluteTimeToTimeOfDay;
	}
	public static ExogenousTravelTime LoadTravelTimeFromValidationData(String filePath, Boolean mapAbsoluteTimeToTimeOfDay){
		ExogenousTravelTime newTravelTime = new ExogenousTravelTime(mapAbsoluteTimeToTimeOfDay);

		TabularFileParser fileParser = new TabularFileParser();
		TabularFileParserConfig fileParserConfig = new TabularFileParserConfig();
		fileParserConfig.setFileName(filePath);
		fileParserConfig.setDelimiterRegex("\t");
		TabularFileHandler handler = new TabularFileHandler() {
			public LinkedHashMap<String, Integer> headerMap;
			public LinkedHashMap<String, Integer> ttColumnsToBinIndex;

			@Override
			public void startRow(String[] row) {
				if (headerMap == null) {
					headerMap = new LinkedHashMap<String, Integer>();
					ttColumnsToBinIndex = new LinkedHashMap<String, Integer>();
					newTravelTime.numSlots = 0;
					for (int i = 0; i < row.length; i++) {
						String colName = row[i].toLowerCase();
						if (colName.startsWith("\"")) {
							colName = colName.substring(1, colName.length() - 1);
						}
						headerMap.put(colName, i);
						if(colName.substring(0,2).equals("tt")){
							String[] parts = colName.split("_");
							Double binTime = Double.parseDouble(parts[1]);
							Double binEndTime = Double.parseDouble(parts[2]);
							Double timeSlice = binEndTime - binTime;
							int binIndex = convertTimeToBin(binTime,mapAbsoluteTimeToTimeOfDay,timeSlice);
							if(binTime>6.0*3600.0){
								newTravelTime.numSlots++;
								newTravelTime.timeSlice = timeSlice;
								ttColumnsToBinIndex.put(colName,binIndex);
							}
						}
					}
				} else {
					double[] travelTimes = new double[newTravelTime.numSlots];
					String linkId = CSVUtil.getValue("link_id",row,headerMap);
					for(String colName : ttColumnsToBinIndex.keySet()){
					    travelTimes[ttColumnsToBinIndex.get(colName)] = Double.parseDouble(CSVUtil.getValue(colName,row,headerMap));
					}
					newTravelTime.linkTravelTimes.put(linkId.hashCode(),travelTimes);
				}
			}
		};
		fileParser.parse(fileParserConfig, handler);

		return newTravelTime;

		/*
		newTravelTime.numSlots = newTravelTime.calculator.getNumSlots();
		newTravelTime.timeSlice = (new Integer(calculator.getTimeSlice())).doubleValue();
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
		*/
	}
	public static ExogenousTravelTime LoadTravelTimeFromSerializedData(String serialPath) {
		ExogenousTravelTime newTravelTime = new ExogenousTravelTime(false);
		newTravelTime.deserializeTravelTimeData(serialPath);
	    return newTravelTime;
	}
	public static ExogenousTravelTime LoadTravelTimeFromNetwork(Network network, Boolean mapAbsoluteTimeToTimeOfDay) {
		ExogenousTravelTime newTravelTime = new ExogenousTravelTime(mapAbsoluteTimeToTimeOfDay);
		newTravelTime.mapAbsoluteTimeToTimeOfDay = true;
		newTravelTime.numSlots = 1;
		newTravelTime.timeSlice = Double.MAX_VALUE;
		for(Id<Link> id : network.getLinks().keySet()){
			newTravelTime.linkTravelTimes.put(id.hashCode(), new double[]{network.getLinks().get(id).getLength() / network.getLinks().get(id).getFreespeed()});
		}
		return newTravelTime;
	}
	public void deserializeTravelTimeData(String serialPath){
		try {
			FileInputStream fileIn = new FileInputStream(serialPath);
			GZIPInputStream zipIn = new GZIPInputStream(fileIn);
			Input in = new Input(zipIn);
			Kryo kryo = new Kryo();
			this.linkTravelTimes = (HashMap<Integer, double[]>) kryo.readClassAndObject(in);
			this.timeSlice = (Double)kryo.readClassAndObject(in);
			this.numSlots = (Integer)kryo.readClassAndObject(in);
			this.mapAbsoluteTimeToTimeOfDay = (Boolean) kryo.readClassAndObject(in);
			in.close();
			zipIn.close();
			fileIn.close();
		} catch (Exception e) {
			// Our fallback is to use the freespeed in the network file and assume constant travel time over the day
			log.warn("Execption occurred when deserializing travel time data: "+e.getMessage()+" loading travel times from network instead");
			EVGlobalData.data.travelTimeFunction = ExogenousTravelTime.LoadTravelTimeFromNetwork(EVGlobalData.data.controler.getScenario().getNetwork(),true);
			System.exit(0);
		}
	}
	public void serializeTravelTimeData(String serialPath){
		try {
			FileOutputStream fileOut = new FileOutputStream(serialPath);
			GZIPOutputStream zout = new GZIPOutputStream(new BufferedOutputStream(fileOut));
			Output out = new Output(zout);
			Kryo kryo = new Kryo();
			kryo.writeClassAndObject(out,this.linkTravelTimes);
			kryo.writeClassAndObject(out,this.timeSlice);
			kryo.writeClassAndObject(out,this.numSlots);
			kryo.writeClassAndObject(out,this.mapAbsoluteTimeToTimeOfDay);
			out.close();
			zout.close();
			fileOut.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
		return link.getLength() / (link.getFreespeed() > 0.0 ? link.getFreespeed() : 1.0);
//		if(!this.linkTravelTimes.containsKey(link.getId().hashCode())){
////			log.warn("No link travel time is available for link " + link.getId());
//			double[] fakeTravelTimes = new double[numSlots];
//			Arrays.fill(fakeTravelTimes,link.getLength() / (link.getFreespeed() > 0.0 ? link.getFreespeed() : 1.0));
//			this.linkTravelTimes.put(link.getId().hashCode(),fakeTravelTimes);
//		}
//		int slot = convertTimeToBin(time);
//		if(slot >= 0 && slot < numSlots && this.linkTravelTimes.get(link.getId().hashCode())[slot] > 86400.0){
//			return link.getLength() / (link.getFreespeed() > 0.0 ? link.getFreespeed() : 1.0);
//		}
//		if(slot < 0 || slot > numSlots || this.linkTravelTimes.get(link.getId().hashCode())[slot] < 0.0){
//			return link.getLength() / (link.getFreespeed() > 0.0 ? link.getFreespeed() : 1.0);
//		}else{
//			return this.linkTravelTimes.get(link.getId().hashCode())[slot];
//		}
	}

	public static int convertTimeToBin(double time, boolean mapAbsoluteTimeToTimeOfDay, double timeSlice) {
		if(mapAbsoluteTimeToTimeOfDay){
			return (int) (time % 86400.0 / timeSlice);
		}else{
			return (int) (time / timeSlice);
		}
    }
	public int convertTimeToBin(double time) {
	    return ExogenousTravelTime.convertTimeToBin(time,this.mapAbsoluteTimeToTimeOfDay, this.timeSlice);
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
