package beam.playground.metasim.agents.choice.models;

import java.util.LinkedList;

import org.jdom.Element;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import beam.playground.metasim.BeamMode;
import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.services.BeamServices;

public class ModeChoice implements ChoiceModel{
	private BeamServices beamServices;

	@Inject
	public ModeChoice(@Assisted Element params, BeamServices beamServices){
		super();
		this.beamServices = beamServices;
		// Use params here
	}
	@Override
	public Transition selectTransition(BeamAgent agent, LinkedList<Transition> transitions) {
		PersonAgent person = (PersonAgent)agent;
		person.setChosenMode(BeamMode.WALK);
		return transitions.getFirst();
	}
}