package beam.playground.metasim.viz;

// GraphViz.java - a simple API to call dot from Java programs

/*$Id$*/
/*
 ******************************************************************************
 *                                                                            *
 *                    (c) Copyright Laszlo Szathmary                          *
 *                                                                            *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms of the GNU Lesser General Public License as published by   *
 * the Free Software Foundation; either version 2.1 of the License, or        *
 * (at your option) any later version.                                        *
 *                                                                            *
 * This program is distributed in the hope that it will be useful, but        *
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY *
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public    *
 * License for more details.                                                  *
 *                                                                            *
 * You should have received a copy of the GNU Lesser General Public License   *
 * along with this program; if not, write to the Free Software Foundation,    *
 * Inc., 675 Mass Ave, Cambridge, MA 02139, USA.                              *
 *                                                                            *
 ******************************************************************************
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.Properties;

import org.matsim.core.controler.MatsimServices;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import beam.playground.metasim.services.BeamServices;

/**
 * <dl>
 * <dt>Purpose: GraphViz Java API
 * <dd>
 *
 * <dt>Description:
 * <dd> With this Java class you can simply call dot
 *      from your Java programs.
 * <dt>Example usage:
 * <dd>
 * <pre>
 *    GraphViz gv = new GraphViz();
 *    gv.addln(gv.start_graph());
 *    gv.addln("A -> B;");
 *    gv.addln("A -> C;");
 *    gv.addln(gv.end_graph());
 *    System.out.println(gv.getDotSource());
 *
 *    String type = "gif";
 *    File out = new File("out." + type);   // out.gif in this example
 *    gv.writeGraphToFile( gv.getGraph( gv.getDotSource(), type ), out );
 * </pre>
 * </dd>
 *
 * </dl>
 *
 * @version v0.5.1, 2013/03/18 (March) -- Patch of Juan Hoyos (Mac support)
 * @version v0.5, 2012/04/24 (April) -- Patch of Abdur Rahman (OS detection + start subgraph + read config file)
 * @version v0.4, 2011/02/05 (February) -- Patch of Keheliya Gallaba is added. Now you
 * can specify the type of the output file: gif, dot, fig, pdf, ps, svg, png, etc.
 * @version v0.3, 2010/11/29 (November) -- Windows support + ability to read the graph from a text file
 * @version v0.2, 2010/07/22 (July) -- bug fix
 * @version v0.1, 2003/12/04 (December) -- first release
 * @author  Laszlo Szathmary (<a href="jabba.laci@gmail.com">jabba.laci@gmail.com</a>)
 */
public class GraphVizExporter {
	/**
	 * Detects the client's operating system.
	 */
	private final static String osName = System.getProperty("os.name").replaceAll("\\s","");

	/**
	 * Load the config.properties file.
	 */
	private static Properties configFile; 

	/**
	 * The dir. where temporary files will be created.
	 */
	private static String TEMP_DIR = "";

	/**
	 * Where is your dot program located? It will be called externally.
	 */
	private static String DOT;

	/**
	 * The image size in dpi. 96 dpi is normal size. Higher values are 10% higher each.
	 * Lower values 10% lower each.
	 * 
	 * dpi patch by Peter Mueller
	 */
	private int[] dpiSizes = {46, 51, 57, 63, 70, 78, 86, 96, 106, 116, 128, 141, 155, 170, 187, 206, 226, 249};

	/**
	 * Define the index in the image size array.
	 */
	private int currentDpiPos = 7;

	/**
	 * Constructor: creates a new GraphViz object that will contain
	 * a graph.
	 */
	public GraphVizExporter(String dotConfigFile) {
		GraphVizExporter.configFile = new Properties() {
			private final static long serialVersionUID = 1L; {
				try {
					load(new FileInputStream(dotConfigFile));
				} catch (Exception e) {}
			}
		}; 
		GraphVizExporter.DOT = configFile.getProperty("dotFor" + osName);
		GraphVizExporter.TEMP_DIR = configFile.getProperty("tempDirFor" + osName);
	}
	
	/**
	 * Increase the image size (dpi).
	 */
	public void increaseDpi() {
		if ( this.currentDpiPos < (this.dpiSizes.length - 1) ) {
			++this.currentDpiPos;
		}
	}

	/**
	 * Decrease the image size (dpi).
	 */
	public void decreaseDpi() {
		if (this.currentDpiPos > 0) {
			--this.currentDpiPos;
		}
	}

	public int getImageDpi() {
		return this.dpiSizes[this.currentDpiPos];
	}

	/**
	 * The source of the graph written in dot language.
	 */
	private StringBuilder graph = new StringBuilder();


	/**
	 * Returns the graph's source description in dot language.
	 * @return Source of the graph in dot language.
	 */
	public String getDotSource() {
		return this.graph.toString();
	}

	/**
	 * Adds a string to the graph's source (without newline).
	 */
	public void add(String line) {
		this.graph.append(line);
	}

	/**
	 * Adds a string to the graph's source (with newline).
	 */
	public void addln(String line) {
		this.graph.append(line + "\n");
	}

	/**
	 * Adds a newline to the graph's source.
	 */
	public void addln() {
		this.graph.append('\n');
	}

	public void clearGraph(){
		this.graph = new StringBuilder();
	}

