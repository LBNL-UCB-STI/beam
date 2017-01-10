package beam.playground.metasim.scheduler;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public interface ActionSchedulerFactory {
	ActionScheduler create(Class<? extends ActionScheduler> clazz) throws NoSuchMethodException, SecurityException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException;
	
	public class Default implements ActionSchedulerFactory{

		@Override
		public ActionScheduler create(Class<? extends ActionScheduler> clazz) throws NoSuchMethodException, SecurityException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
			Constructor<?> ctor = clazz.getConstructor();
			return (ActionScheduler) ctor.newInstance(new Object[] { });
		}
	}
}
