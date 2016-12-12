package beam.playground.agents.behavior;

import java.lang.reflect.Proxy;

import beam.playground.agents.BeamAgent;

public class BaseBehavior implements Behavior {
	Boolean isActive;
	BeamAgent agent;

	public BaseBehavior(Boolean isActive, BeamAgent agent) {
		super();
		this.isActive = isActive;
		this.agent = agent;
	}

	//TODO see if using proxy instance is possible in BaseBehavior while also extending to subclass methods
//	public static Behavior newInstance(){
//		return (Behavior) Proxy.newProxyInstance(BaseBehavior.class.getClassLoader(), new Class[] {BaseBehavior.class}, new BehaviorInvocationHandler());
//	}

	@Override
	public void activate() {
		isActive = true;
	}

	@Override
	public void deactivate() {
		isActive = false;
	}

	@Override
	public Boolean isActive() {
		return isActive;
	}

}
