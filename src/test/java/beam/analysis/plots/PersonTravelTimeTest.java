package beam.analysis.plots;

import beam.sim.metrics.NoOpSimulationMetricCollector$;
import org.junit.Before;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.core.events.handler.BasicEventHandler;

public class PersonTravelTimeTest {

    private static class PersonTravelTimeHandler implements BasicEventHandler {

        private final PersonTravelTimeAnalysis personTravelTimeStats;

        PersonTravelTimeHandler(PersonTravelTimeAnalysis personTravelTimeStats) {
            this.personTravelTimeStats = personTravelTimeStats;
        }

        @Override
        public void handleEvent(Event event) {
            if (event instanceof PersonDepartureEvent || event.getEventType().equalsIgnoreCase(PersonDepartureEvent.EVENT_TYPE)) {
                personTravelTimeStats.processStats(event);
            } else if (event instanceof PersonArrivalEvent || event.getEventType().equalsIgnoreCase(PersonArrivalEvent.EVENT_TYPE)) {
                personTravelTimeStats.processStats(event);
            }
        }
    }

    private PersonTravelTimeAnalysis personTravelTimeStats = new PersonTravelTimeAnalysis(NoOpSimulationMetricCollector$.MODULE$,
            new PersonTravelTimeAnalysis.PersonTravelTimeComputation() {
            }, true);

    @Before
    public void setUpClass() {
        GraphTestUtil.createDummySimWithXML(new PersonTravelTimeHandler(personTravelTimeStats));
        personTravelTimeStats.compute();
    }

    /*
    @Test
    public void testShouldPassShouldReturnAvgTimeForSpecificHour() {
        /**
         * 0 index represent CAR count
         * 1 index represent DriveTran count
         * 2 index represent RideHail count
         * 3 index represent Walk count
         * 4 index represent WalkTran count
         *
        int expectedResultOfMode[] = {3, 0, 4, 32, 17};
        int actualResultOfMode[] = {
                (int) Math.ceil(statsComputed[0][6]),
                (int) Math.ceil(statsComputed[1][6]),
                (int) Math.ceil(statsComputed[2][6]),
                (int) Math.ceil(statsComputed[3][6]),
                (int) Math.ceil(statsComputed[4][6])
        };
        assertArrayEquals(expectedResultOfMode, actualResultOfMode);
    }
    */

}
