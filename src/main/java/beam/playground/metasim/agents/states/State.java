package beam.playground.metasim.agents.states;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.anarres.graphviz.builder.GraphVizGraph;
import org.anarres.graphviz.builder.GraphVizScope;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.actions.Action;
import beam.playground.metasim.agents.transition.Transition;
import beam.playground.metasim.scheduler.ActionCallBack;

public interface State {
	public String getName();
	public void addTransition(Transition transition);
	public Collection<Transition> getAllTranstions();
	public Collection<Transition> getContingentTranstions();
	public Collection<Transition> getNonContingentTranstions();
	public void addAction(Action action);
	public Collection<Action> getAllActions();
	public void addStateEntryListener(StateEnterExitListener listener);
	public void addStateExitListener(StateEnterExitListener listener);
	public List<ActionCallBack> enterState(BeamAgent agent);
	public List<ActionCallBack> exitState(BeamAgent agent);
	
	public class Default implements State,GraphVizScope {
		private String name;
		private LinkedList<Transition> contingentTransitionsFromThisState = new LinkedList<>(), nonContingentTransitionsFromThisState = new LinkedList<>();
		private LinkedList<Action> actions = new LinkedList<>();
		private LinkedList<StateEnterExitListener> entryListeners = new LinkedList<>(), exitListeners = new LinkedList<>();

		public Default(String name) {
			super();
			this.name = name;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public void addTransition(Transition transition) {
			if(transition.isContingent()){
				contingentTransitionsFromThisState.add(transition);
			}else{
				nonContingentTransitionsFromThisState.add(transition);
			}
		}

		@Override
		public Collection<Transition> getAllTranstions() {
			LinkedList<Transition> allTransitions = new LinkedList<>();
			allTransitions.addAll(contingentTransitionsFromThisState);
			allTransitions.addAll(nonContingentTransitionsFromThisState);
			return allTransitions;
		}

		@Override
		public Collection<Transition> getContingentTranstions() {
			return contingentTransitionsFromThisState;
		}

		@Override
		public Collection<Transition> getNonContingentTranstions() {
			return nonContingentTransitionsFromThisState;
		}

		@Override
		public void addAction(Action action) {
			actions.add(action);
		}

		@Override
		public Collection<Action> getAllActions() {
			return actions;
		}
		
		public String toString(){
			return "State:"+name;
		}

		@Override
		public void addStateEntryListener(StateEnterExitListener listener) {
			entryListeners.add(listener);
		}
		@Override
		public void addStateExitListener(StateEnterExitListener listener) {
			exitListeners.add(listener);
		}

		@Override
		public List<ActionCallBack> enterState(BeamAgent agent) {
			List<ActionCallBack> callbacks = new LinkedList<>();
			for(StateEnterExitListener listener : entryListeners){
				callbacks.addAll(listener.notifyOfStateEntry(agent));
			}
			return callbacks;
		}

		@Override
		public List<ActionCallBack> exitState(BeamAgent agent) {
			List<ActionCallBack> callbacks = new LinkedList<>();
			for(StateEnterExitListener listener : exitListeners){
				callbacks.addAll(listener.notifyOfStateExit(agent));
			}
			return callbacks;
		}

	}

}
