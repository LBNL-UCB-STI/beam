package beam.agentsim.agents.choice.logit;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Random;

public class NestedLogit implements AbstractLogit{
	public NestedLogitData data;
	public NestedLogit parent;
	public LinkedList<NestedLogit> children;
	public LinkedList<NestedLogit> ancestorNests;
	private DiscreteProbabilityDistribution cdf;

	public NestedLogit(NestedLogit tree) {
		this.data = new NestedLogitData();
		this.data.setElasticity(tree.data.getElasticity().doubleValue());
		this.data.setNestName(tree.data.nestName);
		this.data.setUtility(tree.data.getUtility());
		this.parent = tree.parent;
		this.children = tree.children;
		this.ancestorNests = tree.ancestorNests;
	}
	public static NestedLogit NestedLogitFactory(String nestedLogitTreeAsXML){
		SAXBuilder saxBuilder = new SAXBuilder();
		InputStream stream = new ByteArrayInputStream(nestedLogitTreeAsXML.getBytes(StandardCharsets.UTF_8));
		Document document;
		try {
			document = saxBuilder.build(stream);
			return NestedLogit.NestedLogitFactory(document.getRootElement());
		} catch (JDOMException | IOException e) {
			e.printStackTrace();
		}
		return null;
	}
	public static NestedLogit NestedLogitFactory(Element rootElem) {
		NestedLogitData theData = new NestedLogitData();
		theData.setNestName(rootElem.getAttributeValue("name"));
		NestedLogit tree = new NestedLogit(theData);
		UtilityFunction utility;
		for(int i=0; i < rootElem.getChildren().size(); i++){
			Element elem = (Element) rootElem.getChildren().get(i);
			if(elem.getName().toLowerCase().equals("elasticity")){
				theData.setElasticity(Double.parseDouble(elem.getValue()));
			}else if(elem.getName().toLowerCase().equals("utility")){
				utility = new UtilityFunction();
				for(int j=0; j < elem.getChildren().size(); j++){
					Element paramElem = (Element)elem.getChildren().get(j);
					if(paramElem.getName().toLowerCase().equals("param")){
						utility.addCoefficient(paramElem.getAttributeValue("name"), Double.parseDouble(paramElem.getValue()), LogitCoefficientType.valueOf(paramElem.getAttributeValue("type")));
					}
				}
				theData.setUtility(utility);
				if(tree.parent!=null){
					tree.ancestorNests = new LinkedList<NestedLogit>();
					establishAncestry(tree,tree.parent);
				}
			}else if(elem.getName().toLowerCase().equals("nestedlogit") || elem.getName().toLowerCase().equals("alternative")){
				if(tree.children == null){
					tree.children = new LinkedList<NestedLogit>();
				}
				NestedLogit child = NestedLogit.NestedLogitFactory(elem);
				child.parent = tree;
				tree.children.add(child);
			}
		}
		return tree;
	}
	private static void establishAncestry(NestedLogit tree, NestedLogit ancestor) {
		if(ancestor!=null){
			tree.ancestorNests.add(ancestor);
			establishAncestry(tree, ancestor.parent);
		}
	}
	public NestedLogit(NestedLogitData data) {
		this.data = data;
	}

