package beam.playground.metasim.agents;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.input.SAXBuilder;

import com.google.inject.Inject;
import com.google.inject.Provider;

import beam.playground.metasim.agents.actions.ActionFactory;
import beam.playground.metasim.agents.transition.TransitionFactory;
import beam.playground.metasim.services.BeamServices;

public class FiniteStateMachineGraphFactoryImpl implements FiniteStateMachineGraphFactory{
	ActionFactory actionFactory;
	Provider<BeamServices> beamServicesProvider;
	TransitionFactory transitionFactory;

	@Inject
	public FiniteStateMachineGraphFactoryImpl(Provider<BeamServices> beamServicesProvider, ActionFactory actionFactory, TransitionFactory transitionFactory){
		this.beamServicesProvider = beamServicesProvider;
		this.actionFactory = actionFactory;
		this.transitionFactory = transitionFactory;
	}

	@Override
	public FiniteStateMachineGraph create(String filePath) {
		SAXBuilder saxBuilder = new SAXBuilder();
		InputStream stream = new FileInputStream(new File(filePath));
		Document document = saxBuilder.build(stream);
		
		for(int i=0; i < document.getRootElement().getChildren().size(); i++){
			Element elem = (Element)document.getRootElement().getChildren().get(i);
			if(elem.getName().toLowerCase().equals("state")){
				isMeteredByHour = false;
				for(int j=0; j < elem.getChildren().size(); j++){
					Element levelElem = (Element)elem.getChildren().get(j);
				}
			}
		}
		return null;
	}

}
