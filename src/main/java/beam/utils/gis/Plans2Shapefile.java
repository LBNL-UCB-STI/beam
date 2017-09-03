package beam.utils.gis;

import com.google.common.collect.Lists;
import com.vividsolutions.jts.geom.Point;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.StageActivityTypes;
import org.matsim.core.router.StageActivityTypesImpl;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileWriter;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Plans2Shapefile {

    private final String outputDir;
    private final Population population;
    private SimpleFeatureBuilder actBuilder;
    private final CoordinateReferenceSystem crs;
    private final ArrayList<String> filteredActs;

    public Plans2Shapefile(Population population, CoordinateReferenceSystem crs, String outputDir, ArrayList<String> filteredActs) {
        this.outputDir = outputDir;
        this.population = population;
        this.crs = crs;
        this.filteredActs=filteredActs;
        initFeatureType();
    }

    public void write() {
        try {
            writeActs();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Modified method from MATSim source due to unexpected functionality (changed from !stageActivities in 2nd if)
    public static List<Activity> getActivities(
            final List<? extends PlanElement> planElements,
            final StageActivityTypes stageActivities) {
        final List<Activity> activities = new ArrayList<>();

        for (PlanElement pe : planElements) {
            if ( !(pe instanceof Activity) ) continue;
            final Activity act = (Activity) pe;

            if ( stageActivities == null || stageActivities.isStageActivity( act.getType() ) ) {
                activities.add( act );
            }
        }

        // it is not backed to the plan: fail if try to modify
        return Collections.unmodifiableList( activities );
    }


    private void writeActs() throws IOException {
        String outputFile = this.outputDir + "/acts.shp";
        ArrayList<SimpleFeature> fts = new ArrayList<SimpleFeature>();

        for (Plan plan : this.population.getPersons().values().stream().flatMap(p -> p.getPlans().stream()).collect(Collectors.toList())) {
            String id = plan.getPerson().getId().toString();
            Stream<Activity> acts;
            if (this.filteredActs.size()>0) {
                 acts = getActivities(plan.getPlanElements(), new StageActivityTypesImpl(this.filteredActs)).stream();
            } else {
                acts = plan.getPlanElements().stream().filter(pe->pe instanceof Activity).map(pe->(Activity)pe);
            }
            acts.forEach(act->fts.add(getActFeature(id, act)));
        }

        ShapeFileWriter.writeGeometries(fts, outputFile);
    }

    private void initFeatureType() {
        SimpleFeatureTypeBuilder actBuilder = new SimpleFeatureTypeBuilder();
        actBuilder.setName("activity");
        actBuilder.setCRS(this.crs);
        actBuilder.add("the_geom", Point.class);
        actBuilder.add("PERS_ID", String.class);
        actBuilder.add("TYPE", String.class);
        actBuilder.add("START_TIME", Double.class);
        actBuilder.add("END_TIME", Double.class);

        this.actBuilder = new SimpleFeatureBuilder(actBuilder.buildFeatureType());
    }


    private SimpleFeature getActFeature(final String id, final Activity act) {
        String type = act.getType();

        Double startTime = act.getStartTime();
        Double endTime = act.getEndTime();
        Coord c = act.getCoord();

        try {
            return this.actBuilder.buildFeature(null, new Object[]{MGC.coord2Point(c), id, type, startTime, endTime});
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }

        return null;
    }


    public static void main(String[] args) {
        /* input:
		 * [1] matsim plans file you want to convert
		 * [2] CRS of plans
		 * [3] output plans directory (no / at end)
		 * [4] activity types to filter by
		 */

        Config config = ConfigUtils.createConfig();
        config.plans().setInputFile(args[0]);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        ArrayList<String> filteredActs = new ArrayList<>();
        if(args.length>3){
            String[] split = args[3].split(",");
            filteredActs.addAll(Arrays.asList(split));
        }
        Plans2Shapefile selectedPlans2ESRIShape = new Plans2Shapefile(scenario.getPopulation(), MGC.getCRS(args[1]), args[2],filteredActs);
        selectedPlans2ESRIShape.write();
    }
}
