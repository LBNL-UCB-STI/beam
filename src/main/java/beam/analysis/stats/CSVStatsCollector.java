package beam.analysis.stats;

import org.matsim.api.core.v01.events.Event;
import org.matsim.core.events.handler.BasicEventHandler;

import java.awt.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class CSVStatsCollector {

    LinkedList<CSVStats> stats=new LinkedList<CSVStats>();

    public void addStats(CSVStats csvStats){
        stats.add(csvStats);
    }

    public Map<String,Double> getIterationSummaryStats(){
        HashMap<String,Double> result=new HashMap<>();

        for (CSVStats csvStats:stats){
            result.putAll(csvStats.getIterationSummaryStats());
        }

        return result;
    }




}
