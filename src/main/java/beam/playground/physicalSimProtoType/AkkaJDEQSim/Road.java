package beam.playground.physicalSimProtoType.AkkaJDEQSim;

import java.util.HashMap;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;

public class Road extends UntypedActor {

	private static HashMap<Id<Link>, ActorRef> roads;

	public static void addRoad(Id<Link> linkId, ActorRef road){
		roads.put(linkId, road);
	}
	
	public static ActorRef getRoad(Id<Link> linkId) {
		return roads.get(linkId);
	}
	
	
	
	@Override
	public void onReceive(Object message) throws Throwable {
		// TODO Auto-generated method stub
	}
	
	

}
