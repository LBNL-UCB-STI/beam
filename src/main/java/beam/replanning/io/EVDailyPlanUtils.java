package beam.replanning.io;

import java.util.ArrayList;
import java.util.LinkedList;

import org.matsim.api.core.v01.Id;

import beam.parking.lib.obj.Matrix;

public class EVDailyPlanUtils {

	// treats negative values as optional for comparison
	public static boolean strategySequenceMatches(ArrayList<Integer> strategySequenceA, ArrayList<Integer> strategySequenceB){
		for (int i=0;i<strategySequenceA.size();i++){
			if (strategySequenceA.get(i)!=strategySequenceB.get(i) && strategySequenceA.get(i)>0 && strategySequenceB.get(i)>0){
				return false;
			}
		}
		
		return true;
	}
	
	public static ArrayList<Integer> intChargingIdSequenceToArrayList(int[] sequence){
		ArrayList<Integer> result = new ArrayList<>();
		
		for (int i=0;i<sequence.length;i++){
			result.add(sequence[i]);
		}
		
		return result;
	}
	
	public static double getAverageScoreSelectedSubSet(EVDailyPlanCsvFile[] evDailyPlanData, int startIteration, int endIteration, int numberOfBestPlansToConsider) {
		LinkedList<Double> scores=new LinkedList<>();
		
		for (int i=startIteration;i<=endIteration;i++){
			scores.addAll(evDailyPlanData[i].getChargingStrategyScoresOfPlanSubSet(Id.createPersonId("1"), numberOfBestPlansToConsider));
		}
		
		double scoreSum=0;
		
		for (Double score:scores){
			scoreSum+=score;
		}
		
		return scoreSum/scores.size();
	}
	
}
