package beam.analysis;

import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.collections.Tuple;

import java.util.LinkedList;
import java.util.Map;

/**
 * @author rwaraich
 */
public class PathTraversalLib {


    public static LinkedList<String> getLinkIdList(String links, String separator) {
        LinkedList<String> linkIds = new LinkedList<>();
        if (links.trim().length() != 0) {
            for (String link : links.split(separator)) {
                linkIds.add(link.trim());
            }
        }

        return linkIds;
    }


    public static boolean hasEmptyStartOrEndCoordinate(Map<String, String> pathTraversalEventAttributes) {
        Tuple<Coord, Coord> startAndEndCoordinates = getStartAndEndCoordinates(pathTraversalEventAttributes);
        return startAndEndCoordinates.getFirst() == null || startAndEndCoordinates.getSecond() == null;
    }

    public static Tuple<Coord, Coord> getStartAndEndCoordinates(Map<String, String> pathTraversalEventAttributes) {
        Coord startCoord = null;
        Coord endCoord = null;
        if (pathTraversalEventAttributes.get("start.x").length() != 0 && pathTraversalEventAttributes.get("start.y").length() != 0) {
            startCoord = new Coord(Double.parseDouble(pathTraversalEventAttributes.get("start.x")), Double.parseDouble(pathTraversalEventAttributes.get("start.y")));
        }

        if (pathTraversalEventAttributes.get("end.x").length() != 0 && pathTraversalEventAttributes.get("end.y").length() != 0) {
            endCoord = new Coord(Double.parseDouble(pathTraversalEventAttributes.get("end.x")), Double.parseDouble(pathTraversalEventAttributes.get("end.y")));
        }

        return new Tuple<>(startCoord, endCoord);
    }


}
