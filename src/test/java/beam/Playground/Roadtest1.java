package beam.Playground;

import beam.playground.jdeqsim_with_cacc.jdeqsim.Road;
import org.junit.Test;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import static org.junit.Assert.assertEquals;

public class Roadtest1 implements LinkEnterEventHandler, PersonArrivalEventHandler{


   /* @Test
    public void testToSeeAvgTravelTimeIsSame(){
    assertEquals(5,Road.TravelTime);

    }
    @Test
    public void testToSeeTravelTimeIsSame(){
        assertEquals(5,Road.TravelTime);

    }*/

    @Test
    public void handleEvent(LinkEnterEvent event) {

    }

    @Override
    public void handleEvent(PersonArrivalEvent event) {

    }
}