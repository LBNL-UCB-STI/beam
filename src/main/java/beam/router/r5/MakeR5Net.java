package beam.router.r5;

import com.conveyal.r5.transit.TransportNetwork;

import java.io.File;
import java.io.IOException;

/**
 *
 * Builds the R5 network from an input folder and writes it.
 *
 * Args:
 * 0 - Path to input folder
 * 1 - Path to output file
 *
 * Created by Andrew A. Campbell on 6/14/17.
 */
public class MakeR5Net {

	public static void main(String[] args) throws IOException {
		File inputDir = new File(args[0]);
		TransportNetwork tNet = TransportNetwork.fromDirectory(inputDir, false, false);
		File outNet = new File(args[1]);
		tNet.write(outNet);
	}
}
