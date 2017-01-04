package beam.playground.metasim.services;

import java.util.LinkedHashMap;
import java.util.Random;

import org.jdom.Document;
import org.jdom.Element;
import org.matsim.analysis.CalcLinkStats;
import org.matsim.analysis.IterationStopWatch;
import org.matsim.analysis.ScoreStats;
import org.matsim.analysis.VolumesAnalyzer;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.listener.ControlerListener;
import org.matsim.core.replanning.StrategyManager;
import org.matsim.core.router.TripRouter;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.xml.sax.SAXException;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;

import beam.playground.metasim.agents.BeamAgentPopulation;
import beam.playground.metasim.agents.FiniteStateMachineGraph;
import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.behavior.ChoiceModel;
import beam.playground.metasim.agents.behavior.ChoiceModelFactory;
import beam.playground.metasim.agents.behavior.ModeChoice;
import beam.playground.metasim.agents.behavior.RandomTransition;
import beam.playground.metasim.scheduler.Scheduler;
import beam.playground.metasim.services.config.BeamConfigGroup;
import beam.sim.traveltime.BeamRouter;

public interface ChoiceModelService {
	public void putDefaultChoiceModelForAction(Action action, ChoiceModel choiceModel);
	public void initialize(Document document) throws SAXException, ClassNotFoundException;
	public ChoiceModel getDefaultChoiceModelForAction(Action action);
	public ChoiceModel getDefaultChoiceModelOfClass(Class<?> choiceModelClass);

	public class Default implements ChoiceModelService{
		private LinkedHashMap<Class<?>,ChoiceModel> defaultChoiceModelsByClass = new LinkedHashMap<>();
		private LinkedHashMap<Action,ChoiceModel> defaultActionChoiceModelMap = new LinkedHashMap<>();
		private ChoiceModelFactory choiceModelFactory;

		@Inject
		public Default(ChoiceModelFactory choiceModelFactory){
			this.choiceModelFactory = choiceModelFactory;
		}

		@Override
		public void initialize(Document document) throws SAXException, ClassNotFoundException{
			if(!document.getRootElement().getName().toLowerCase().equals("choicemodels")) throw new SAXException("Unrecognized element "+document.getRootElement().getName()+" at root of choiceModelConfigFile, expecting choiceModels");
			for(int i=0; i < document.getRootElement().getChildren().size(); i++){
				Element elem = (Element)document.getRootElement().getChildren().get(i);
				if(elem.getName().toLowerCase().equals("choicemodel")){
					if(elem.getChild("class") == null)throw new SAXException("Missing 'class' element in choiceModel element of choiceModelConfigFile");
					Class<?> theClass = Class.forName(elem.getChild("class").getValue());
					ChoiceModel theChoiceModel = null;
					if(theClass == RandomTransition.class){
						theChoiceModel = choiceModelFactory.create();
					}else if(theClass == ModeChoice.class){
						theChoiceModel = choiceModelFactory.create(elem.getChild("parameters"));
					}else{
						throw new SAXException("Unrecognized class "+theClass.getCanonicalName() +" under choiceModel with id="+elem.getAttributeValue("id")+" in choiceModelConfigFile");
					}
					defaultChoiceModelsByClass.put(theClass, theChoiceModel);
				}else if(elem.getName().toLowerCase().equals("priorprobability")){
				}else{
					throw new SAXException("Unrecognized element "+elem.getName()+" under choiceModels element in choiceModelConfigFile");
				}
			}
		}
		@Override
		public void putDefaultChoiceModelForAction(Action action, ChoiceModel choiceModel) {
			defaultActionChoiceModelMap.put(action,choiceModel);
		}
		@Override
		public ChoiceModel getDefaultChoiceModelForAction(Action action) {
			return defaultActionChoiceModelMap.get(action);
		}
		@Override
		public ChoiceModel getDefaultChoiceModelOfClass(Class<?> choiceModelClass) {
			return defaultChoiceModelsByClass.get(choiceModelClass);
		}
	}

}
