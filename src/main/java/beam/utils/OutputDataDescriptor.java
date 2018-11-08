package beam.utils;

import beam.sim.OutputDataDescription;
import java.util.List;

public interface OutputDataDescriptor {
    /**
     * Get description of fields written to the output files.
     * @return list of data description objects
     */
    List<OutputDataDescription> getOutputDataDescriptions();
}
