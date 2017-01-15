package beam.playground.metasim.services.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;

import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.core.config.ReflectiveConfigGroup;

import beam.playground.metasim.events.ActionCallBackScheduleEvent;
import beam.playground.metasim.events.ActionEvent;
import beam.playground.metasim.events.TransitionEvent;

public class BeamEventLoggerConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "beamEventLogger";
	public static final String EVENTS_FILE_FORMATS="eventsFileFormats",WRITE_PLANS_INTERVAL="writePlansInterval",WRITE_EVENTS_INTERVAL="writeEventsInterval", DEFAULT_LEVEL="Default.level", EXPLODE_EVENTS_INTO_FILES="explodeEventsIntoFiles";
	public enum BeamEventsFileFormats {xml,csv,xmlgz,csvgz};

	private String eventsFileFormats;
	private ArrayList<BeamEventsFileFormats> eventsFileFormatsArray = new ArrayList<>();
	private Integer writePlansInterval, writeEventsInterval, defaultLevel;
	private Boolean explodeEventsIntoFiles;
	private HashSet<Class<?>> allLoggableEvents = new HashSet<>();

	public BeamEventLoggerConfigGroup() {
		super(GROUP_NAME,true);

		// Registry of BEAM events that can be logged by BeamEventLogger
		allLoggableEvents.add(ActionEvent.class);
		allLoggableEvents.add(TransitionEvent.class);
		allLoggableEvents.add(ActionCallBackScheduleEvent.class);
		// Registry of MATSim events that can be logged by BeamEventLogger
		allLoggableEvents.add(ActivityEndEvent.class);
		allLoggableEvents.add(PersonDepartureEvent.class);
		allLoggableEvents.add(PersonEntersVehicleEvent.class);
		allLoggableEvents.add(VehicleEntersTrafficEvent.class);
		allLoggableEvents.add(LinkLeaveEvent.class);
		allLoggableEvents.add(LinkEnterEvent.class);
		allLoggableEvents.add(VehicleLeavesTrafficEvent.class);
		allLoggableEvents.add(PersonLeavesVehicleEvent.class);
		allLoggableEvents.add(PersonArrivalEvent.class);
		allLoggableEvents.add(ActivityStartEvent.class);
	}

    @Override
    public Map<String, String> getComments() {
        Map<String,String> map = super.getComments();
        return map;
    }
    @StringGetter(EVENTS_FILE_FORMATS)
    private String getEventsFileFormats() {
		return this.eventsFileFormats;
	}
    @StringSetter(EVENTS_FILE_FORMATS)
	public void setEventsFileFormats(String eventsFileFormats) {
		this.eventsFileFormats = eventsFileFormats;
		this.eventsFileFormatsArray.clear();
		for(String format : eventsFileFormats.split(",")){
			if(format.toLowerCase().equals("xml")){
				this.eventsFileFormatsArray.add(BeamEventsFileFormats.xml);
			}else if(format.toLowerCase().equals("xml.gz")){
				this.eventsFileFormatsArray.add(BeamEventsFileFormats.xmlgz);
			}else if(format.toLowerCase().equals("csv")){
				this.eventsFileFormatsArray.add(BeamEventsFileFormats.csv);
			}else if(format.toLowerCase().equals("csv.gz")){
				this.eventsFileFormatsArray.add(BeamEventsFileFormats.csvgz);
			}
		}
	}
    public ArrayList<BeamEventsFileFormats>getEventsFileFormatsAsArray() {
		return this.eventsFileFormatsArray;
	}
    @StringGetter(WRITE_PLANS_INTERVAL)
	public Integer getWritePlansInterval() {
		return writePlansInterval;
	}
    @StringSetter(WRITE_PLANS_INTERVAL)
	public void setWritePlansInterval(Integer writePlansInterval) {
		this.writePlansInterval = writePlansInterval;
	}
    @StringGetter(WRITE_EVENTS_INTERVAL)
	public Integer getWriteEventsInterval() {
		return writeEventsInterval;
	}
    @StringSetter(WRITE_EVENTS_INTERVAL)
	public void setWriteEventsInterval(Integer writeEventsInterval) {
		this.writeEventsInterval = writeEventsInterval;
	}
    @StringGetter(DEFAULT_LEVEL)
	public Integer getDefaultLevel() {
		return writeEventsInterval;
	}
    @StringSetter(DEFAULT_LEVEL)
	public void setDefaultLevel(Integer defaultLevel) {
		this.writeEventsInterval = defaultLevel;
	}
    @StringGetter(EXPLODE_EVENTS_INTO_FILES)
	public Boolean getExplodeEventsIntoFiles() {
		return explodeEventsIntoFiles;
	}
    @StringSetter(EXPLODE_EVENTS_INTO_FILES)
	public void setExplodeEventsIntoFiles(Boolean explodeEventsIntoFiles) {
		this.explodeEventsIntoFiles = explodeEventsIntoFiles;
	}
    public HashSet<Class<?>> getAllLoggableEvents(){
    	return allLoggableEvents;
    }
}
