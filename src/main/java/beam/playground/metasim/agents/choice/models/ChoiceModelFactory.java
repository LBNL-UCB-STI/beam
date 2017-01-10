package beam.playground.metasim.agents.behavior;

import org.jdom.Element;

import beam.playground.metasim.agents.behavior.ChoiceModel;

public interface ChoiceModelFactory {

	RandomTransition create();
	ModeChoice create(Element child);
}
