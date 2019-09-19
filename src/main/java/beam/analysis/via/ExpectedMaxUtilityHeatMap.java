package beam.analysis.via;

import beam.agentsim.events.ModeChoiceEvent;
import beam.utils.NetworkHelper;
import org.matsim.api.core.v01.events.Event;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.handler.BasicEventHandler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Map;

public class ExpectedMaxUtilityHeatMap implements BasicEventHandler {

    private final String SEPERATOR = ",";
    private final NetworkHelper networkHelper;
    private final OutputDirectoryHierarchy controlerIO;
    private final int writeEventsInterval;
    private CSVWriter csvWriter;
    static final String fileBaseName = "expectedMaxUtilityHeatMap";
    private BufferedWriter bufferedWriter;
    private boolean writeDataInThisIteration = false;

    public ExpectedMaxUtilityHeatMap(EventsManager eventsManager, NetworkHelper networkHelper, OutputDirectoryHierarchy controlerIO, int writeEventsInterval) {
        this.networkHelper = networkHelper;
        this.controlerIO = controlerIO;
        this.writeEventsInterval = writeEventsInterval;
        eventsManager.addHandler(this);
    }

    @Override
    public void handleEvent(Event event) {
        if (writeDataInThisIteration && event instanceof ModeChoiceEvent) {
            ModeChoiceEvent modeChoiceEvent = (ModeChoiceEvent) event;
            int linkId = Integer.parseInt(modeChoiceEvent.location);
            Link link = networkHelper.getLinkUnsafe(linkId);

            if (link != null) { // TODO: fix this, so that location of mode choice event is always initialized
                try {
                    bufferedWriter.append(Double.toString(modeChoiceEvent.getTime()));
                    bufferedWriter.append(SEPERATOR);
                    bufferedWriter.append(Double.toString(link.getCoord().getX()));
                    bufferedWriter.append(SEPERATOR);
                    bufferedWriter.append(Double.toString(link.getCoord().getY()));
                    bufferedWriter.append(SEPERATOR);
                    bufferedWriter.append(modeChoiceEvent.expectedMaxUtility);
                    bufferedWriter.append("\n");
                    csvWriter.flushBuffer();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
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

}
