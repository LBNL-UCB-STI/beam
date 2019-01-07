package beam.utils;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;


public class PlanReaderCsv {

    private static final String PATH = "test/input/beamville/test-data/";
    static final String plansOutputFileName = "plans-output.xml";
    private static final String DEFAULT_DELIMITER = ",";
    private static final String PLANS_INPUT_FILE_NAME = "plans-input.csv";

    private static final Logger log = LoggerFactory.getLogger(PlanReaderCsv.class);

    private final String delimiter;

    public static void main(String[] args) throws IOException {
        PlanReaderCsv planReader = new PlanReaderCsv();
        Population population = planReader.readPlansFromCSV(PATH + PLANS_INPUT_FILE_NAME);
        planReader.writePlansToXml(population, PATH + plansOutputFileName);
    }

    public PlanReaderCsv(){
        this(DEFAULT_DELIMITER);
    }

    PlanReaderCsv(String delimiter) {
        this.delimiter = delimiter == null ? DEFAULT_DELIMITER : delimiter;
    }

    public Population readPlansFromCSV(String plansFile) throws IOException{

        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());

        BufferedReader reader = new BufferedReader(new FileReader(plansFile));
        String line;
        int idx = 0;

        while((line = reader.readLine()) != null){

            if(idx == 0) { idx++; continue; }
            String[] dRow = line.split(delimiter, -1);

            String personId = dRow[0];
            String planElement = dRow[1];
            String planElementId = dRow[2];
            String activityType = dRow[3];
            String x = dRow[4];
            String y = dRow[5];
            String endTime = dRow[6];
            String mode = dRow[7];

            Plan plan = null;
            Id<Person> _personId = Id.createPersonId(personId);
            if(!population.getPersons().keySet().contains(_personId)) {

                Person person = population.getFactory().createPerson(_personId);
                plan = population.getFactory().createPlan();
                plan.setPerson(person);
                person.addPlan(plan);
                person.setSelectedPlan(plan);
                population.addPerson(person);
            }else{
                Person person = population.getPersons().get(_personId);
                plan = person.getSelectedPlan();
            }


            if(planElement.equalsIgnoreCase("leg")){
                PopulationUtils.createAndAddLeg(plan, mode);
            }else if(planElement.equalsIgnoreCase("activity")){
                Coord coord = new Coord(Double.parseDouble(x), Double.parseDouble(y));
                Activity act = PopulationUtils.createAndAddActivityFromCoord(plan, activityType, coord);

                if(!endTime.isEmpty())
                    act.setEndTime(Double.parseDouble(endTime));
            }

            idx++;
        }

        return population;
    }

    void writePlansToXml(Population population, String outputFile) {
        new PopulationWriter(population).write(outputFile);

        log.info("Written plans successfully to {}", outputFile);
    }

    public void printRow(String[] dRow){
        log.info("personId => {}, planElement => {} , planElementId => {} , activityType => {} , " +
                        "x => {} , y => {} , endTime => {} , mode => {}", dRow);
    }

}
