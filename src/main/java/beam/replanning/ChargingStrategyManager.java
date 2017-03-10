package beam.replanning;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Random;

import beam.BEAMSimTelecontrolerListener;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.gbl.MatsimRandom;

import beam.EVGlobalData;
import beam.logit.DiscreteProbabilityDistribution;
import beam.parking.lib.DebugLib;
import beam.replanning.chargingStrategies.ChargingStrategyAlwaysChargeOnArrival;
import beam.replanning.chargingStrategies.ChargingStrategyChargeOnFirstDeparture;
import beam.replanning.chargingStrategies.ChargingStrategyNestedLogit;
import beam.replanning.chargingStrategies.ChargingStrategyUniformRandom;

public class ChargingStrategyManager {
	
	public static ChargingStrategyManager data=null;
	
	public static void init(){
		data=new ChargingStrategyManager();
		data.rand = MatsimRandom.getLocalInstance();
	}
	
	private final HashMap<Id, EVDailyReplanable> plans = new HashMap<>();
	public LinkedList<ChargingStrategy> allStrategies=new LinkedList<ChargingStrategy>();
	public HashMap<String,DiscreteProbabilityDistribution> priorProbabilityDistributionByActivityType = new HashMap<String,DiscreteProbabilityDistribution>();
	public HashMap<String,ChargingStrategy> strategiesByName = new HashMap<String,ChargingStrategy>();
	private Random rand;
	
	public void loadChargingStrategies(){
		SAXBuilder saxBuilder = new SAXBuilder();
		FileInputStream stream;
		Document document = null;
		try {
			stream = new FileInputStream(EVGlobalData.data.CHARGING_STRATEGIES_FILEPATH);
			document = saxBuilder.build(stream);
		} catch (FileNotFoundException e) {
			DebugLib.stopSystemAndReportInconsistency(e.getMessage());
		} catch (JDOMException | IOException e) {
			DebugLib.stopSystemAndReportInconsistency(e.getMessage());
		}
		
		for(int i=0; i < document.getRootElement().getChildren().size(); i++){
			Element elem = (Element)document.getRootElement().getChildren().get(i);
			if(elem.getName().toLowerCase().equals("strategy")){
				ChargingStrategy newStrategy = null;
				if(elem.getChild("className").getValue().equals("ChargingStrategyUniformRandom")){
					newStrategy = new ChargingStrategyUniformRandom();
				}else if(elem.getChild("className").getValue().equals("ChargingStrategyNestedLogit")){
					newStrategy = new ChargingStrategyNestedLogit();
					BEAMSimTelecontrolerListener.setInitialLogitParams(elem.getChild("parameters"));
				}else if(elem.getChild("className").getValue().equals("ChargingStrategyAlwaysChargeOnArrival")){
					newStrategy = new ChargingStrategyAlwaysChargeOnArrival();
				}else if(elem.getChild("className").getValue().equals("ChargingStrategyChargeOnFirstDeparture")){
					newStrategy = new ChargingStrategyChargeOnFirstDeparture();
				}else{
					DebugLib.stopSystemAndReportInconsistency("No class found with name "+elem.getChild("className").getValue()+ " that implements ChargingStrategy");
				}
				newStrategy.setId(Integer.parseInt(elem.getChild("id").getValue()));
				newStrategy.setName(elem.getChild("name").getValue());
				newStrategy.setParameters(elem.getChild("parameters"));
				allStrategies.add(newStrategy);
				strategiesByName.put(newStrategy.getStrategyName(),newStrategy);
			}else if(elem.getName().toLowerCase().equals("priorprobability")){
				for(int j=0; j < elem.getChildren().size(); j++){
					Element probElem = (Element)elem.getChildren().get(j);
					if(probElem.getName().toLowerCase().equals("conditional")){
						Element conditionedOnElem = (Element)probElem.getChild("conditionedOn");
						if(conditionedOnElem.getAttributeValue("name").toLowerCase().equals("activitytype")){
							for(int k=0; k < conditionedOnElem.getChildren().size(); k++){
								Element activityElem = (Element)conditionedOnElem.getChildren().get(k);
								DiscreteProbabilityDistribution conditionalProb = new DiscreteProbabilityDistribution();
								for(int l=0; l < activityElem.getChildren().size(); l++){
									Element strategyElem = (Element)activityElem.getChildren().get(l);
									conditionalProb.addElement(strategyElem.getAttributeValue("name"), Double.parseDouble(strategyElem.getValue()));
								}
								priorProbabilityDistributionByActivityType.put(activityElem.getName(),conditionalProb);
							}
						}else{
							DebugLib.stopSystemAndReportInconsistency("The prior specification conditionedOn name "+conditionedOnElem.getName()+" is unrecognized in the ChargingStrategiesFile");
						}
					}else{
						DebugLib.stopSystemAndReportInconsistency("The prior specification "+probElem.getName()+" is unrecognized in the ChargingStrategiesFile");
					}
				}
			}
		}
	}
	

	public StrategySequence createStrategySequence(Plan plan){
		HashMap<Integer, ChargingStrategy> strategies=new HashMap<>();
		StrategySequence strategySequence=new StrategySequence(strategies);
		
		// If a prior distribution is not defined, we select as simple random
		if(priorProbabilityDistributionByActivityType == null){
			int currentPlanElementIndex=0;
			for (PlanElement pe:plan.getPlanElements()){
				if (pe instanceof Leg){
					Leg leg=(Leg) pe;
					if (leg.getMode().equalsIgnoreCase(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES)){
						strategies.put(currentPlanElementIndex, getRandomStrategy().copy());
					}
				}
				currentPlanElementIndex++;
			}
		}else{
			int currentPlanElementIndex=0;
			Leg leg = null;
			for (PlanElement pe:plan.getPlanElements()){
				if (pe instanceof Leg){
					leg=(Leg) pe;
					if (!leg.getMode().equalsIgnoreCase(EVGlobalData.data.PLUGIN_ELECTRIC_VEHICLES)){
						leg = null;
					}
				}else if (pe instanceof Activity&& leg != null){
					Activity activity = (Activity) pe;
					strategies.put(currentPlanElementIndex-1,strategiesByName.get(priorProbabilityDistributionByActivityType.get(activity.getType()).sample()).copy());
				}
				currentPlanElementIndex++;
			}
		}
		
		return strategySequence; 
	}
	
	private ChargingStrategy getRandomStrategy(){
		return allStrategies.get(this.rand.nextInt(allStrategies.size()));
	}

	public void createReplanable(Person person) {
		plans.put(person.getId(), new EVDailyReplanable(person));

		// create initial plan
		createNewDailyPlanWithSelectedPlan(person);
	}
	
	public void createReplanableWithStrategySequence(Person person, StrategySequence strategySequence) {
		plans.put(person.getId(), new EVDailyReplanable(person));

		EVDailyPlan evDailyPlan = new EVDailyPlan(person.getSelectedPlan(),strategySequence);
		getReplanable(person.getId()).setSelectedPlan(evDailyPlan);
	}
	

	public  void createNewDailyPlanWithSelectedPlan(Person person) {
		EVDailyPlan evDailyPlan = new EVDailyPlan(person.getSelectedPlan(),createStrategySequence(person.getSelectedPlan()));
		getReplanable(person.getId()).setSelectedPlan(evDailyPlan);
	}

	public EVDailyReplanable getReplanable(Id personId) {
		return plans.get(personId);
	}

}
