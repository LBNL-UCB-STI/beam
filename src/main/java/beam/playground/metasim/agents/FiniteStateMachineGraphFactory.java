package beam.playground.metasim.agents;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.matsim.core.controler.MatsimServices;
import org.xml.sax.SAXException;

import com.google.inject.Inject;
import com.google.inject.Provider;

import beam.playground.metasim.agents.actions.ActionFactory;
import beam.playground.metasim.agents.transition.TransitionFactory;
import beam.playground.metasim.services.BeamServices;

public interface FiniteStateMachineGraphFactory {
	FiniteStateMachineGraph create(Element fsmElem) throws ClassNotFoundException, SAXException;

	public class Default implements FiniteStateMachineGraphFactory{
		ActionFactory actionFactory;
		Provider<BeamServices> beamServicesProvider;
		Provider<MatsimServices> matsimServicesProvider;
		TransitionFactory transitionFactory;

		@Inject
		public Default(Provider<BeamServices> beamServicesProvider, Provider<MatsimServices> matsimServicesProvider, ActionFactory actionFactory, TransitionFactory transitionFactory){
			this.beamServicesProvider = beamServicesProvider;
			this.matsimServicesProvider = matsimServicesProvider;
			this.actionFactory = actionFactory;
			this.transitionFactory = transitionFactory;
		}

		@Override
		public FiniteStateMachineGraph create(Element fsmElem) throws ClassNotFoundException, SAXException {
			return new FiniteStateMachineGraph(fsmElem, actionFactory, transitionFactory, matsimServicesProvider.get(), beamServicesProvider.get());
		}

	}
}