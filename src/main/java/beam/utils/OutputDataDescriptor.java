package beam.utils;

import beam.sim.OutputDataDescription;
import org.matsim.core.controler.OutputDirectoryHierarchy;

import java.util.List;

public interface OutputDataDescriptor {
    /**
     * Get description of fields written to the output files.
     * @return list of data description objects
     * @param ioController the IO controller
     */
    List<OutputDataDescription> getOutputDataDescriptions(OutputDirectoryHierarchy ioController);
}
