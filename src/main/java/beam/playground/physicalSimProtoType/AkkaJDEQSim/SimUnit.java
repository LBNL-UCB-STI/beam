/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package beam.playground.physicalSimProtoType.AkkaJDEQSim;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import beam.playground.beamSimAkkaProtoType.scheduler.StartSimulationMessage;
import beam.playground.physicalSimProtoType.AkkaJDEQSim.messages.EventMessage;
import beam.playground.physicalSimProtoType.AkkaJDEQSim.scheduler.TriggerMessage;

/**
 * The basic building block for all simulation units.
 *
 * @author rashid_waraich
 */
public abstract class SimUnit extends UntypedActor {

	protected ActorRef scheduler = null;

	public SimUnit(ActorRef scheduler) {
		this.scheduler = scheduler;
	}

	public void sendMessage(EventMessage m, ActorRef sendingActor, ActorRef receivingActor, double messageArrivalTime) {
		m.setSendingUnit(sendingActor);
		m.setReceivingActorRef(receivingActor);
		m.setMessageArrivalTime(messageArrivalTime);
		scheduler.tell(m, sendingActor);
	}

}
