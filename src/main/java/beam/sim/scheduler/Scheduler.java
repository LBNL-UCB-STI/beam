package beam.sim.scheduler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Queue;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Identifiable;
import org.matsim.core.utils.collections.Tuple;

import beam.EVGlobalData;
import beam.parking.lib.DebugLib;

public class Scheduler {

	private Queue<CallBack> queue = new PriorityQueue<CallBack>(100,new Comparator<CallBack>() {
		@Override
		public int compare(CallBack a, CallBack b) {
			if(a.getTime() < b.getTime()){
				return -1;
			}else if(a.getTime() > b.getTime()){
				return 1;
			}else if(a.getPriority() < b.getPriority()){
				return -1;
			}else if(a.getPriority() > b.getPriority()){
				return 1;
			}else if(a.getTargetObject() instanceof Identifiable) {
				//TODO this is problematic when id's are not of the same class
				return ((Identifiable<?>)a.getTargetObject()).getId().toString().compareTo(((Identifiable<?>)a.getTargetObject()).getId().toString());
			}else{	
				//TODO make sure target objects are naturally ordered for reproducibility
				return 0;
			}
		}
	});
	
	public CallBack addCallBackMethod(double time, Object targetObject, Method method){
		return addCallBackMethod(time, targetObject, method, 0.0);
	}
	public CallBack addCallBackMethod(double time, Object targetObject, Method method, double priority){
		CallBack callback=new CallBack(time, priority, targetObject, method);
		this.queue.add(callback);
		return callback;
	}
	public CallBack addCallBackMethod(double time, Object targetObject, String methodName, double priority, Object callingObject){
		CallBack callback=new CallBack(time, priority, targetObject, methodName, EVGlobalData.data.now, callingObject);
		this.queue.add(callback);
		return callback;
	}
	public CallBack addCallBackMethod(double time, Object targetObject, String methodName){
		return addCallBackMethod(time,targetObject,methodName,0.0,null);
	}
	public CallBack addCallBackMethod(double time, Object targetObject, String methodName, double priority){
		return addCallBackMethod(time,targetObject,methodName,priority,null);
	}
	
	public void doSimStep(double now) {
		while (queue.peek() != null && queue.peek().getTime() <= now) {
			CallBack entry = queue.poll();
			try {
				entry.getMethod().invoke(entry.getTargetObject(), (Object[]) null);
			} catch (SecurityException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (InvocationTargetException e) {
				e.printStackTrace();
			}
		}
	}
	public int getSize() {
		return 0;
	}
	public void removeCallback(CallBack callback) {
		queue.remove(callback);
	}
	
	
}
