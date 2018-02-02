package beam.utils;


import beam.utils.scripts.QuadTreeBuilder;
import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.FeatureSource;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.opengis.feature.Feature;
import org.opengis.feature.GeometryAttribute;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class TAZ {

    public static void main(String[] args) {
        File file = new File("Y:\\tmp\\beam\\tl_2011_06_taz10\\tl_2011_06_taz10.shp");

        try {
            Map<String, String> connect = new HashMap<>();
            connect.put("url", file.toURI().toString());

            DataStore dataStore = DataStoreFinder.getDataStore(connect);
            String[] typeNames = dataStore.getTypeNames();
            String typeName = typeNames[0];

            System.out.println("Reading content " + typeName);

            FeatureSource featureSource = dataStore.getFeatureSource(typeName);
            FeatureCollection collection = featureSource.getFeatures();
            FeatureIterator iterator = collection.features();


            try {
                while (iterator.hasNext()) {
                    Feature feature = iterator.next();
                    GeometryAttribute sourceGeometry = feature.getDefaultGeometryProperty();
                    System.out.println(feature.getBounds());

                    // TODO
                    // Quad tree
                    //use QuadTreeBuilder.buildQuadTree()

                    // use object attributes for storing the remaing information
                        // parking type, capacity per type, price per type and time of day.
                    // charger type, capacity per type, charging power per type, price per energy unit, etc.

                    // TNC prices come from computational unit
                        // integrate tnc price into ride hail manager


                    // sf light scenario data, prepare initial things
                        // define format
                    // what data sources to use to prepare data?

                    // include scaling factors for everything related to this.

                }
            } finally {
                iterator.close();
            }

        } catch (Throwable e) {
            e.printStackTrace();
        }
    }
}
