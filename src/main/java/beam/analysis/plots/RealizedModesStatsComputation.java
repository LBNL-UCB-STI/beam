package beam.analysis.plots;

import org.matsim.core.utils.collections.Tuple;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class RealizedModesStatsComputation
        implements StatsComputation<Tuple<Map<Integer, Map<String, Double>>, Set<String>>, double[][]> {

    @Override
    public double[][] compute(Tuple<Map<Integer, Map<String, Double>>, Set<String>> stat) {
        List<Integer> hoursList = GraphsStatsAgentSimEventsListener.getSortedIntegerList(stat.getFirst().keySet());
        List<String> modesChosenList = GraphsStatsAgentSimEventsListener.getSortedStringList(stat.getSecond());
        if (0 == hoursList.size())
            return null;
        int maxHour = hoursList.get(hoursList.size() - 1);
        double[][] dataset = new double[stat.getSecond().size()][maxHour + 1];
        for (int i = 0; i < modesChosenList.size(); i++) {
            String modeChosen = modesChosenList.get(i);
            dataset[i] = getHoursDataPerOccurrenceAgainstMode(modeChosen, maxHour, stat.getFirst());
        }
        return dataset;
    }

    private double[] getHoursDataPerOccurrenceAgainstMode(String modeChosen, int maxHour, Map<Integer, Map<String, Double>> stat) {
        double[] modeOccurrencePerHour = new double[maxHour + 1];
        for (int hour = 0; hour <= maxHour; hour++) {
            Map<String, Double> hourData = stat.get(hour);
            if (hourData != null) {
                modeOccurrencePerHour[hour] = hourData.get(modeChosen) == null ? 0 : hourData.get(modeChosen);
            } else {
                modeOccurrencePerHour[hour] = 0;
            }
        }
        return modeOccurrencePerHour;
    }

}
