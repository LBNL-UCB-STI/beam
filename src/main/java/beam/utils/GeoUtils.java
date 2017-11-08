package beam.utils;

import beam.EVGlobalData;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import org.geotools.geometry.jts.JTS;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.geotools.referencing.CRS;
import org.matsim.api.core.v01.Coord;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;

/**
 * BEAM
 */
public class GeoUtils {
    private static GeometryFactory geomFactory = JTSFactoryFinder.getGeometryFactory();

    public static Coord transformToWgs(Coord coord){
        if (coord.getX() > 360.0 | coord.getX() < -360.0) {
            Coord result = transform(coord,EVGlobalData.data.targetCoordinateSystem, EVGlobalData.data.wgs84CoordinateSystem);
            //TODO remove this hack which fixes an issue that produces a different result depending on whether BEAM is
            //compiled as a fat jar for running
            if(result.getX() > 0.0) {
                return new Coord(result.getY(), result.getX());
            }else{
                return new Coord(result.getX(), result.getY());
            }
        } else {
            return coord;
        }
    }
    public static Coord transformToUtm(com.vividsolutions.jts.geom.Coordinate coord){
        return transformToUtm(new Coord(coord.x, coord.y));
    }
    public static Coord transformToUtm(Coord coord){
        return transform(coord,EVGlobalData.data.wgs84CoordinateSystem,EVGlobalData.data.targetCoordinateSystem);
    }
    private static Coord transform(Coord coord, CoordinateReferenceSystem source, CoordinateReferenceSystem target) {
        //  Coordinate of the charging site
        Geometry transformedPoint = null;
        Coordinate jtsCoord = null;
        Point jtsPoint = null;
        try {
            // Be careful, JTS reverses the x and y because design
            jtsCoord = new Coordinate(coord.getX(),coord.getY());
            jtsPoint = geomFactory.createPoint(jtsCoord);
            transformedPoint = JTS.transform(jtsPoint, CRS.findMathTransform(source,target));
            if(transformedPoint.getCoordinate().x >=500000.0 && Math.abs(transformedPoint.getCoordinate().y) >= 9990000.0){
                jtsCoord = new Coordinate(coord.getY(),coord.getX());
                jtsPoint = geomFactory.createPoint(jtsCoord);
                transformedPoint = JTS.transform(jtsPoint, CRS.findMathTransform(source,target));
            }
        } catch (TransformException e) {
            e.printStackTrace();
        } catch (FactoryException e) {
            e.printStackTrace();
        }
        Coord theCoord = new Coord(transformedPoint.getCentroid().getX(),transformedPoint.getCentroid().getY());
        return theCoord;
    }

}
