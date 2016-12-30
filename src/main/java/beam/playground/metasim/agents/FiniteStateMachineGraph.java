package beam.playground.metasim.agents;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import org.apache.log4j.Logger;
import org.jdom.Element;
import org.xml.sax.SAXException;

import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.actions.ActionFactory;
import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.agents.transition.TransitionFactory;

public class FiniteStateMachineGraph {
	private static Logger log = Logger.getLogger(FiniteStateMachineGraph.class);
	State initialState;
	LinkedHashMap<String,State> states = new LinkedHashMap<>();
	LinkedHashMap<String, Transition> transitions = new LinkedHashMap<>();
	LinkedHashMap<String, Action> actions = new LinkedHashMap<>();
	Class<?> assignedClass;

	public FiniteStateMachineGraph(Element elem, ActionFactory actionFactory, TransitionFactory transitionFactory) throws SAXException, ClassNotFoundException {
		if(elem.getAttributeValue("class") == null)throw new SAXException("Finite state machine element ('finiteStateMachine') in XML config file does not have a 'class' attribute");
		this.assignedClass = Class.forName(elem.getAttributeValue("class"));
		for(int j=0; j < elem.getChildren().size(); j++){
			Element graphElem = (Element)elem.getChildren().get(j);
			String graphElemName = graphElem.getName().toLowerCase();
			if(graphElemName.equals("states")){
				for(int k=0; k < graphElem.getChildren().size(); k++){
					Element stateElem = (Element)graphElem.getChildren().get(k);
					if(!stateElem.getName().toLowerCase().equals("state"))throw new SAXException("Unexpected element in config file for finite state machines finiteStateMachines::finiteStateMachine::states::" + stateElem.getName());
					State theState = new State.Default(stateElem.getAttributeValue("name"));
					states.put(theState.getName(), theState);
					if(stateElem.getAttributeValue("type") != null && stateElem.getAttributeValue("type").toLowerCase().equals("initialstate")){
						initialState = theState;
					}
				}
			}else if(graphElemName.equals("transitions")){
				for(int k=0; k < graphElem.getChildren().size(); k++){
					Element transitionElem = (Element)graphElem.getChildren().get(k);
					if(!transitionElem.getName().toLowerCase().equals("transition"))throw new SAXException("Unexpected element in config file for finite state machines finiteStateMachines::finiteStateMachine::transitions::" + transitionElem.getName());
					
					// class
					if(transitionElem.getAttributeValue("class") == null)throw new SAXException("Transition in finite state machine for class " + elem.getAttributeValue("class") + " is missing the 'class' attribute.");
					if(transitions.containsKey(transitionElem.getAttributeValue("class"))){
						log.warn("Transition of class " + transitionElem.getAttributeValue("class") + " is declared more than once for finite state machine of class " + elem.getAttributeValue("class") + ". Only the first declaration will be used.");
					}else{
						// fromState
						if(transitionElem.getAttributeValue("fromState") == null)throw new SAXException("Transition in finite state machine for class " + elem.getAttributeValue("class") + " is missing the 'fromState' attribute.");
						State fromState = states.get(transitionElem.getAttributeValue("fromState"));
						if(fromState == null)throw new SAXException("Transition in finite state machine for class " + elem.getAttributeValue("class") + " specifies an unknown fromState: " + transitionElem.getAttributeValue("fromState"));
						// toState
						if(transitionElem.getAttributeValue("toState") == null)throw new SAXException("Transition in finite state machine for class " + elem.getAttributeValue("class") + " is missing the 'toState' attribute.");
						State toState = states.get(transitionElem.getAttributeValue("toState"));
						if(toState == null)throw new SAXException("Transition in finite state machine for class " + elem.getAttributeValue("class") + " specifies an unknown toState: " + transitionElem.getAttributeValue("toState"));
						// isContingent
						Boolean isContingent = transitionElem.getAttributeValue("isContingent") == null ? false : transitionElem.getAttributeValue("isContingent").toLowerCase().equals("true");
						
						// Make the transition
						Transition theTransition = transitionFactory.create(Class.forName(transitionElem.getAttributeValue("class")), fromState, toState, isContingent);
						transitions.put(transitionElem.getAttributeValue("class"),theTransition);
						fromState.addTransition(theTransition);
					}
				}
			}else if(graphElemName.equals("actions")){
				for(int k=0; k < graphElem.getChildren().size(); k++){
					Element actionElem = (Element)graphElem.getChildren().get(k);
					if(!actionElem.getName().toLowerCase().equals("action"))throw new SAXException("Unexpected element in config file for finite state machines finiteStateMachines::finiteStateMachine::actions::" + actionElem.getName());
					
					// name 
					if(actionElem.getAttributeValue("name") == null)throw new SAXException("Action in finite state machine for class " + elem.getAttributeValue("class") + " is missing the 'name' attribute.");
					if(actions.containsKey(actionElem.getAttributeValue("name"))){
						log.warn("Action named " + actionElem.getAttributeValue("name") + " is declared more than once for finite state machine of class " + elem.getAttributeValue("class") + ". Only the first declaration will be used.");
					}else{
						// state
						if(actionElem.getAttributeValue("state") == null)throw new SAXException("Action in finite state machine for class " + elem.getAttributeValue("class") + " is missing the 'state' attribute.");
						State theState = states.get(actionElem.getAttributeValue("state"));
						if(theState == null)throw new SAXException("Action in finite state machine for class " + elem.getAttributeValue("class") + " specifies an unknown state: " + actionElem.getAttributeValue("state"));
						
						//TODO need to deal with restrictToTransitions here somehow
						// define the subset of transitions that this action uses
						String restrictToTransitions = actionElem.getAttributeValue("restrictToTransitions") == null ? "" : actionElem.getAttributeValue("restrictToTransitions");
						
						// Make the action 
						Action theAction = actionFactory.create(actionElem.getAttributeValue("name"));
						actions.put(theAction.getName(), theAction);
						theState.addAction(theAction);
					}
				}
			}
		}
	}

	public State getInitialState() {
		return initialState;
	}

	public Class<?> getAssignedClass() {
		return assignedClass;
	}

	public LinkedHashMap<String, Action> getActionMap() {
		return actions;
	}

}
