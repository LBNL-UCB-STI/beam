package beam.utils.csv.readers;

import beam.sim.BeamServices;
import beam.utils.BeamVehicleUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.households.*;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleTypeImpl;
import org.matsim.vehicles.VehicleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;


public class PlanReaderCsv {

    private String csvScenarioFile;
    private Logger log = LoggerFactory.getLogger(PlanReaderCsv.class);

    public String delimiter = ",";
    public static final String plansOutputFileName = "plans-output.xml";

    private BeamServices beamServices;
    private Map<Id<Household>, List<Id<Vehicle>>> vehiclesByHouseHoldId;

    private Map<String, Map<String, String>> buildings;
    private Map<String, Map<String, String>> houseHolds;
    private Map<String, Map<String, String>> parcel_attr;
    private Map<String, Map<String, String>> persons;
    private Map<String, Map<String, String>> units;
    private Map<String, List<Map<String, String>>> plans;
    private String defaultAvailableModes = "car,ride_hail,bike,bus,funicular,gondola,cable_car,ferry,tram,transit,rail,subway,tram";


    List<Household> houseHoldsList = new ArrayList<>();

    List<Vehicle> allVehicles = new ArrayList<>();
    List<Person> allPersons = new ArrayList<>();
    int vehicleCounter = 0;
    int personCounter = 0;

    MutableScenario scenario;

    public static void main(String[] args) throws IOException {

        //PlanReaderCsv planReader = new PlanReaderCsv();
        //planReader.readGzipScenario();
    }

    public PlanReaderCsv(MutableScenario scenario, BeamServices beamServices){

        this(scenario, beamServices, null);

    }

    public PlanReaderCsv(MutableScenario scenario, BeamServices beamServices, String delimiter) {

        this.scenario = scenario;
        this.beamServices = beamServices;
        this.delimiter = delimiter == null ? this.delimiter : delimiter;

        this.csvScenarioFile = beamServices.beamConfig().beam().agentsim().agents().population().beamPopulationFile();

        population = scenario.getPopulation();
        population.getPersons().clear();
        population.getPersonAttributes().clear();

        vehiclesByHouseHoldId = BeamVehicleUtils.prePopulateVehiclesByHouseHold(beamServices);

        readGzipScenario();
    }

    public Population getPopulation() {
        return population;
    }

    public Population readPlansFromCSV(String plansFile) throws IOException{

        BufferedReader reader;

        if(plansFile.endsWith(".gz")) {
            GZIPInputStream gzipStream = new GZIPInputStream(new FileInputStream(plansFile));

            reader = new BufferedReader(new InputStreamReader(gzipStream));
        }else {
            reader = new BufferedReader(new FileReader(plansFile));
        }

        return readPlansFromCSV(reader);
    }

    Population population = null;

    public Population readPlansFromCSV(BufferedReader reader) throws IOException{

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



            //printRow(dRow);
            idx++;
        }

