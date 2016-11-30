package beam.events;

import static org.junit.Assert.*;

import java.io.File;
import org.junit.Test;

import beam.EVGlobalData;
import beam.TestUtilities;
import beam.basicTests.SingleAgentBaseTest;

public class EventLogger_EVEventsIntervalTest extends SingleAgentBaseTest {

	@Override
	@Test
	public void test() {
		TestUtilities.setConfigFile("config_EVEventsLoggingIntervalTest.xml");

		startRun();
		
		String message="Ev events logging intervall not functioning correctly (module: PEVSim.LogSettings, param: writeEVEventsInterval)";
		
		File eventsFileIterationZero=new File(EVGlobalData.data.controler.getControlerIO().getIterationFilename(0, "events.xml.gz"));
		File eventsFileIterationOne=new File(EVGlobalData.data.controler.getControlerIO().getIterationFilename(1, "events.xml.gz"));
		File eventsFileIterationTwo=new File(EVGlobalData.data.controler.getControlerIO().getIterationFilename(2, "events.xml.gz"));
		File eventsFileIterationThree=new File(EVGlobalData.data.controler.getControlerIO().getIterationFilename(3, "events.xml.gz"));
		
		assertTrue(message, eventsFileIterationZero.length() > 3* eventsFileIterationOne.length());
		assertTrue(message, eventsFileIterationTwo.length() > 3* eventsFileIterationThree.length());
	}

}
