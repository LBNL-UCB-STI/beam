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

    private Logger log = LoggerFactory.getLogger(PlanReaderCsv.class);

    public String delimiter = ",";
    public String path = "test/input/beamville/test-data/";
    public static final String plansInputFileName = "plans-input.csv";
    public static final String plansOutputFileName = "plans-output.xml";


    public static void main(String[] args) throws IOException {

        PlanReaderCsv planReader = new PlanReaderCsv();
        Population population = planReader.readPlansFromCSV();
        planReader.writePlansToXml(population);
    }

    public PlanReaderCsv(){

        this(null, null);
    }

    public PlanReaderCsv(String path, String delimiter) {

        this.path = path == null ? this.path : path;
        this.delimiter = delimiter == null ? this.delimiter : delimiter;

    }

    public Population readPlansFromCSV() throws IOException{

        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());

        BufferedReader reader = new BufferedReader(new FileReader(path + plansInputFileName));
        String line = "";
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


            printRow(dRow);
            idx++;
        }

        return population;
    }

    public void writePlansToXml(Population population) {
        String plansFilename = path + plansOutputFileName;
        new PopulationWriter(population).write(plansFilename);

        log.info("Written plans successfully to {}", plansFilename);
    }

    public void printRow(String[] dRow){
        log.info("personId => {}, planElement => {} , planElementId => {} , activityType => {} , " +
                        "x => {} , y => {} , endTime => {} , mode => {}",
                dRow);
    }


}

