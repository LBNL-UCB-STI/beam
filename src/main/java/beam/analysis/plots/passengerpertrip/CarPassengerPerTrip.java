package beam.analysis.plots.passengerpertrip;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.plots.GraphUtils;
import com.google.common.base.CaseFormat;
import org.jfree.data.category.CategoryDataset;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class CarPassengerPerTrip implements IGraphPassengerPerTrip{

    int eventCounter = 0;
    int maxHour = 0;
    final Integer maxPassengers = CAR_MAX_PASSENGERS;

    final String graphName;
    private static final String xAxisTitle = "Hour";
    private static final String yAxisTitle = "# trips";

    final Map<Integer, Map<Integer, Integer>> numPassengerToEventFrequencyBin = new HashMap<>();

    public CarPassengerPerTrip(String graphName){
        this.graphName = graphName;
    }

    @Override
    public void collectEvent(PathTraversalEvent event) {
        eventCounter++;

        int h = getEventHour(event.getTime());
        maxHour = maxHour < h ? h : maxHour;

        Integer numPassengers = event.numberOfPassengers();

        Map<Integer, Integer> eventFrequencyBin = numPassengerToEventFrequencyBin.get(numPassengers);
        if(eventFrequencyBin == null) {
            eventFrequencyBin = new HashMap<>();
            eventFrequencyBin.put(h, 1);
        } else {
            Integer frequency = eventFrequencyBin.get(h);
            if(frequency == null) {
                frequency = 1;
            } else {
                frequency = frequency + 1;
            }
            eventFrequencyBin.put(h, frequency);
        }
        numPassengerToEventFrequencyBin.put(numPassengers, eventFrequencyBin);
    }

    @Override
    public void process(IterationEndsEvent event) throws IOException {
        double[][] matrixDataSet = buildMatrixDataSet();
        CategoryDataset dataSet = GraphUtils.createCategoryDataset("Mode ", "", matrixDataSet);
        writeCSV(matrixDataSet, event.getIteration(), event.getServices().getControlerIO());

        draw(dataSet, event.getIteration(), xAxisTitle, yAxisTitle, event.getServices().getControlerIO());
    }

    public double[][] buildMatrixDataSet() {
        double[][] matrixDataSet = new double[maxPassengers + 1][maxHour + 1];

        for (int numberOfPassengers = 0; numberOfPassengers <= maxPassengers; numberOfPassengers++) {
            matrixDataSet[numberOfPassengers] = getEventFrequenciesBinByNumberOfPassengers(numberOfPassengers, maxHour);
        }

        return matrixDataSet;
    }

    @Override
    public String getFileName(String extension) {
        return "passengerPerTrip" + CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, graphName) + "." + extension;
    }

    @Override
    public String getTitle() {
        return "Number of Passengers per Trip [" + graphName.toUpperCase() + "]";
    }

    @Override
    public String getLegendText(int i) {
        return Integer.toString(i);
    }

    private double[] getEventFrequenciesBinByNumberOfPassengers(int numberOfpassengers, int maxHour) {
        Map<Integer, Integer> eventFrequenciesBin = numPassengerToEventFrequencyBin.get(numberOfpassengers);

        double[] data = new double[maxHour + 1];

        if(eventFrequenciesBin != null){

            for(int i = 0; i < maxHour + 1; i++){
                Integer frequency = eventFrequenciesBin.get(i);
                if(frequency == null){
                    data[i] = 0;
                }else{
                    data[i] = frequency;
                }
            }
        }

        return data;
    }

    @Override
    public boolean isValidCase(String graphName, int numPassengers) {
        return numPassengers <= maxPassengers;
    }
}
