package beam;

import java.io.File;
import java.util.regex.Matcher;

public class TestUtilities {
	
	public static void setConfigFile(String configFileName){
		EVGlobalData.simulationStaticVariableInitializer();		
		EVGlobalData.data.CONFIG_RELATIVE_PATH = configFileName;
	}
	// TODO: define later sub folders accordingly, so that config searches at right place in folder structure
	public static void setTestInputDirectory(@SuppressWarnings("rawtypes") Class theClass){
		EVGlobalData.data.INPUT_DIRECTORY_BASE_PATH  = new File("").getAbsolutePath() + File.separator + "test" + File.separator + "input" + File.separator + theClass.getCanonicalName().replaceAll("\\.", Matcher.quoteReplacement(File.separator)) + File.separator;
	}
	
	public static void setTestInputDirectory(String path){
		EVGlobalData.data.INPUT_DIRECTORY_BASE_PATH = path;
	}
	
	public static String getResource(String resourceName){
		return EVGlobalData.data.INPUT_DIRECTORY_BASE_PATH + File.separator + resourceName;
	}
	
	public static String getPathToyGridScenarioInputFolder() {
		String inputTestFolderPath = "test\\input\\beam\\toyGridScenario".replaceAll("\\\\",
				Matcher.quoteReplacement(File.separator));
		return new File("").getAbsolutePath() + File.separator + inputTestFolderPath;
	}

	public static String getOutputFolderPath(Class theClass) {
		return new File("").getAbsolutePath() + File.separator + "test" + File.separator + "output" + File.separator
				+ theClass.getCanonicalName().replaceAll("\\.", Matcher.quoteReplacement(File.separator)) + File.separator;
	}

	public static void initOutputFolder(Class theClass) {
		EVGlobalData.data.OUTPUT_DIRECTORY_BASE_PATH = getOutputFolderPath(theClass);
		new File(EVGlobalData.data.OUTPUT_DIRECTORY_BASE_PATH).mkdirs();
	}
}
