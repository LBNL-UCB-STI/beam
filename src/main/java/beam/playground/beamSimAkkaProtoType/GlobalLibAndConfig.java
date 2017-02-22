package beam.playground.beamSimAkkaProtoType;

public class GlobalLibAndConfig {

	// if window size is small (e.g. 1 second), then it might make sense to
	// decrease tick size, as it might allows for slightly more parallelism (window can
	// move forward faster)
	public static final double sizeOfWindowTickInSeconds = 0.1;

	public static final int windowSizeInSeconds = 100;

	public static final int latencyRttDelayInMs = 10;

	public static int getWindowSizeInTicks() {
		return getTick(sizeOfWindowTickInSeconds * windowSizeInSeconds);
	}

	public static int getTick(double time) {
		return (int) Math.round(Math.floor(time / sizeOfWindowTickInSeconds));
	}

	public static double getTime(int tick) {
		return tick * sizeOfWindowTickInSeconds;
	}

}
