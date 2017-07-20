package beam.router.r5;

import beam.router.Modes;
import beam.router.RoutingModel;
import beam.utils.exception.BeamException;
import com.conveyal.r5.api.util.*;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.vehicles.Vehicle;
import scala.Option;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;


/**
 * Created by ahmar.nadeem on 7/18/2017.
 */
public class R5RoutingGraphBuilder {

    public RoutingModel buildGraphPath(TransitSegment segment) {
        List<String> activeLinkIds = new ArrayList<String>();
//        //TODO the coords and times should only be collected if the particular logging event that requires them is enabled
        List<Coord> activeCoords = new ArrayList<Coord>();
        List<Long> activeTimes = new ArrayList<Long>();

        TransitJourneyID transitJourneyID = new TransitJourneyID(0, 0);

        Long startTime = null;
        Modes.BeamMode mode = null;
        long travelTime = 0;
        Option<Id<Vehicle>> beamVehicleId = null ;
        Option<String> stopId = null ;
        RoutingModel.BeamGraphPath graphPath = null;

        long arrivalTimeInMillis = 0;
        String vehicleId =  null;
        for (SegmentPattern pattern : segment.segmentPatterns) {

            List<ZonedDateTime> fromArrivalTimeList = pattern.fromArrivalTime;
            List<ZonedDateTime> fromDepartureTimeList = pattern.fromDepartureTime;

            if (fromArrivalTimeList != null && fromArrivalTimeList.size() > 0 &&
                    (fromDepartureTimeList != null && fromDepartureTimeList.size() > 0)) {

                //here we sum up the total time taken by each pattern
                arrivalTimeInMillis = fromArrivalTimeList.get(0).getSecond() * 1000;
                travelTime += arrivalTimeInMillis - (fromDepartureTimeList.get(0).getSecond() * 1000);
            }

            List<String> tripIdList = pattern.tripIds;
            if( tripIdList != null && tripIdList.size() > 0 ){
                if( vehicleId == null ){
                    vehicleId = tripIdList.get(0);
                    continue;
                }
                if( !vehicleId.equals(tripIdList.get(0)) ){
                    throw new BeamException("Vehicle has been changed during the transit segment.");
                }
            }
        }

        RoutingModel.BeamLeg WAITING = new RoutingModel.BeamLeg(startTime, mode, travelTime, graphPath, beamVehicleId, stopId);
        RoutingModel.BeamLeg BOARDING = new RoutingModel.BeamLeg(arrivalTimeInMillis, mode, arrivalTimeInMillis + 5000, graphPath, beamVehicleId, stopId);
        RoutingModel.BeamLeg ALIGHTING = new RoutingModel.BeamLeg(arrivalTimeInMillis + 5000, mode, arrivalTimeInMillis + 10000, graphPath, beamVehicleId, stopId);

        Stop fromPosition = segment.from;
        Stop toPosition = segment.to;

        Coord fromCoord = new Coord(segment.from.lat, segment.from.lon);
        Coord toCoord = new Coord(segment.to.lat, segment.to.lon);

        TransitSegmentBO startPoint = new TransitSegmentBO();
        startPoint.setActiveCoord(fromCoord);
        startPoint.setActiveLinkId(segment.from.stopId);

        //Still struggling to find out a way for getting active time for start and end points.
        startPoint.setActiveTime((long) segment.getTransitTime(transitJourneyID));

        TransitSegmentBO endPoint = new TransitSegmentBO();
        endPoint.setActiveCoord(toCoord);
        endPoint.setActiveLinkId(segment.to.stopId);
        endPoint.setActiveTime((long) segment.getTransitTime(transitJourneyID));


//        TransitSegmentBO segmentBO;
//        for (SegmentPattern pattern : segment.segmentPatterns) {
//
//            int departureTimetime = pattern.;
//
//            segmentBO = new TransitSegmentBO();
//            segmentBO.setActiveCoord();
////      activeTimes = activeTimes :+ pattern.fromDepartureTime.
////      activeCoords = activeCoords :+ toCoord(route.geometry)
//        }
//        return new RoutingModel.BeamGraphPath(activeLinkIds, activeCoords, activeTimes);
        return null;
    }
}
