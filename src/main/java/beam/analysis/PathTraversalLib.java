package beam.analysis;

import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.collections.Tuple;

import java.util.LinkedList;
import java.util.Map;

public class PathTraversalLib {


    public static LinkedList<String> getLinkIdList(String links) {
        LinkedList<String> linkIds = new LinkedList<String>();
        if (links.trim().length() != 0) {
            for (String link : links.split(",")) {
                linkIds.add(link.trim());
            }
        }

        return linkIds;
    }

    public static Tuple<Coord,Coord> getStartAndEndCoordinates(Map<String,String> pathTraversalEventAttributes){
        Coord startCoord=new Coord(Double.parseDouble(pathTraversalEventAttributes.get("start.x")),Double.parseDouble(pathTraversalEventAttributes.get("start.y")));
        Coord endCoord=new Coord(Double.parseDouble(pathTraversalEventAttributes.get("end.x")),Double.parseDouble(pathTraversalEventAttributes.get("end.y")));
        return new Tuple<>(startCoord,endCoord);
    }


}
