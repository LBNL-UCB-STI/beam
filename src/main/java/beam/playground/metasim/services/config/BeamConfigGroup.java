package beam.playground.metasim.services.config;

import java.io.File;
import java.util.Map;

import org.matsim.core.config.Config;
import org.matsim.core.config.ReflectiveConfigGroup;

public class BeamConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "beam";
	private static final String SIMULATION_NAME = "simulationName", EVENTS_FILE_OUTPUT_FORMATS= "eventsFileOutputFormats", FINITE_STATE_MACHINES_CONFIG_FILE="finiteStateMachinesConfigFile";

	private String simulationName;
	private String eventsFileOutputFormats;
	private String finiteStateMachinesConfigFile;

	private String inputDirectoryBasePath;
	private String configRelativePath;
	private String outputDirectoryBasePath;
	private String outputDirectoryName;
	private File outputDirectory;

	public void customizeConfiguration(Config config, String inputDirectoryBasePath, String configRelativePath, String outputDirectoryBasePath) {
		String inputDirectory = inputDirectoryBasePath + File.separator;

		config.network().setInputFile(inputDirectory + config.network().getInputFile());
		config.plans().setInputFile(inputDirectory + config.plans().getInputFile());

		String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new java.util.Date());
		outputDirectoryName = simulationName + "_" + timestamp;
		outputDirectory = new File(outputDirectoryBasePath + File.separator + outputDirectoryName);
		outputDirectory.mkdir();
		config.controler().setOutputDirectory(outputDirectory.getAbsolutePath());
	}

	public String getInputDirectoryBasePath() {
		return inputDirectoryBasePath;
	}
	public String getConfigRelativePath() {
		return configRelativePath;
	}
	public String getOutputDirectoryBasePath() {
		return outputDirectoryBasePath;
	}
	public String getOutputDirectoryName() {
		return outputDirectoryName;
	}
	public File getOutputDirectory() {
		return outputDirectory;
	}
	public BeamConfigGroup() {
		super(GROUP_NAME);
	}

    @Override
    public Map<String, String> getComments() {
        Map<String,String> map = super.getComments();
        return map;
    }
    @StringGetter(SIMULATION_NAME)
    public String getSimulationName() {
		return this.simulationName;
	}
    @StringSetter(SIMULATION_NAME)
	public void setSimulationName(final String simulationName) {
		this.simulationName = simulationName;
	}
    @StringGetter(EVENTS_FILE_OUTPUT_FORMATS)
	public String getEventsFileOutputFormats() {
		return eventsFileOutputFormats;
	}
    @StringSetter(EVENTS_FILE_OUTPUT_FORMATS)
	public void setEventsFileOutputFormats(String eventsFileOutputFormats) {
		this.eventsFileOutputFormats = eventsFileOutputFormats;
	}
    @StringGetter(FINITE_STATE_MACHINES_CONFIG_FILE)
    public String getFiniteStateMachinesConfigFile(){
    	return this.finiteStateMachinesConfigFile;
    }
    @StringGetter(FINITE_STATE_MACHINES_CONFIG_FILE)
    public void setFiniteStateMachinesConfigFile(String finiteStateMachinesConfigFile){
    	this.finiteStateMachinesConfigFile = finiteStateMachinesConfigFile;
    }

    //TODO this needs to come from somewhere if we want this control
	public boolean getDumpPlansAtEndOfRun() {
		return false;
	}

}
