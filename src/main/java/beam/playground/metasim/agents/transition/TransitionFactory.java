package beam.playground.metasim.agents.transition;

import org.matsim.core.controler.MatsimServices;

import com.google.inject.Inject;
import com.google.inject.Provider;

import beam.playground.metasim.agents.states.State;
import beam.playground.metasim.services.BeamServices;

public interface TransitionFactory {
	Transition create(Class transitionClass, State fromState, State toState, Boolean isContingent);
	
	public class Default implements TransitionFactory{
		private final Provider<BeamServices> beamServicesProvider;
		private final Provider<MatsimServices> matsimServiceProvider;

		@Inject
		public Default(Provider<BeamServices> beamServicesProvider, Provider<MatsimServices> matsimServiceProvider){
			this.beamServicesProvider = beamServicesProvider;
			this.matsimServiceProvider = matsimServiceProvider;
		}

		@Override
		public Transition create(Class transitionClass, State fromState, State toState, Boolean isContingent) {
			Transition transition = null;
			try {
				transition = (Transition)transitionClass.newInstance();
			} catch (InstantiationException | IllegalAccessException e) {
				e.printStackTrace();
			}
			transition.initialize(fromState, toState, isContingent, beamServicesProvider.get(), matsimServiceProvider.get());
			return transition;
		}
	}
}
