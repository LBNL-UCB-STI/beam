package beam.utils;

import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation;

/**
 * BEAM
 */
public class GeoUtils {

    static GeotoolsTransformation utm2Wgs = new GeotoolsTransformation("EPSG:26910", "EPSG:4326");
    static GeotoolsTransformation wgs2Utm = new GeotoolsTransformation("EPSG:4326", "EPSG:26910");

    public static Coord transformToWgs(Coord coord){
        if (coord.getX() > 360.0 | coord.getX() < -360.0) {
            return (utm2Wgs.transform(coord));
        } else {
            return coord;
        }
    }
    public static Coord transformToUtm(com.vividsolutions.jts.geom.Coordinate coord){
        return transformToUtm(new Coord(coord.x, coord.y));
    }
    public static Coord transformToUtm(Coord coord){
        return (wgs2Utm.transform(coord));
    }
//        def distLatLon2Meters(x1: Double, y1: Double, x2: Double, y2: Double): Double = {
//                //    http://stackoverflow.com/questions/837872/calculate-distance-in-meters-when-you-know-longitude-and-latitude-in-java
//                val earthRadius = 6371000
//                val distX = Math.toRadians(x2 - x1)
//                val distY = Math.toRadians(y2 - y1)
//                val a = Math.sin(distX / 2) * Math.sin(distX / 2) + Math.cos(Math.toRadians(x1)) * Math.cos(Math.toRadians(x2)) * Math.sin(distY / 2) * Math.sin(distY / 2)
//                val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
//                val dist = earthRadius * c
//                dist
//
//        }
//
//
//    }
}