	@Override
	public DiscreteProbabilityDistribution evaluateProbabilities(LinkedHashMap<String,LinkedHashMap<String,Double>> inputData){
		LinkedHashMap<NestedLogit,Double> conditionalProbs = new LinkedHashMap<NestedLogit,Double>();
		double totalExpMaxUtil = getExpOfExpectedMaximumUtility(inputData,conditionalProbs);
		LinkedHashMap<String,Double> marginalProbs = marginalizeAlternativeProbabilities(conditionalProbs);
		cdf = new DiscreteProbabilityDistribution();
		cdf.setPDF(marginalProbs);
		return cdf;
	}
	@Override
	public String makeRandomChoice(LinkedHashMap<String,LinkedHashMap<String,Double>> inputData, Random rand){
		if(cdf==null)evaluateProbabilities(inputData);
		return cdf.sample(rand);
	}
	@Override
	public void clear() {
		cdf = null;
	}
	private LinkedHashMap<String, Double> marginalizeAlternativeProbabilities(LinkedHashMap<NestedLogit, Double> conditionalProbs) {
		LinkedHashMap<String,Double> marginalProbs = new LinkedHashMap<String,Double>();
		for(NestedLogit node : conditionalProbs.keySet()){
			if(node.isAlternative()){
				double marginal = propogateNestProbs(node,conditionalProbs);
				marginalProbs.put(node.data.getNestName(),marginal);
			}
		}
		return marginalProbs;
	}
	private double propogateNestProbs(NestedLogit node, LinkedHashMap<NestedLogit,Double> conditionalProbs) {
		if(node.parent==null){
			return 1.0; // Top level
		}else{
			return conditionalProbs.get(node) * propogateNestProbs(node.parent, conditionalProbs);
		}
	}
	public double getExpOfExpectedMaximumUtility(LinkedHashMap<String,LinkedHashMap<String,Double>> inputData, LinkedHashMap<NestedLogit,Double> conditionalProbs){
		if(this.isAlternative()){
			double utilOfAlternative = this.data.getUtility().evaluateFunction(inputData.get(this.data.getNestName()));
			this.data.setExpectedMaxUtility(utilOfAlternative);
			return Math.exp(utilOfAlternative/this.data.getElasticity());
		}else{
			double sumOfExpOfExpMaxUtil = 0.0;
			for(NestedLogit child : this.children){
				double expOfExpMaxUtil = child.getExpOfExpectedMaximumUtility(inputData,conditionalProbs);
				conditionalProbs.put(child, expOfExpMaxUtil);
				sumOfExpOfExpMaxUtil += expOfExpMaxUtil;
			}
			if(sumOfExpOfExpMaxUtil>0.0){
				if(sumOfExpOfExpMaxUtil<Double.POSITIVE_INFINITY){
					for(NestedLogit child : this.children){
						conditionalProbs.put(child,conditionalProbs.get(child) / sumOfExpOfExpMaxUtil);
					}
				}else{
					int numInf = 0;
					for(NestedLogit child : this.children){
						if(conditionalProbs.get(child) == Double.POSITIVE_INFINITY){
							numInf++;
						}
					}
					for(NestedLogit child : this.children){
						if(conditionalProbs.get(child) == Double.POSITIVE_INFINITY){
							conditionalProbs.put(child,1.0 / numInf);
						}else{
							conditionalProbs.put(child,0.0);
						}
					}

				}
			}
			this.data.setExpectedMaxUtility(Math.log(sumOfExpOfExpMaxUtil) * this.data.getElasticity());
			return Math.pow(sumOfExpOfExpMaxUtil,this.data.getElasticity());
		}
	}
	public Double getMarginalProbability(String nestName) {
		if(this.cdf==null){
			return null;
		}else{
			return sumMarginalProbsOfNest(this,nestName,this.cdf.getProbabilityDensityMap());
		}
	}
	@Override
	public Double getExpectedMaximumUtility() {
		return this.data.getExpectedMaxUtility();
	}
	public Double getExpectedMaximumUtility(String nestName) {
		if(this.data.nestName.equals(nestName)){
			return this.data.getExpectedMaxUtility();
		}else if(!this.isAlternative()){
			for(NestedLogit child : this.children){
				Double expMax = child.getExpectedMaximumUtility(nestName);
				if(expMax != null)return expMax;
			}
		}
		return null;
	}
	private Double sumMarginalProbsOfNest(NestedLogit node, String nestName, LinkedHashMap<String,Double> pdf) {
		return sumMarginalProbsOfNest(this,nestName,pdf,false);
	}
	private Double sumMarginalProbsOfNest(NestedLogit node, String nestName, LinkedHashMap<String,Double> pdf, Boolean startSumming) {
		if(!startSumming && node.data.nestName.equals(nestName)){
			return sumMarginalProbsOfNest(node,nestName,pdf,true);
		}
		if(node.isAlternative()){
			return startSumming ? pdf.get(node.data.getNestName()) : 0.0;
		}
		Double sumChildren = 0.0;
		for(NestedLogit child : node.children){
			sumChildren += sumMarginalProbsOfNest(child,nestName,pdf,startSumming);
		}
		return sumChildren;
	}
	private boolean isAlternative() {
		return this.children==null;
	}
	public String toString(){
		return this.data.getNestName();
	}
	public String toStringRecursive(int depth){
		String result = "";
		String tabs = "", tabsPlusOne = "  ";
		for(int i=0; i<depth; i++){
			tabs += "  ";
			tabsPlusOne += "  ";
		}
		result += tabs + this.data.getNestName() + "\n";
		if((this.children==null || this.children.isEmpty()) && this.data.getUtility()!=null){
			result += tabsPlusOne + this.data.getUtility().toString() + "\n";
		}else{
			for (NestedLogit subnest : this.children) {
				result += subnest.toStringRecursive(depth+1);
			}
		}
		return result;
	}
	public void setName(String name) {
		this.data.setNestName(name);
	}
	public String getName() { return this.data.getNestName(); }
	public void addChild(NestedLogit child) {
		this.children.add(child);
	}
	public void removeChild(NestedLogit child) {
		this.children.remove(child);
	}
	public void removeChildren() {
		this.children.clear();
	}
}