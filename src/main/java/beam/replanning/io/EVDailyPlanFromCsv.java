package beam.replanning.io;

import java.util.ArrayList;
import java.util.LinkedList;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

public class EVDailyPlanFromCsv {
	
	public double planScore;
	public double chargingSequenceScore;
	public boolean isSelectedPlan;
	
	public LinkedList<PlanElement> planElements=new LinkedList<>();
	
	public ArrayList<Integer> getChargingStrategyIds(){
		ArrayList<Integer> list=new ArrayList<>();
		
		for (PlanElement pe:planElements){
			if (pe instanceof LegFromCsv){
				LegFromCsv leg=(LegFromCsv) pe;
				list.add(leg.chargingStrategyId);
			}
		}
		
		return list;
	}
	
	public int[] getChartingStrategyIds_int(){
		ArrayList<Integer> chargingStrategyIds = getChargingStrategyIds();
		int[] strategyIds=new int[chargingStrategyIds.size()];
		
		for (int i=0;i<strategyIds.length;i++){
			strategyIds[i]=chargingStrategyIds.get(i);
		}
		
		return strategyIds;
	}
	
}
