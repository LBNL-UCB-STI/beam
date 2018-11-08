package beam.analysis.via;

import beam.agentsim.events.ModeChoiceEvent;
import beam.analysis.plots.GraphsStatsAgentSimEventsListener;
import beam.sim.OutputDataDescription;
import beam.utils.OutputDataDescriptor;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.handler.BasicEventHandler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ExpectedMaxUtilityHeatMap implements BasicEventHandler, OutputDataDescriptor {

    private final String SEPERATOR = ",";
    private final Network network;
    private final OutputDirectoryHierarchy controlerIO;
    private final int writeEventsInterval;
    private CSVWriter csvWriter;
    private final String fileBaseName= "expectedMaxUtilityHeatMap";
    private BufferedWriter bufferedWriter;
    private boolean writeDataInThisIteration = false;

    public ExpectedMaxUtilityHeatMap(EventsManager eventsManager, Network network, OutputDirectoryHierarchy controlerIO, int writeEventsInterval) {
        this.network = network;
        this.controlerIO = controlerIO;
        this.writeEventsInterval = writeEventsInterval;
        eventsManager.addHandler(this);
    }


    @Override
    public void handleEvent(Event event) {
        if (writeDataInThisIteration && event instanceof ModeChoiceEvent) {
            ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) event;
            Map<String, String> eventAttributes = modeChoiceEvent.getAttributes();
            Link link = network.getLinks().get(Id.createLinkId(eventAttributes.get(ModeChoiceEvent.ATTRIBUTE_LOCATION)));

            if (link != null) { // TODO: fix this, so that location of mode choice event is always initialized
                try {
                    bufferedWriter.append(Double.toString(modeChoiceEvent.getTime()));
                    bufferedWriter.append(SEPERATOR);
                    bufferedWriter.append(Double.toString(link.getCoord().getX()));
                    bufferedWriter.append(SEPERATOR);
                    bufferedWriter.append(Double.toString(link.getCoord().getY()));
                    bufferedWriter.append(SEPERATOR);
                    bufferedWriter.append(eventAttributes.get(ModeChoiceEvent.ATTRIBUTE_EXP_MAX_UTILITY));
                    bufferedWriter.append("\n");
                    csvWriter.flushBuffer();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void printColumnHeaders() {
        try {
            bufferedWriter.append("time");
            bufferedWriter.append(SEPERATOR);
            bufferedWriter.append("x");
            bufferedWriter.append(SEPERATOR);
            bufferedWriter.append("y");
            bufferedWriter.append(SEPERATOR);
            bufferedWriter.append("expectedMaximumUtility\n");
            csvWriter.flushBuffer();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    @Override
    public void reset(int iteration) {
        if (this.csvWriter != null) {
            this.csvWriter.closeFile();
        }

        writeDataInThisIteration = writeEventsInterval > 0 && iteration % writeEventsInterval == 0;

        if (writeDataInThisIteration) {
            this.csvWriter = new CSVWriter(controlerIO.getIterationFilename(iteration, fileBaseName + ".csv"));
            this.bufferedWriter = this.csvWriter.getBufferedWriter();
            printColumnHeaders();
        }
    }

    /**
     * Get description of fields written to the output files.
     *
     * @return list of data description objects
     */
    @Override
    public List<OutputDataDescription> getOutputDataDescriptions() {
        String outputFilePath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputFilename(fileBaseName + ".csv");
        String outputDirPath = GraphsStatsAgentSimEventsListener.CONTROLLER_IO.getOutputPath();
        String relativePath = outputFilePath.replace(outputDirPath, "");
        List<OutputDataDescription> list = new ArrayList<>();
        list.add(new OutputDataDescription(this.getClass().getSimpleName(), relativePath, "time", "Time of the event occurrence"));
        list.add(new OutputDataDescription(this.getClass().getSimpleName(), relativePath, "x", "X co-ordinate of the network link location"));
        list.add(new OutputDataDescription(this.getClass().getSimpleName(), relativePath, "y", "Y co-ordinate of the network link location"));
        list.add(new OutputDataDescription(this.getClass().getSimpleName(), relativePath, "expectedMaximumUtility", "Expected maximum utility of the network link for the event"));
        return list;
    }
}
