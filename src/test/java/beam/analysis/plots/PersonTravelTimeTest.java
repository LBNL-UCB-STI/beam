package beam.analysis.plots;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.core.events.handler.BasicEventHandler;
import org.matsim.core.utils.collections.Tuple;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;

public class PersonTravelTimeTest {

    private class PersonTravelTimeHandler implements BasicEventHandler {

        private final PersonTravelTimeStats personTravelTimeStats;

        PersonTravelTimeHandler(PersonTravelTimeStats personTravelTimeStats){
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

    private double[][] statsComputed;

    private PersonTravelTimeStats personTravelTimeStats = new PersonTravelTimeStats(new PersonTravelTimeStats.PersonTravelTimeComputation() {
        @Override
        public Tuple<List<String>, double[][]> compute(Map<String, Map<Integer, List<Double>>> stat) {
            Tuple<List<String>, double[][]> compute = super.compute(stat);
            statsComputed = compute.getSecond();
            return compute;
        }
    });

    @Before
    public void setUpClass() {
        statsComputed = null;
        GraphTestUtil.createDummySimWithXML(new PersonTravelTimeHandler(personTravelTimeStats));
    }

    @Test
    public void testShouldPassShouldReturnAvgTimeForSpecificHour() {
        /**
         * 0 index represent CAR count
         * 1 index represent DriveTran count
         * 2 index represent RideHail count
         * 3 index represent Walk count
         * 4 index represent WalkTran count
         */
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

}
