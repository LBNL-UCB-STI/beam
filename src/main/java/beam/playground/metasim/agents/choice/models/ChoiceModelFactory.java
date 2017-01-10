package beam.playground.metasim.agents.choice.models;

import org.jdom.Element;

import beam.playground.metasim.agents.choice.models.ChoiceModel;

public interface ChoiceModelFactory {

	RandomTransition create();
	ModeChoice create(Element child);
}
