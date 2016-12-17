package beam.playground.metasim.services;

import java.util.Random;

import com.google.inject.Singleton;

@Singleton
public class BeamRandomImpl implements BeamRandom {
	private final Random random = new Random();

	@Override
	public int nextInt(int bound) {
		return bound == 0 ? 0 : random.nextInt(bound);
	}

	@Override
	public boolean nextBoolean() {
		return random.nextBoolean();
	}

	@Override
	public double nextDouble() {
		return random.nextDouble();
	}

	@Override
	public void resetSeed(Long seed) {
		random.setSeed(seed);
	}
	

}
