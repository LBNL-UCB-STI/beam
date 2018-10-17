package beam.utils.gis;

import com.vividsolutions.jts.geom.Coordinate;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.PointFeatureFactory;
import org.matsim.core.utils.gis.PolylineFeatureFactory;
import org.matsim.core.utils.gis.ShapeFileWriter;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.util.ArrayList;
import java.util.Collection;

public class Network2ShpLines {
    //import org.opengis.feature.

    /**
     * System input:
     *  0 - input MATSim xml network file
     *  1 - output links shapefile
     *  2 - output nodes shapefile
     *  3 - epsg code, format example EPSG:26910
     */


        public static void main(String[] args) {
            ////
            // Load parameters
            ////
            String inputNetwork = args[0];
            String outLinksSHP = args[1];
            String outNodesSHP = args[2];
            String epsgCode = args[3];


            Config config = ConfigUtils.createConfig();
            config.network().setInputFile(inputNetwork);
            Scenario scenario = ScenarioUtils.loadScenario(config);
            Network network = scenario.getNetwork();

            CoordinateReferenceSystem crs = MGC.getCRS(epsgCode);    // EPSG Code for Standard UTM Zone 11

            Collection<SimpleFeature> features = new ArrayList<>();
            PolylineFeatureFactory linkFactory = new PolylineFeatureFactory.Builder().
                    setCrs(crs).
                    setName("link").
                    addAttribute("ID", String.class).
                    addAttribute("fromID", String.class).
                    addAttribute("toID", String.class).
                    addAttribute("length", Double.class).
                    addAttribute("type", String.class).
                    addAttribute("capacity", Double.class).
                    addAttribute("freespeed", Double.class).
                    addAttribute("origID", String.class).
                    create();

            for (Link link : network.getLinks().values()) {
                Coordinate fromNodeCoordinate = new Coordinate(link.getFromNode().getCoord().getX(), link.getFromNode().getCoord().getY());
                Coordinate toNodeCoordinate = new Coordinate(link.getToNode().getCoord().getX(), link.getToNode().getCoord().getY());
                Coordinate linkCoordinate = new Coordinate(link.getCoord().getX(), link.getCoord().getY());
                /*LinkImpl tempLinkImpl = new LinkImpl(link.getId(), link.getFromNode(), link.getToNode(), ((LinkImpl)link).getNetwork(), link.getLength(), )
	            		link.getFreespeed(), link.getCapacity(), link.getNumberOfLanes());*/
//            LinkImpl tempLinkImpl = (LinkImpl) link;

//            SimpleFeature ft = linkFactory.createPolyline(new Coordinate[]{fromNodeCoordinate, linkCoordinate, toNodeCoordinate},
//                    new Object[]{link.getId().toString(), link.getFromNode().getId().toString(), link.getToNode().getId().toString(),
//                            link.getLength(), ((LinkImpl) link).getType(), link.getCapacity(), link.getFreespeed(),
//                            ((LinkImpl) link).getOrigId()}, null);

                //TODO - since access to LinkImpl has been restricted to package level, we cannot easily retreive the type (which was normally empty) and OrigID (the original OSM ID, which is very useful)
                SimpleFeature ft = linkFactory.createPolyline(new Coordinate[]{fromNodeCoordinate, linkCoordinate, toNodeCoordinate},
                        new Object[]{link.getId().toString(), link.getFromNode().getId().toString(), link.getToNode().getId().toString(),
                                link.getLength(), link.getCapacity(), link.getFreespeed()}, null);
                features.add(ft);
            }
            ShapeFileWriter.writeGeometries(features, outLinksSHP);

            features = new ArrayList<>();
            PointFeatureFactory nodeFactory = new PointFeatureFactory.Builder().
                    setCrs(crs).
                    setName("nodes").
                    addAttribute("ID", String.class).
                    create();

            for (Node node : network.getNodes().values()) {
                SimpleFeature ft = nodeFactory.createPoint(node.getCoord(), new Object[]{node.getId().toString()}, null);
                features.add(ft);
            }
            ShapeFileWriter.writeGeometries(features, outNodesSHP);
            System.out.println("All done now, hurray!");
        }


}
