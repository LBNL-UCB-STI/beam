package beam.replanning.io;

import java.util.Collection;
import java.util.LinkedList;
import java.util.PriorityQueue;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import beam.EVGlobalData;
import beam.parking.lib.DebugLib;
import beam.parking.lib.GeneralLib;
import beam.parking.lib.obj.LinkedListValueHashMap;
import beam.parking.lib.obj.Matrix;
import beam.parking.lib.obj.SortableMapObject;

public class EVDailyPlanCsvFile {

	LinkedListValueHashMap<Id<Person>, EVDailyPlanFromCsv> personPlans;
	
	public Collection<Id<Person>> getPersonIds(){
		return personPlans.getKeySet();
	}
	
	EVDailyPlanCsvFile(String fileName){
		Matrix<String> data=GeneralLib.readStringMatrix(fileName,",");
		personPlans=new LinkedListValueHashMap<>();
		
		EVDailyPlanFromCsv plan=null;
		for (int i=1;i<data.getNumberOfRows();i++){
			if (data.getString(i, EVDailyPlanWriter.COL_IND_TYPE).equalsIgnoreCase("plan")){
				plan=new EVDailyPlanFromCsv();
				plan.planScore=data.getDouble(i, EVDailyPlanWriter.COL_IND_PLAN_SCORE);
				plan.chargingSequenceScore = data.getDouble(i, EVDailyPlanWriter.COL_IND_CHARGING_SEQUENCE_SCORE);
				plan.isSelectedPlan=data.getBoolean(i, EVDailyPlanWriter.COL_IND_IS_SELECTED_EV_DAILY_PLAN);
				personPlans.put(Id.createPersonId(data.getString(i, EVDailyPlanWriter.COL_IND_PERSON_ID)), plan);
			} else if (data.getString(i, EVDailyPlanWriter.COL_IND_TYPE).equalsIgnoreCase("act")){ 
				plan.planElements.add(new ActFromCsv(data.getDouble(i, EVDailyPlanWriter.COL_IND_ACT_END_TIME)));
			} else if (data.getString(i, EVDailyPlanWriter.COL_IND_TYPE).equalsIgnoreCase("leg")){
				plan.planElements.add(new LegFromCsv(data.getInteger(i, EVDailyPlanWriter.COL_IND_CHARGING_STRATEGY_ID)));
			} else {
				DebugLib.stopSystemAndReportInconsistency();
			}
		}
	}
	
	public static EVDailyPlanCsvFile[] readIterations(int numberOfIterations){
		EVDailyPlanCsvFile[] eVDailyPlanCsvFile=new EVDailyPlanCsvFile[numberOfIterations];
		
		for (int i=0;i<numberOfIterations;i++){
			eVDailyPlanCsvFile[i]=new EVDailyPlanCsvFile(EVGlobalData.data.controler.getControlerIO().getIterationFilename(i, EVGlobalData.data.SELECTED_EV_DAILY_PLANS_FILE_NAME));
		}
		
		return eVDailyPlanCsvFile;
	}
	
	public LinkedList<EVDailyPlanFromCsv> getPlans(Id<Person> personId){
		return personPlans.get(personId);
	}
	
	public LinkedList<Double> getChargingStrategyScoresOfPlanSubSet(Id<Person> personId, int numberOfBestPlansToConsider){
		LinkedList<Double> result=new LinkedList<>();
		PriorityQueue<SortableMapObject<EVDailyPlanFromCsv>> priorityQueue = new PriorityQueue<SortableMapObject<EVDailyPlanFromCsv>>();
		
		for (EVDailyPlanFromCsv plan:getPlans(personId)){
			priorityQueue.add(new SortableMapObject<EVDailyPlanFromCsv>(plan, plan.chargingSequenceScore));
		}
		
		double scoreSum=0;
		for (int i=0;i<numberOfBestPlansToConsider;i++){
			if (!priorityQueue.isEmpty()){
				result.add(priorityQueue.poll().getWeight());
			}
		}
		
		return result;
	}
	
	
	
	public double getAverageChargingStrategyScoreOfPlanSubSet(Id<Person> personId, int numberOfBestPlansToConsider){
		LinkedList<Double> chargingStrategyScoresOfPlanSubSet = getChargingStrategyScoresOfPlanSubSet(personId,numberOfBestPlansToConsider);
		
		double scoreSum=0;
		for (Double score:chargingStrategyScoresOfPlanSubSet){
			scoreSum+=score;
		}
		
		return scoreSum/chargingStrategyScoresOfPlanSubSet.size();
	}
	
	public EVDailyPlanFromCsv getSelectedPlan(Id<Person> personId){
		for (EVDailyPlanFromCsv plan:getPlans(personId)){
			if (plan.isSelectedPlan){
				return plan;
			}
		}
		
		return null;
	}
	
	
}
