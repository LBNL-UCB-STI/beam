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

package beam.playground.physicalSimProtoType.AkkaJDEQSim.messages;

import akka.actor.ActorRef;
import beam.playground.physicalSimProtoType.AkkaJDEQSim.scheduler.TriggerMessage;
import beam.playground.physicalSimProtoType.oldJDEQSim.SimUnit;

/**
 * The basic EventMessage type.
 *
 * @author rashid_waraich
 */
public abstract class EventMessage extends TriggerMessage {
	public ActorRef vehicle;
	public ActorRef scheduler;
	private ActorRef sendingUnit;

	public EventMessage(ActorRef scheduler, ActorRef vehicle) {
		this.vehicle = vehicle;
		this.scheduler = scheduler;
	}

	public void resetMessage(ActorRef scheduler, ActorRef vehicle) {
		this.scheduler = scheduler;
		this.vehicle = vehicle;
	}
	
	public ActorRef getSendingUnit() {
		return sendingUnit;
	}

	public void setSendingUnit(ActorRef sendingUnit) {
		this.sendingUnit = sendingUnit;
	}

}
