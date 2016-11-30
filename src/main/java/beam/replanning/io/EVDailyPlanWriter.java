package beam.replanning.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;

import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.io.UncheckedIOException;

import beam.EVGlobalData;
import beam.replanning.ChargingStrategy;
import beam.replanning.EVDailyPlan;
import beam.replanning.EVDailyReplanable;

public class EVDailyPlanWriter {

	protected BufferedWriter out;

	HashMap<Integer, String> columnNameMapping;

	public static final int COL_IND_PERSON_ID = 0;
	public static final int COL_IND_TYPE = 1;
	public static final int COL_IND_PLAN_ELEM_INDEX = 2;
	public static final int COL_IND_CHARGING_STRATEGY_ID = 3;
	public static final int COL_IND_PLAN_SCORE = 4;
	public static final int COL_IND_CHARGING_SEQUENCE_SCORE = 5;
	public static final int COL_IND_ACT_END_TIME = 6;
	public static final int COL_IND_NUMBER_OF_EVPLANS_IN_MEMORY = 7;
	public static final int COL_IND_PLAN_NUMBER = 8;
	public static final int COL_IND_IS_SELECTED_EV_DAILY_PLAN = 9;

	public EVDailyPlanWriter(String outfilename) {
		this.out = IOUtils.getBufferedWriter(outfilename);
		columnNameMapping = new HashMap<>();
		columnNameMapping.put(COL_IND_PERSON_ID, "personId");
		columnNameMapping.put(COL_IND_TYPE, "planElementType");
		columnNameMapping.put(COL_IND_PLAN_ELEM_INDEX, "planElementIndex");
		columnNameMapping.put(COL_IND_CHARGING_STRATEGY_ID, "chargingStrategyId");
		columnNameMapping.put(COL_IND_PLAN_SCORE, "planScore");
		columnNameMapping.put(COL_IND_CHARGING_SEQUENCE_SCORE, "chargingSequenceScore");
		columnNameMapping.put(COL_IND_ACT_END_TIME, "actEndTime");
		columnNameMapping.put(COL_IND_NUMBER_OF_EVPLANS_IN_MEMORY, "numberOfEVPlansInMemory");
		columnNameMapping.put(COL_IND_PLAN_NUMBER, "planNumber");
		columnNameMapping.put(COL_IND_IS_SELECTED_EV_DAILY_PLAN, "isSelectedEVDailyPlan");
		
		String[] row = new String[columnNameMapping.size()];
		
		for (int i=0;i<row.length;i++){
			row[i]=columnNameMapping.get(i);
		}
		
		writeRow(row);
	}
	
	public void writeEVDailyPlan(EVDailyReplanable evDailyReplanable) {
		LinkedList<EVDailyPlan> evDailyPlans = evDailyReplanable.getEVDailyPlans();
		int planNumber=0;
		for (EVDailyPlan evDailyPlan:evDailyPlans){
			writeEVDailyPlan(evDailyPlan,evDailyPlans.size(),planNumber++,evDailyPlan==evDailyReplanable.getSelectedEvDailyPlan());
		}
	}

	public void writeEVDailyPlan(EVDailyPlan evDailyPlan, int numberOfPlansInMemory, int planNumber, boolean isSelectedPlan) {
		String[] row;
		int planElementIndex = 0;
		
		row = new String[columnNameMapping.size()];

		row[COL_IND_PERSON_ID] = evDailyPlan.plan.getPerson().getId().toString();
		row[COL_IND_TYPE] = "plan";
		row[COL_IND_PLAN_SCORE] = Double.toString(evDailyPlan.getMatsimPlanScore());
		row[COL_IND_CHARGING_SEQUENCE_SCORE] = Double.toString(evDailyPlan.getChargingStrategiesForTheDay().getScore());
		row[COL_IND_NUMBER_OF_EVPLANS_IN_MEMORY] = Integer.toString(numberOfPlansInMemory);
		row[COL_IND_PLAN_NUMBER] = Integer.toString(planNumber);
		row[COL_IND_IS_SELECTED_EV_DAILY_PLAN] = Boolean.toString(isSelectedPlan);

		writeRow(row);
		
		for (PlanElement pe : evDailyPlan.plan.getPlanElements()) {
			row = new String[columnNameMapping.size()];

			row[COL_IND_PERSON_ID] = evDailyPlan.plan.getPerson().getId().toString();
			row[COL_IND_PLAN_ELEM_INDEX] = Integer.toString(planElementIndex);

			if (pe instanceof Activity) {
				Activity act=(Activity) pe;
				row[COL_IND_TYPE] = "act";
				row[COL_IND_ACT_END_TIME] = Double.toString(act.getEndTime());
			}

			if (pe instanceof Leg) {
				row[COL_IND_TYPE] = "leg";
				ChargingStrategy chargingStrategyForLeg = evDailyPlan.getChargingStrategyForLeg(planElementIndex);
				row[COL_IND_CHARGING_STRATEGY_ID] = chargingStrategyForLeg.getId().toString();
			}

			writeRow(row);

			planElementIndex++;
		}


	}

	private void writeRow(String row[]) {
		try {
			for (int i = 0; i < row.length; i++) {
				String str = row[i];
				if (str != null) {
					this.out.append(str);
				}

				if (i < row.length - 1) {
					this.out.append(",");
				} else {
					this.out.append("\n");
				}

			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void flushBuffer() {
		try {
			this.out.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void closeFile() {
		try {
			this.out.close();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
