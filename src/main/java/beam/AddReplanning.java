package beam;

import java.util.Collection;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.controler.events.AfterMobsimEvent;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ScoringEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.AfterMobsimListener;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ScoringListener;
import org.matsim.core.controler.listener.StartupListener;

import beam.replanning.ChargingStrategyManager;
import beam.replanning.module.EVSelectorForRemoval;

public class AddReplanning implements IterationStartsListener, AfterMobsimListener, IterationEndsListener, ScoringListener, StartupListener, BeforeMobsimListener {

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		// Collection<? extends Person> persons
		// =event.getServices().getScenario().getPopulation().getPersons().values();
		// int numberOfCarLegs=0;
		// for (Person person : persons) {
		// for (PlanElement pe : person.getSelectedPlan().getPlanElements()) {
		// if (pe instanceof LegImpl){
		// LegImpl leg=(LegImpl) pe;
		// if (leg.getMode().equalsIgnoreCase("car")){
		// leg.setMode("ev");
		// numberOfCarLegs++;
		// }
		// }
		// }
		// }
		// System.out.println(numberOfCarLegs);
		// System.out.println();
		
		// remove all plan, strategy pairs, where plan is already deleted (otherwise, it might be selected for next iteration).
//		Collection<? extends Person> persons = event.getServices().getScenario().getPopulation().getPersons().values();
//		for (Person person : persons) {
//			ChargingStrategyManager.getReplanable(person.getId()).removeObsoleteDailyPlans();
//			if (ChargingStrategyManager.getReplanable(person.getId()).getEVDailyPlans().size()<person.getPlans().size()){
//				System.out.println();
//			}
//		}
		
	}

	@Override
	public void notifyAfterMobsim(AfterMobsimEvent event) {
		// int numberOfEVLegs=0;
		// Collection<? extends Person> persons
		// =event.getServices().getScenario().getPopulation().getPersons().values();
		//
		// for (Person person : persons) {
		// for (PlanElement pe : person.getSelectedPlan().getPlanElements()) {
		// if (pe instanceof LegImpl){
		// LegImpl leg=(LegImpl) pe;
		// if (leg.getMode().equalsIgnoreCase("bayPT")){
		// //leg.setMode("car");
		// numberOfEVLegs++;
		// }
		// }
		// }
		// }
		// System.out.println(numberOfEVLegs);
		// System.out.println();
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		// update score of current plan
		//System.out.println();

	}

	@Override
	public void notifyScoring(ScoringEvent event) {
		// write score to agent plan itself => MATSim standard score statistics are updated with this
		Collection<? extends Person> persons = event.getServices().getScenario().getPopulation().getPersons().values();
		for (Person person : persons) {
			ChargingStrategyManager.data.getReplanable(person.getId()).getSelectedEvDailyPlan().setMatsimPlanScore(person.getSelectedPlan().getScore());
			person.getSelectedPlan().setScore(ChargingStrategyManager.data.getReplanable(person.getId()).getSelectedEvDailyPlan().getScore());
		}
	}

	@Override
	public void notifyStartup(StartupEvent event) {
		event.getServices().getStrategyManager().setPlanSelectorForRemoval(new EVSelectorForRemoval());
		
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		
		Collection<? extends Person> persons = event.getServices().getScenario().getPopulation().getPersons().values();
		for (Person person : persons) {
			// for newly created plans we need to make sure they are prepared to operate in our framework
			if (ChargingStrategyManager.data.getReplanable(person.getId()).getSelectedEvDailyPlan().plan!=person.getSelectedPlan()){
				ChargingStrategyManager.data.createNewDailyPlanWithSelectedPlan(person);
			}
			
			// make sure that matsim plans which have been deleted, all charging strategies pairs where they are involved are also deleted.
			ChargingStrategyManager.data.getReplanable(person.getId()).removeObsoleteDailyPlans();
//			if (ChargingStrategyManager.getReplanable(person.getId()).getEVDailyPlans().size()<person.getPlans().size()){
//				System.out.println();
//			}
		}
	}

}
