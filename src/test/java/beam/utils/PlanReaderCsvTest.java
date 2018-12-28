package beam.utils;

import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;

public class PlanReaderCsvTest {

    private static final String path = "test/input/beamville/test-data/";
    private static String delimiter = ",";

    private PlanReaderCsv planReader;

    private Population populationReadFromCsv;
    private Population populationReadFromXml;

    @Before
    public void init() throws IOException {
        planReader = new PlanReaderCsv(delimiter);

        populationReadFromCsv = planReader.readPlansFromCSV(path + "/plans-input.csv");

        planReader.writePlansToXml(populationReadFromCsv, path + "/plans-output.xml");

        populationReadFromXml = this.readPlansFromXml();
    }

    @Test
    public void testPlansLoadedFromCSVAndXMLHaveSamePersonSize() {

        assert(populationReadFromCsv.getPersons().size() == populationReadFromXml.getPersons().size());
    }

    @Test
    public void testPlansLoadedFromCSVAndXMLHaveSamePersonIds() {

        assert(populationReadFromCsv.getPersons().keySet().containsAll(populationReadFromXml.getPersons().keySet())
                && populationReadFromXml.getPersons().keySet().containsAll(populationReadFromCsv.getPersons().keySet()));
    }

    private Population readPlansFromXml(){

        // Read the population from the written file
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenario).readFile(path + PlanReaderCsv.plansOutputFileName);
        return scenario.getPopulation();
    }
}