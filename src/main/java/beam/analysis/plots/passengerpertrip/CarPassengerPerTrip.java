package beam.analysis.plots.passengerpertrip;

import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.plots.GraphUtils;
import com.google.common.base.CaseFormat;
import org.jfree.data.category.CategoryDataset;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class CarPassengerPerTrip implements IGraphPassengerPerTrip{

    int eventCounter = 0;
    int maxHour = 0;
    final Integer maxPassengers = CAR_MAX_PASSENGERS;
    ArrayList<Integer> nonZeroColumns = new ArrayList<Integer>(maxPassengers + 1);

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
        ArrayList<double[]> dataSet = new ArrayList<double[]>(maxPassengers);

        boolean empty = true;
        for (int numberOfPassengers = 0; numberOfPassengers <= maxPassengers; numberOfPassengers++) {
            double[] freq = getEventFrequenciesBinByNumberOfPassengers(numberOfPassengers, maxHour);
            if (freq != null) {
                dataSet.add(freq);
                nonZeroColumns.add(numberOfPassengers);
                empty = false;
            }
        }

        // just to keep at least one column of data in case there are no vehicles
        if (empty) {
            dataSet.add(new double[maxHour + 1]);
            nonZeroColumns.add(0);
        }

        double[][] matrixDataSet = new double[dataSet.size()][];

        return dataSet.toArray(matrixDataSet);
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
        return Integer.toString(nonZeroColumns.get(i));
    }

    private double[] getEventFrequenciesBinByNumberOfPassengers(int numberOfpassengers, int maxHour) {
        Map<Integer, Integer> eventFrequenciesBin = numPassengerToEventFrequencyBin.get(numberOfpassengers);

        double[] data = new double[maxHour + 1];
        boolean nonZero = false;

        if(eventFrequenciesBin != null){

            for(int i = 0; i < maxHour + 1; i++){
                Integer frequency = eventFrequenciesBin.get(i);
                if(frequency == null){
                    data[i] = 0;
                }else{
                    data[i] = frequency;
                    if (frequency != 0) {
                        nonZero = true;
                    }
                }
            }
        }

        return nonZero ? data : null;
    }

    @Override
    public boolean isValidCase(String graphName, int numPassengers) {
        return numPassengers <= maxPassengers;
    }
}
