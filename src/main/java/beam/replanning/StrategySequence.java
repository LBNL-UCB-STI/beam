package beam.replanning;

import java.util.HashMap;
import java.util.Random;

import org.apache.log4j.Logger;

import beam.charging.infrastructure.ChargingPlugImpl;

public class StrategySequence {

	private static final Logger log = Logger.getLogger(StrategySequence.class);
	
	// map leg index to strategy (per leg one strategy).
	private HashMap<Integer, ChargingStrategy> strategies;
	
	public StrategySequence() {
		this(new HashMap());
	}
	
	public StrategySequence(HashMap<Integer, ChargingStrategy> strategies) {
		this.strategies = strategies;
	}

	public ChargingStrategy getStrategy(int planElementIndex){
		return strategies.get(planElementIndex);
	}
	
	public void resetScore(){
		score=0;
	}
	
	private double score;

	public void addScore(double score){
		this.score+=score;
	}

	public double getScore() {
		return score;
	}
	
}