	/**
	 * Returns the graph as an image in binary format.
	 * @param dot_source Source of the graph to be drawn.
	 * @param type Type of the output image to be produced, e.g.: gif, dot, fig, pdf, ps, svg, png.
	 * @return A byte array containing the image of the graph.
	 */
	public byte[] getGraph(String dot_source, String type)
	{
		File dot;
		byte[] img_stream = null;

		try {
			dot = writeDotSourceToFile(dot_source);
			if (dot != null)
			{
				img_stream = get_img_stream(dot, type);
				if (dot.delete() == false) 
					System.err.println("Warning: " + dot.getAbsolutePath() + " could not be deleted!");
				return img_stream;
			}
			return null;
		} catch (java.io.IOException ioe) { return null; }
	}
	public byte[] getGraphFromFile(File dot, String type)
	{
		byte[] img_stream = null;

		if (dot != null)
		{
			img_stream = get_img_stream(dot, type);
			return img_stream;
		}
		return null;
	}

	/**
	 * Writes the graph's image in a file.
	 * @param img   A byte array containing the image of the graph.
	 * @param file  Name of the file to where we want to write.
	 * @return Success: 1, Failure: -1
	 */
	public int writeGraphToFile(byte[] img, String file)
	{
		File to = new File(file);
		return writeGraphToFile(img, to);
	}

	/**
	 * Writes the graph's image in a file.
	 * @param img   A byte array containing the image of the graph.
	 * @param to    A File object to where we want to write.
	 * @return Success: 1, Failure: -1
	 */
	public int writeGraphToFile(byte[] img, File to)
	{
		try {
			FileOutputStream fos = new FileOutputStream(to);
			fos.write(img);
			fos.close();
		} catch (java.io.IOException ioe) { return -1; }
		return 1;
	}

	/**
	 * It will call the external dot program, and return the image in
	 * binary format.
	 * @param dot Source of the graph (in dot language).
	 * @param type Type of the output image to be produced, e.g.: gif, dot, fig, pdf, ps, svg, png.
	 * @return The image of the graph in .gif format.
	 */
	private byte[] get_img_stream(File dot, String type)
	{
		File img;
		byte[] img_stream = null;

		try {
			img = File.createTempFile("graph_", "."+type, new File(GraphVizExporter.TEMP_DIR));
			Runtime rt = Runtime.getRuntime();

			// patch by Mike Chenault
			String[] args = {DOT, "-T"+type, "-Gdpi="+dpiSizes[this.currentDpiPos], dot.getAbsolutePath(), "-o", img.getAbsolutePath()};
			Process p = rt.exec(args);

			p.waitFor();

			FileInputStream in = new FileInputStream(img.getAbsolutePath());
			img_stream = new byte[in.available()];
			in.read(img_stream);
			// Close it if we need to
			if( in != null ) in.close();

			if (img.delete() == false) 
				System.err.println("Warning: " + img.getAbsolutePath() + " could not be deleted!");
		}
		catch (java.io.IOException ioe) {
			System.err.println("Error:    in I/O processing of tempfile in dir " + GraphVizExporter.TEMP_DIR+"\n");
			System.err.println("       or in calling external command");
			ioe.printStackTrace();
		}
		catch (java.lang.InterruptedException ie) {
			System.err.println("Error: the execution of the external program was interrupted");
			ie.printStackTrace();
		}

		return img_stream;
	}

	/**
	 * Writes the source of the graph in a file, and returns the written file
	 * as a File object.
	 * @param str Source of the graph (in dot language).
	 * @return The file (as a File object) that contains the source of the graph.
	 */
	private File writeDotSourceToFile(String str) throws java.io.IOException
	{
		File temp;
		try {
			temp = File.createTempFile("dorrr",".dot", new File(GraphVizExporter.TEMP_DIR));
			FileWriter fout = new FileWriter(temp);
			fout.write(str);
			BufferedWriter br=new BufferedWriter(new FileWriter("dotsource.dot"));
			br.write(str);
			br.flush();
			br.close();
			fout.close();
		}
		catch (Exception e) {
			System.err.println("Error: I/O error while writing the dot source to temp file!");
			return null;
		}
		return temp;
	}

	/**
	 * Returns a string that is used to start a graph.
	 * @return A string to open a graph.
	 */
	public String start_graph() {
		return "digraph G {";
	}

	/**
	 * Returns a string that is used to end a graph.
	 * @return A string to close a graph.
	 */
	public String end_graph() {
		return "}";
	}

	/**
	 * Takes the cluster or subgraph id as input parameter and returns a string
	 * that is used to start a subgraph.
	 * @return A string to open a subgraph.
	 */
	public String start_subgraph(int clusterid) {
		return "subgraph cluster_" + clusterid + " {";
	}

	/**
	 * Returns a string that is used to end a graph.
	 * @return A string to close a graph.
	 */
	public String end_subgraph() {
		return "}";
	}

	/**
	 * Read a DOT graph from a text file.
	 * 
	 * @param input Input text file containing the DOT graph
	 * source.
	 */
	public void readSource(String input)
	{
		StringBuilder sb = new StringBuilder();

		try
		{
			FileInputStream fis = new FileInputStream(input);
			DataInputStream dis = new DataInputStream(fis);
			BufferedReader br = new BufferedReader(new InputStreamReader(dis));
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			dis.close();
		} 
		catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
		}

		this.graph = sb;
	}

} // end of class GraphVizExporter