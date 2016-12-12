package beam.playground.agents.behavior;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

import beam.playground.exceptions.InactiveBehaviorException;

public class BehaviorInvocationHandler implements InvocationHandler {

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Method isActiveMethod = proxy.getClass().getMethod("isActive");

        // invoke the method
        Boolean isActive = (Boolean) isActiveMethod.invoke(proxy);

        if(isActive){
			// invoke the method to which the call was made
			return(method.invoke(proxy, args));
        }else{
        	throw new InactiveBehaviorException("");
        }
	}
}
