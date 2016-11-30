package beam.sim.scheduler;

import java.lang.reflect.Method;

public class CallBack {
	private double time,timeScheduled;
	private double priority;
	private Object callingObject,targetObject;
	private Method method;
	
	public CallBack(double time, double priority, Object targetObject, String methodName, double timeScheduled, Object callingObject) {
		super();
		this.time = time;
		this.priority = priority;
		this.targetObject = targetObject;
		try {
			this.method = targetObject.getClass().getMethod(methodName);
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		}
		this.timeScheduled = timeScheduled;
		this.callingObject = callingObject;
	}
	public CallBack(double time, double priority, Object targetObject, Method method) {
		super();
		this.time = time;
		this.priority = priority;
		this.targetObject = targetObject;
		this.method = method;
	}
	public CallBack(double time, double priority, Object targetObject, String methodName) {
		super();
		this.time = time;
		this.priority = priority;
		this.targetObject = targetObject;
		try {
			this.method = targetObject.getClass().getMethod(methodName);
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		}
	}
	public Object getTargetObject() {
		return targetObject;
	}
	public Method getMethod() {
		return method;
	}
	public double getTime() {
		return time;
	}
	public void setTime(double time) {
		this.time = time;
	}
	public double getPriority() {
		return priority;
	}
	public void setPriority(double priority) {
		this.priority = priority;
	}
	public String toString(){
		return this.targetObject.getClass().getName() + "::" + this.method.getName() + " @"+this.time + " (" + this.priority + ")";
	}
}
