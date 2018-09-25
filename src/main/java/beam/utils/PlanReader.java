package beam.utils;

import com.csvreader.CsvReader;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.utils.objectattributes.attributable.Attributes;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class PlanReader {

    public static final String delimiter = ",";
    public static final String path = "D:/ns/work/issue_635/";
    public static final String plansInputFileName = "plan.csv";
    public static final String plansOutputFileName = "plans-output.xml";


    public static void main(String[] args) throws IOException {



        BufferedReader reader = new BufferedReader(new FileReader(path + plansInputFileName));
        String line = "";
        int idx = 0;

//        Plan plan = PopulationUtils.createPlan();
//        PopulationFactory populationFactory = PopulationUtils.getFactory();
//
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());

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

        //PopulationUtils.

        writePlans(population);

    }

    private static void writePlans(Population population) {
        String plansFilename = path + plansOutputFileName;
        new PopulationWriter(population).write(plansFilename);

        System.out.println("Written plans successfully to " + plansFilename);
    }

    public static void printRow(String[] dRow){
        System.out.println("personId => " + dRow[0] +
                ", planElement => " + dRow[1] +
                ", planElementId => " + dRow[2] +
                ", activityType => " + dRow[3] +
                ", x => " + dRow[4] +
                ", y => " + dRow[5] +
                ", endTime => " + dRow[6] +
                ", mode => " + dRow[7]);
    }
}

