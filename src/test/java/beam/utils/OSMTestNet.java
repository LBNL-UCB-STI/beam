package beam.utils;
//
////import org.jdom.Attribute;
//
//import org.jdom2.Attribute;
//import org.jdom2.Document;
//import org.jdom2.Element;
//import org.jdom2.output.Format;
//import org.jdom2.output.XMLOutputter;
//
//import java.io.FileWriter;
//import java.io.IOException;
//import java.util.Arrays;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
///**
// * Created by Andrew A. Campbell on 8/14/17.
// *
// * Builds the simple Manhattan grid OSM test network. All streets consist of separated single direction ways
// * ("oneway"="yes"). Some of the spatial attributes are settable (location, block length).
// */
//
//public class OSMTestNet {
//
//	private double tLLon =  -122.259820;  // top-left lon
//	private double tLLat = 37.874080;  // top-left lat
//	private double inc = 0.001;  // lat lon increments (i.e. block length)
//	private double intxLen = 0.00005;
//	private int nRows = 5;
//	private int nCols = 5;
//	private String wayVal = "trunk";  // the value of the OSM way (e.g. trunk, residential, sidewalk etc.
//
//	private String nLanes = "1";
//	private String maxSpeed = "35 mph";
//	private String oneWay = "yes";
//
//	private Element osm;
//	private Document doc;
//	private Element root;
//
//	private Map<String, Element> nodeMap = new HashMap<>();
//
//	private Integer nodeId = 1;
//	private Integer wayId = 1;
//
//	OSMTestNet(){
//		this.osm = new Element("osm");  // root
//		osm.setAttribute("version", "0.6");
//		osm.setAttribute("generator", "OSMTestNet.java");
//		this.doc = new Document(osm);
//		this.root = this.doc.getRootElement();
//	}
//
//
//	/**
//	 * Builds the test network. Use the setters to overwrite defaults
//	 */
//	public void buildTestNet(){
//		// Add all the nodes first
//		for (int row=0; row<nRows; row++){
//			for(int col=0; col<nCols; col++){
//				// Create the new intersection
//				double lon = this.tLLon + this.inc*col;
//				double lat = this.tLLat + this.inc*row;
//				this.makeAndAddIntersection(lat, lon, row, col);
//
//				// Make the ways
//				Element[] thisIntx = this.getIntersection(row, col);
//				// Add the to/from edges to bottom intersection if it exists
//				if (row>0){
//					Element[] botIntx = this.getIntersection(row-1, col);
//					String name = "col_" + String.valueOf(col);
//					// top to bottom
//					this.doc.getRootElement().addContent(this.makeWay(thisIntx[3], botIntx[0], name));
//					// bottom to top
//					this.doc.getRootElement().addContent(this.makeWay(botIntx[1], thisIntx[2], name));
//				}
//				// Add the to/from edges to the left intersection if it exists
//				if (col>0){
//					Element[] leftIntx = this.getIntersection(row, col-1);
//					String name = "row_" + String.valueOf(row);
//					// left to right
//					this.doc.getRootElement().addContent(this.makeWay(leftIntx[2], thisIntx[3], name));
//					// right to left
//					this.doc.getRootElement().addContent(this.makeWay(thisIntx[0], leftIntx[1], name));
//				}
//
//			}
//		}
//	}
//
//
//	public void writeNetwork(String path) throws IOException {
//		XMLOutputter xmlOutPut = new XMLOutputter();
//		xmlOutPut.setFormat(Format.getPrettyFormat());
//		xmlOutPut.output(this.doc, new FileWriter(path));
//	}
//
//	/**
//	 * Makes an OSM way element including the common set of attributes and member elements (nodes and tags).
//	 * @param otherNode From node
//	 * @param thisNode to node.
//	 * @param name Street name.
//	 * @return
//	 */
//	public Element makeWay(Element otherNode, Element thisNode, String name){
//		// Initialize the node and increment wayId
//		Element newWay = new Element("way");
//		String wayID = String.valueOf(this.wayId);
//		newWay.setAttribute(new Attribute("id", wayID));
//		this.wayId++;
//
//		//Add the default way attributes
//		newWay.setAttribute(new Attribute("user", "wintermute"));
//		newWay.setAttribute(new Attribute("uid", "1"));
//		newWay.setAttribute(new Attribute("timestamp", "2016-12-31T23:59:59.999Z"));
//		newWay.setAttribute(new Attribute("visible", "true"));
//		newWay.setAttribute(new Attribute("version", "1"));
//		newWay.setAttribute(new Attribute("changeset", "1"));
//
//		// Add the ordered node elements
//		Element oNT = new Element("nd");
//		oNT.setAttribute("ref", otherNode.getAttributeValue("id"));
//		newWay.addContent(oNT);
//		Element tNT = new Element("nd");
//		tNT.setAttribute("ref", thisNode.getAttributeValue("id"));
//		newWay.addContent(tNT);
//
//		// Add the other tags
//		Element tag0 = makeTag("highway", this.wayVal);
//		Element tag1= makeTag("lanes", this.nLanes);
//		Element tag2 = makeTag("maxspeed", this.maxSpeed);
//		Element tag3 = makeTag("oneway", this.oneWay);
//		Element tag4 = makeTag("name", name);
//		newWay.addContent(tag0);
//		newWay.addContent(tag1);
//		newWay.addContent(tag2);
//		newWay.addContent(tag3);
//		newWay.addContent(tag4);
//		return newWay;
//	}
//
//	/**
//	 * Makes an OSM node element including the common set of attributes and member elements (nodes and tags).
//	 * @param lat
//	 * @param lon
//	 * @param row
//	 * @param col
//	 * @param lab Label(NW, NE, SE, SW). These are used for looking the node up in this.nodeMap
//	 * @return
//	 */
//	public Element makeNode(double lat, double lon, int row, int col, String lab){
//		// Initialize node, increment nodeID, and add to the lookup map
//		Element node = new Element("node");
//		String thisNodeId = String.valueOf(this.nodeId);
//		this.nodeMap.put(stringKey(row, col, lab), node);
//		this.nodeId++;
//		// Add all the variable node attributes
//		node.setAttribute(new Attribute("id", thisNodeId));
//		node.setAttribute(new Attribute("lat", String.valueOf(lat)));
//		node.setAttribute(new Attribute("lon", String.valueOf(lon)));
//		// Add all the default node attributes
//		node.setAttribute(new Attribute("user", "wintermute"));
//		node.setAttribute(new Attribute("uid", "1"));
//		node.setAttribute(new Attribute("timestamp", "2016-12-31T23:59:59.999Z"));
//		node.setAttribute(new Attribute("visible", "true"));
//		node.setAttribute(new Attribute("version", "1"));
//		node.setAttribute(new Attribute("changeset", "1"));
//		// Add the default tags
//		Element tag = makeTag("highway", "traffic_signals");
//		node.addContent(tag);
//
//		return node;
//	}
//
//	/**
//	 * Creates an intersection of 4 nodes and the short ways that connect them.
//	 * @param lat Centroid of intersection lat
//	 * @param lon Centroid of intersection lon
//	 * @param row
//	 * @param col
//	 */
//	public void makeAndAddIntersection(double lat, double lon, int row, int col){
//		// Make and addfour nodes for the four corners of the intersection
//		Element nodeNW = makeNode(lat+this.intxLen, lon-this.intxLen, row, col, "NW");
//		Element nodeNE = makeNode(lat+this.intxLen, lon+this.intxLen, row, col, "NE");
//		Element nodeSE = makeNode(lat-this.intxLen, lon+this.intxLen, row, col, "SE");
//		Element nodeSW = makeNode(lat-this.intxLen, lon-this.intxLen, row, col, "SW");
//		this.root.addContent(nodeNW);
//		this.root.addContent(nodeNE);
//		this.root.addContent(nodeSE);
//		this.root.addContent(nodeSW);
//
//		// Make and addfour short ways connecting the nodes
//		Element waySE2NE = makeWay(nodeSE, nodeNE, "col_" + String.valueOf(col));
//		Element wayNW2SW = makeWay(nodeNW, nodeSW, "col_" + String.valueOf(col));
//		Element wayNE2NW = makeWay(nodeNE, nodeNW, "row_" + String.valueOf(row));
//		Element waySW2SE = makeWay(nodeSW, nodeSE, "row_" + String.valueOf(row));
//		this.root.addContent(waySE2NE);
//		this.root.addContent(wayNW2SW);
//		this.root.addContent(wayNE2NW);
//		this.root.addContent(waySW2SE);
//	}
//
//	/**
//	 * [NOT USED] Stub of a class that should probably be implemented to clean everything up.
//	 */
//	private class Intersection {
//		int row;
//		int col;
//		String[] nodes = new String[4];
//	}
//
//	/**
//	 * Returns and array of node IDs
//	 * @param row
//	 * @param col
//	 * @return
//	 */
//	//NOTE: I guess it will be cleaner to implement the Intersection subclass and simply return those.
//	private Element[] getIntersection(int row, int col){
//		Element[] nodes = new Element[4];
//		nodes[0] = this.nodeMap.get(stringKey(row, col, "NW"));
//		nodes[1] = this.nodeMap.get(stringKey(row, col, "NE"));
//		nodes[2] = this.nodeMap.get(stringKey(row, col, "SE"));
//		nodes[3] = this.nodeMap.get(stringKey(row, col, "SW"));
//		return nodes;
//	}
//
//	public Element makeTag(String key, String value){
//		Element tag = new Element("tag");
//		tag.setAttribute("k", key);
//		tag.setAttribute("v", value);
//		return tag;
//	}
//
//	/**
//	 * Maps all unique pairs of non-negative integers to unique integer. Used for generating Map keys.
//	 * @param k1
//	 * @param k2
//	 * @return
//	 */
//	public int cantorPairing(int k1, int k2){
//		return (k1+k2)*(k1+k2+1)/2 + k2;
//	}
//
//	/**
//	 * Sets the number of rows and columns in the Manhattan grid
//	 * @param nRows
//	 * @param nCols
//	 */
//	public void setNRowsCols(int nRows, int nCols){
//		this.nRows = nRows;
//		this.nCols = nCols;
//	}
//
//	/**
//	 * Sets the lat / lon for the top-left intersection
//	 * @param lat
//	 * @param lon
//	 */
//	public void setTLLatLon(double lat, double lon){
//		this.tLLon = lon;
//		this.tLLat = lat;
//	}
//
//	/**
//	 * Sets the lat/lon increments (i.e. block length)
//	 * @param inc
//	 */
//	public void setInc(double inc){
//		this.inc = inc;
//	}
//
//	private String stringKey(int row, int col, String corner){
//		List<String> corners = Arrays.asList("NW", "NE", "SE", "SW");
//		if (corner.contains(corner)){
//			return String.valueOf(row) + "_" + String.valueOf(col) + "_" + corner;
//		}
//		else {
//			return null;
//		}
//	}
//
//	//TODO - attributes are currently hard coded. We should read them from input.
//	public static void main(String[] args) throws IOException {
//		String outPath = args[0];
//		OSMTestNet testNet = new OSMTestNet();
//		testNet.setTLLatLon(0,0);
//		testNet.setInc(0.01);
//		testNet.buildTestNet();
//		testNet.writeNetwork(outPath);
//	}
//
//}
