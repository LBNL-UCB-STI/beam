package beam.playground.metasim.services.config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

import org.matsim.core.config.Config;
import org.matsim.core.config.ReflectiveConfigGroup;

public class BeamConfigGroup extends ReflectiveConfigGroup {
	public static final String GROUP_NAME = "beam";
	public static final String SIMULATION_NAME = "simulationName", EVENTS_FILE_OUTPUT_FORMATS= "eventsFileOutputFormats", FINITE_STATE_MACHINES_CONFIG_FILE="finiteStateMachinesConfigFile";
	public static final String DOT_CONFIG_FILE = "dotConfigFile", CHOICE_MODEL_CONFIG_FILE="choiceModelConfigFile";

	private String simulationName;
	private String eventsFileOutputFormats;
	private String finiteStateMachinesConfigFile;
	private String choiceModelConfigFile;
	private String dotConfigFile;

	private String inputDirectoryBasePath;
	private String configRelativePath;
	private String outputDirectoryBasePath;
	private String outputDirectoryName;
	private File outputDirectory;

	public void customizeConfiguration(Config config, String inputDirectoryBasePath, String configRelativePath, String outputDirectoryBasePath) {
		String inputDirectory = inputDirectoryBasePath + File.separator;

		config.network().setInputFile(inputDirectory + config.network().getInputFile());
		config.plans().setInputFile(inputDirectory + config.plans().getInputFile());

		Field[] fields = this.getClass().getFields();
		for (Field field:fields){
			if (field.getName().contains("_CONFIG_FILE")){
				try {
					Field configFileField = this.getClass().getDeclaredField(field.get(this).toString());
					configFileField.setAccessible(true);
					configFileField.set(this, inputDirectory + (String)configFileField.get(this));
				} catch (IllegalArgumentException e) {
					e.printStackTrace();
				} catch (IllegalAccessException e) {
					e.printStackTrace();
				} catch (NoSuchFieldException e) {
					e.printStackTrace();
				} catch (SecurityException e) {
					e.printStackTrace();
				}
			}
		}
		
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
    @StringSetter(FINITE_STATE_MACHINES_CONFIG_FILE)
    public void setFiniteStateMachinesConfigFile(String finiteStateMachinesConfigFile){
    	this.finiteStateMachinesConfigFile = finiteStateMachinesConfigFile;
    }
    @StringGetter(DOT_CONFIG_FILE)
    public String getDotConfigFile(){
    	return this.dotConfigFile;
    }
    @StringSetter(DOT_CONFIG_FILE)
    public void setDotConfigFile(String dotConfigFile){
    	this.dotConfigFile = dotConfigFile;
    }
    @StringGetter(CHOICE_MODEL_CONFIG_FILE)
	public String getChoiceModelConfigFile() {
		return choiceModelConfigFile;
	}
    @StringSetter(CHOICE_MODEL_CONFIG_FILE)
	public void setChoiceModelConfigFile(String choiceModelConfigFile) {
		this.choiceModelConfigFile = choiceModelConfigFile;
	}

    //TODO this needs to come from somewhere if we want this control
	public boolean getDumpPlansAtEndOfRun() {
		return false;
	}



}
