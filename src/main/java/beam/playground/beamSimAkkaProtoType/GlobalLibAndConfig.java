package beam.playground.beamSimAkkaProtoType;

import akka.actor.ActorRef;
import akka.event.LoggingAdapter;
import beam.playground.beamSimAkkaProtoType.beamPersonAgent.ActStartMessage;

public class GlobalLibAndConfig {

	// if window size is small (e.g. 1 second), then it might make sense to
	// decrease tick size, as it might allows for slightly more parallelism (window can
	// move forward faster)
	public static final double sizeOfTickInSeconds = 100;

	public static final int windowSizeInSeconds = 1000;

	public static final int latencyRttDelayInMs=1;
	
	public static boolean printMessagesReceived=false;
	
	public static int numberOfPeopleInSimulation=10000000;
	

	public static int getWindowSizeInTicks() {
		return getTick(windowSizeInSeconds);
	}

	public static int getTick(double time) {
		return (int) Math.round(Math.floor(time / sizeOfTickInSeconds));
	}

	public static double getTime(int tick) {
		return tick * sizeOfTickInSeconds;
	}
	

	
	public static void printMessage(LoggingAdapter log ,Object message){
		if (printMessagesReceived){
			log.info(message.toString());
		}
	}
}