        return population;
    }


    public void writePlansToXml(Population population, String outputFile) {
        new PopulationWriter(population).write(outputFile);

        log.info("Written plans successfully to {}", outputFile);
    }

    public void processPlans() {


        for(String planId : plans.keySet()){

            List<Map<String, String>> planDataList = plans.get(planId);


            for(Map<String, String> planData : planDataList) {

                String personId = planData.get("personId");
                String planElement = planData.get("planElement");
                String planElementId = planData.get("planElementId");
                String activityType = planData.get("activityType");
                String x = planData.get("x");
                String y = planData.get("y");
                String endTime = planData.get("endTime");
                String mode = planData.get("mode");

                Id<Person> _personId = Id.createPersonId(personId);
                Person person = population.getPersons().get(_personId);

                if (person == null) {
                    continue;
                }

                Plan plan = person.getSelectedPlan();
                if (plan == null) {

                    plan = PopulationUtils.createPlan(person);
                    person.addPlan(plan);
                    person.setSelectedPlan(plan);
                }

                if (planElement.equalsIgnoreCase("leg")) {
                    PopulationUtils.createAndAddLeg(plan, mode);
                } else if (planElement.equalsIgnoreCase("activity")) {
                    Coord coord = new Coord(Double.parseDouble(x), Double.parseDouble(y));
                    Activity act = PopulationUtils.createAndAddActivityFromCoord(plan, activityType, coord);

                    if (!endTime.isEmpty())
                        act.setEndTime(Double.parseDouble(endTime));
                }
            }
        }
    }


    public void printRow(String[] dRow){
        log.info("personId => {}, planElement => {} , planElementId => {} , activityType => {} , " +
                        "x => {} , y => {} , endTime => {} , mode => {}",
                dRow);
    }


    public void readGzipScenario(){

        CsvToMap csvToMap = new CsvToMap();

        TarArchiveInputStream tarInput = null;
        try {
            tarInput = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(csvScenarioFile)));

            TarArchiveEntry currentEntry = tarInput.getNextTarEntry();
            BufferedReader br = null;
            //StringBuilder sb = new StringBuilder();
            while (currentEntry != null) {
                br = new BufferedReader(new InputStreamReader(tarInput)); // Read directly from tarInput

                System.out.println("For File = " + currentEntry.getName());

                switch (currentEntry.getName()){
                    case "buildings.csv":
                        buildings = csvToMap.read(br);
                        //printMap("buildings", buildings);
                        break;
                    case "households.csv":
                        houseHolds = csvToMap.read(br);
                        //printMap("houseHolds", houseHolds);



                        break;
                    case "parcel_attr.csv":
                        parcel_attr = csvToMap.read(br);
                        //printMap("parcel_attr", parcel_attr);
                        break;
                    case "persons.csv":
                        persons = csvToMap.read(br);
                        //printMap("persons", persons);
                        break;
                    case "plan.csv":
                        //processPlans(br);

                        plans = csvToMap.readListOfMaps(br);
                        break;
                    case "units.csv":
                        units = csvToMap.read(br);
                        //printMap("units", units);
                        break;
                    default:
                        //printLines(br);
                }

                currentEntry = tarInput.getNextTarEntry(); // You forgot to iterate to the next file
            }

            processData();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void printMap(String mapName, Map<String, Map<String, String>> map){

        System.out.println("=> => Map << "+ mapName + ">>");
        for(String id : map.keySet()){
            System.out.println("id -> " + id);
            System.out.println(map.get(id));

        }
    }

    private void printLines(BufferedReader br) throws IOException {
        String line;
        while ((line = br.readLine()) != null) {
            System.out.println("line="+line);
        }
    }

    Map<String, List<Id<Person>>> houseHoldPersons = new HashMap<>();


    private List<Id<Vehicle>> buildVehicles(int numberOfVehicles){

        // We still want to do this but only if no vehicles file is specified

        List<Id<Vehicle>> vehicles = new ArrayList<>();

        for(int i=0; i< numberOfVehicles; i++){

            int vehicleId = vehicleCounter + 1;
            Id<Vehicle> vid = Id.createVehicleId(vehicleId);

            VehicleType vehicleType = new VehicleTypeImpl(Id.create(1, VehicleType.class));

            vehicles.add(vid);
            allVehicles.add(VehicleUtils.getFactory().createVehicle(vid, vehicleType));
        }


        return vehicles;
    }

    private List<Id<Person>> buildPersons(int numberOfPersons){


        List<Id<Person>> persons = new ArrayList<>();

        for(int i=0; i< numberOfPersons; i++){

            int personId = personCounter + 1;
            Id<Person> pid = Id.createPersonId(personId);
            Person person = population.getFactory().createPerson(pid);

            persons.add(pid);
            allPersons.add(person);
        }


        return persons;
    }

    private void processHouseHolds(){

        for(String houseHoldId : houseHolds.keySet()){

            Map<String, String> houseHoldMap = houseHolds.get(houseHoldId);

            String hhId = houseHoldMap.get("household_id");
            Integer numberOfVehicles = Integer.parseInt(houseHoldMap.get("cars"));
            Integer numberOfPersons = Integer.parseInt(houseHoldMap.get("persons"));

            Id<Household> _houseHoldId = Id.create(hhId, Household.class);
            HouseholdImpl objHouseHold = new HouseholdsFactoryImpl().createHousehold(_houseHoldId);

            // Setting the coordinates
            setCoords(objHouseHold, houseHoldMap);

            // If vehicles file not specified then we do the build vehicle other we should somehow be using the
            // v file to assign the vehicles to the household


            //List<Id<Vehicle>> vehicleIds = buildVehicles(numberOfVehicles);
            //List<Id<Person>> personIds = buildPersons(numberOfPersons);

            //objHouseHold.setVehicleIds(vehicleIds);
            //objHouseHold.setMemberIds(personIds);


            objHouseHold.setVehicleIds(vehiclesByHouseHoldId.get(Id.create(hhId, Household.class)));
            objHouseHold.setMemberIds(houseHoldPersons.get(hhId));

            Income income;
            String incomeStr = houseHoldMap.get("income");


            if(incomeStr != null && !incomeStr.isEmpty()) {
                try {

                    Double _income = Double.parseDouble(incomeStr);

                    income = new IncomeImpl(_income, Income.IncomePeriod.year);
                    objHouseHold.setIncome(income);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }


            houseHoldsList.add(objHouseHold);

            // PopulationImpl to set households
            // I have to check in Matsim
            /*
            Population will have
            plans, households, household will have persons and vehicles right
            create default car based on the number in the cars column in the households.csv file


             */

        }


    }

    private void processPersons(){

        for(String personId : persons.keySet()){

            Id<Person> _personId = Id.createPersonId(personId);
            Person person = population.getFactory().createPerson(_personId);

            Map<String, String> personData = persons.get(personId);
            String member_id = personData.get("member_id");
            String age = personData.get("age");
            String primary_commute_mode = personData.get("primary_commute_mode");
            String relate = personData.get("relate");
            String edu = personData.get("edu");
            String sex = personData.get("sex");
            String hours = personData.get("hours");
            String hispanic = personData.get("hispanic");
            String earning = personData.get("earning");
            String race_id = personData.get("race_id");
            String student = personData.get("student");
            String work_at_home = personData.get("work_at_home");
            String worker = personData.get("worker");
            String household_id = personData.get("household_id");
            String node_id_small = personData.get("node_id_small");
            String node_id_walk = personData.get("node_id_walk");
            String job_id = personData.get("job_id");

            //
            /*person.getCustomAttributes().put("member_id", member_id);
            person.getCustomAttributes().put("age", age);
            person.getCustomAttributes().put("primary_commute_mode", primary_commute_mode);
            person.getCustomAttributes().put("relate", relate);
            person.getCustomAttributes().put("edu", edu);
            person.getCustomAttributes().put("sex", sex);
            person.getCustomAttributes().put("hours", hours);
            person.getCustomAttributes().put("hispanic", hispanic);
            person.getCustomAttributes().put("earning", earning);
            person.getCustomAttributes().put("race_id", race_id);
            person.getCustomAttributes().put("student", student);
            person.getCustomAttributes().put("work_at_home", work_at_home);
            person.getCustomAttributes().put("worker", worker);
            person.getCustomAttributes().put("household_id", household_id);
            person.getCustomAttributes().put("node_id_small", node_id_small);
            person.getCustomAttributes().put("node_id_walk", node_id_walk);
            person.getCustomAttributes().put("job_id", job_id);*/

            person.getAttributes().putAttribute("age", Integer.parseInt(age));

            //population.getPersonAttributes().putAttribute(person.getId().toString(), "age", age);
            population.getPersonAttributes().putAttribute(person.getId().toString(), "rank", 0);
            addCarModes(person);


            //person.getCustomAttributes().put("beam-attributes", person.getAttributes());

            population.addPerson(person);


            List<Id<Person>> persons = houseHoldPersons.get(household_id);
            if(persons == null){
                persons = new ArrayList<>();
            }
            persons.add(person.getId());
            houseHoldPersons.put(household_id, persons);
        }
    }



    private void addCarModes(Person person) {

        /*person.getCustomAttributes().put("available-modes", availableModes);
        person.getAttributes().putAttribute("available-modes", availableModes);*/
        population.getPersonAttributes().putAttribute(person.getId().toString(), "available-modes", defaultAvailableModes);

    }

    private void processData(){

        processPersons();

        processPlans();

        processHouseHolds();

        System.out.println("All csv files processed");
    }

    private void setCoords(HouseholdImpl objHouseHold, Map<String, String> houseHoldMap) {

        String x = "";
        String y = "";

        if(houseHoldMap.keySet().contains("homecoordx") && houseHoldMap.keySet().contains("homecoordy")) {
            x = houseHoldMap.get("homecoordx");
            y = houseHoldMap.get("homecoordy");
        }else{
            String houseHoldUnitId = houseHoldMap.get("unit_id");

            if(houseHoldUnitId != null) {
                Map<String, String> unit = units.get(houseHoldUnitId);

                if(unit != null) {
                    String buildingId = unit.get("building_id");

                    if(buildingId != null) {
                        Map<String, String> building = buildings.get(buildingId);

                        if(building != null) {
                            String parcelId = building.get("parcel_id");

                            if(parcelId != null) {
                                Map<String, String> parcel = parcel_attr.get(parcelId);

                                if(parcel != null) {
                                    x = parcel.get("x");
                                    y = parcel.get("y");
                                }
                            }
                        }
                    }
                }
            }
        }

        if(x != null && !x.isEmpty()) {
            objHouseHold.getAttributes().putAttribute("homecoordx", x);
        }

        if(y != null && !y.isEmpty()) {
            objHouseHold.getAttributes().putAttribute("homecoordy", y);
        }
    }


    public List<Person> getAllPersons() {
        return allPersons;
    }

    public List<Vehicle> getAllVehicles() {
        return allVehicles;
    }

    public List<Household> getHouseHoldsList() {
        return houseHoldsList;
    }

}

