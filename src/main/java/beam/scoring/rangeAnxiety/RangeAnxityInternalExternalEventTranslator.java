package beam.scoring.rangeAnxiety;

import java.util.HashMap;
import java.util.HashSet;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.Controler;

import beam.EVGlobalData;
import beam.controller.EVController;
import beam.events.ArrivalChargingDecisionEvent;
import beam.events.ArrivalChargingDecisionEventHandler;
import beam.events.BeginChargingSessionEvent;
import beam.events.BeginChargingSessionEventHandler;
import beam.events.DepartureChargingDecisionEvent;
import beam.events.DepartureChargingDecisionEventHandler;
import beam.events.EndChargingSessionEvent;
import beam.events.EndChargingSessionEventHandler;
import beam.events.ReassessDecisionEvent;
import beam.parking.lib.DebugLib;
import beam.parking.lib.GeneralLib;
import beam.parking.lib.obj.DoubleValueHashMap;
import beam.scoring.EVScoreDataCollector;
import beam.scoring.EVScoreEventGenerator;

public class RangeAnxityInternalExternalEventTranslator extends EVScoreDataCollector {

	private InternalRangeAnxityEventHandler internalRangeAnxityEventHandler;

	private DoubleValueHashMap<Id<Person>> lastSocLogged;

	private DoubleValueHashMap<Id<Person>> timeLastInternalRangeAnxityEventGenerated;

	private DoubleValueHashMap<Id<Person>> lastArrivalOfDay;

	private HashSet<Id<Person>> hasGeneratedFirstInternalEventOfDay;

	private final double epsilon = 0.000000000000001;

	public RangeAnxityInternalExternalEventTranslator(EVController controler) {
		super(controler);
	}

	@Override
	public void reset(int iteration) {
		super.reset(iteration);

		lastSocLogged = new DoubleValueHashMap<>();
		timeLastInternalRangeAnxityEventGenerated = new DoubleValueHashMap<>();
		lastArrivalOfDay = new DoubleValueHashMap<>();
		hasGeneratedFirstInternalEventOfDay = new HashSet<>();

		internalRangeAnxityEventHandler.reset(iteration);
	}

	@Override
	public void handleEvent(BeginChargingSessionEvent event) {
		super.handleEvent(event);

	}

	@Override
	public void handleEvent(DepartureChargingDecisionEvent event) {
		super.handleEvent(event);

		if (!hasGeneratedFirstInternalEventOfDay.contains(event.getPersonId())) {
			hasGeneratedFirstInternalEventOfDay.add(event.getPersonId());
			timeLastInternalRangeAnxityEventGenerated.put(event.getPersonId(), event.getTime());
			lastSocLogged.put(event.getPersonId(), 1.0); // TODO: discuss this
															// assumption
		}

		generateInternalRangeAnxityEvents(event.getPersonId(), timeLastInternalRangeAnxityEventGenerated.get(event.getPersonId()), event.getTime(),
				event.getSoc());
	}

	@Override
	public void handleEvent(ReassessDecisionEvent event) {
		super.handleEvent(event);
		generateInternalRangeAnxityEvents(event.getPersonId(), timeLastInternalRangeAnxityEventGenerated.get(event.getPersonId()), event.getTime(),
				event.getSoC());
	}

	@Override
	public void handleEvent(EndChargingSessionEvent event) {
		super.handleEvent(event);
		generateInternalRangeAnxityEvents(event.getPersonId(), timeLastInternalRangeAnxityEventGenerated.get(event.getPersonId()), event.getTime(),
				event.getSoC());
	}

	@Override
	public void handleEvent(ArrivalChargingDecisionEvent event) {
		super.handleEvent(event);
		generateInternalRangeAnxityEvents(event.getPersonId(), timeLastInternalRangeAnxityEventGenerated.get(event.getPersonId()), event.getTime(),
				event.getSoC());
	}

	private void generateInternalRangeAnxityEvents(Id<Person> personId, double timeLastInternalRangeAnxityEventGenerated, double currentTime,
			double newSoc) {

		double intervalDuration = GeneralLib.getIntervalDuration(timeLastInternalRangeAnxityEventGenerated, currentTime);
		double deltaSoc = newSoc - this.lastSocLogged.get(personId);

		if (Math.abs(deltaSoc) < epsilon) {
			deltaSoc = 0;
			this.lastSocLogged.put(personId, newSoc);
		}

		if (intervalDuration >= EVGlobalData.data.RANGE_ANXITY_SAMPLING_INTERVAL_IN_SECONDS) {
			while (GeneralLib.getIntervalDuration(this.timeLastInternalRangeAnxityEventGenerated.get(personId),
					currentTime) >= EVGlobalData.data.RANGE_ANXITY_SAMPLING_INTERVAL_IN_SECONDS) {
				double internalEventTime = this.timeLastInternalRangeAnxityEventGenerated.get(personId)
						+ EVGlobalData.data.RANGE_ANXITY_SAMPLING_INTERVAL_IN_SECONDS;
				double internalEventSoc = this.lastSocLogged.get(personId)
						+ deltaSoc / intervalDuration * EVGlobalData.data.RANGE_ANXITY_SAMPLING_INTERVAL_IN_SECONDS;

				InternalRangeAnxityEvent event;

				if (isLastArrivalOfDay(personId)) {

					double firstDepartureTimeOfDay = currentTime;

					if (this.timeLastInternalRangeAnxityEventGenerated.get(personId) > firstDepartureTimeOfDay
							+ EVGlobalData.data.NUMBER_OF_SECONDS_IN_ONE_DAY) {
						// stop producing events at departure time of next day
						break;
					}

					event = new InternalRangeAnxityEvent(personId, lastArrivalOfDay.get(personId), internalEventTime, internalEventSoc);

				} else {
					event = new InternalRangeAnxityEvent(personId, currentTime, internalEventTime, internalEventSoc);
				}
				EVGlobalData.data.eventLogger.processEvent(event);
				getInternalRangeAnxityEventHandler().handleEvent(event);

				this.timeLastInternalRangeAnxityEventGenerated.put(personId, internalEventTime);
				this.lastSocLogged.put(personId, internalEventSoc);
			}
		}
	}

	private InternalRangeAnxityEventHandler getInternalRangeAnxityEventHandler() {
		return internalRangeAnxityEventHandler;
	}

	public void setInternalRangeAnxityEventHandler(InternalRangeAnxityEventHandler internalRangeAnxityEventHandler) {
		this.internalRangeAnxityEventHandler = internalRangeAnxityEventHandler;
	}

	@Override
	public void handleEvent(PersonArrivalEvent event) {
		super.handleEvent(event);

		if (isLastArrivalOfDay(event.getPersonId())) {
			lastArrivalOfDay.put(event.getPersonId(), event.getTime());
			generateInternalRangeAnxityEvents(event.getPersonId(), timeLastInternalRangeAnxityEventGenerated.get(event.getPersonId()),
					firstDepartureTimeOfDay.get(event.getPersonId()), lastSocLogged.get(event.getPersonId()));

			getInternalRangeAnxityEventHandler().generateExternalDailyEvent(event.getPersonId(), lastArrivalOfDay.get(event.getPersonId()));
		}

	}

	// TODO: handle last charging of day!!!

}
