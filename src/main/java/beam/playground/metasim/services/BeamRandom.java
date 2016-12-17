package beam.playground.metasim.services;

public interface BeamRandom {
    public int nextInt(int bound);
    public boolean nextBoolean();
    public double nextDouble();
    public void resetSeed(Long seed);

}
