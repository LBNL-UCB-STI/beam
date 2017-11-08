package beam.agentsim.agents.choice.logit;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MulitnomialLogit implements AbstractLogit, Cloneable{
	NestedLogit tree = null; // We use the more general NL to represent an MNL and with an assumed single top-level nest

	public MulitnomialLogit(NestedLogit theTree) {
		this.tree = theTree;
	}

	public static MulitnomialLogit MulitnomialLogitFactory(String multinomialLogitTreeAsXML){
		SAXBuilder saxBuilder = new SAXBuilder();
		InputStream stream = new ByteArrayInputStream(multinomialLogitTreeAsXML.getBytes(StandardCharsets.UTF_8));
		Document document;
		try {
			document = saxBuilder.build(stream);
			return MulitnomialLogit.MulitnomialLogitFactory(document.getRootElement());
		} catch (JDOMException | IOException e) {
			e.printStackTrace();
		}
		return null;
	}
	public static MulitnomialLogit MulitnomialLogitFactory(Element rootElem) {
		NestedLogitData theData = new NestedLogitData();
		theData.setNestName(rootElem.getAttributeValue("name") );
		NestedLogit tree = new NestedLogit(theData);
		UtilityFunction utility;
		for(int i=0; i < rootElem.getChildren().size(); i++){
			Element elem = (Element) rootElem.getChildren().get(i);
			if(elem.getName().toLowerCase().equals("elasticity")){
				theData.setElasticity(Double.parseDouble(elem.getValue()));
			}else if(elem.getName().toLowerCase().equals("alternative")){
				if(tree.children == null){
					tree.children = new LinkedList<NestedLogit>();
				}
				NestedLogit child = NestedLogit.NestedLogitFactory(elem);
				child.parent = tree;
				tree.children.add(child);
			}else{
				throw new org.jdom.IllegalDataException("Unexpected xml element type "+elem.getName());
			}
		}
		return new MulitnomialLogit(tree);
	}
	public static MulitnomialLogit MulitnomialLogitFactory(String modelName, LinkedList<String> variables, LinkedList<String> alternatives, LinkedList<Double> values) {
		return MulitnomialLogitFactory(modelName,1.0, variables, alternatives, values);
    }
	public static MulitnomialLogit MulitnomialLogitFactory(String modelName, Double elasticity, LinkedList<String> variables, LinkedList<String> alternatives, LinkedList<Double> values) {
		NestedLogitData theData = new NestedLogitData();
		theData.setNestName(modelName);
		theData.setElasticity(elasticity);
		NestedLogit tree = new NestedLogit(theData);
		NestedLogit child;
		if(variables.size() != alternatives.size() || variables.size() != values.size()){
			throw new RuntimeException("MultinomialLogit model factory expects three lists of equal sizes, but was given unequal lists instead.");
		}
		HashMap<String,NestedLogit> alternativesProcessed = new HashMap<String,NestedLogit>();
		for(int i=0; i < values.size(); i++) {
			String alternative = alternatives.get(i);
			String variable = variables.get(i);
			Double value = values.get(i);
			if (tree.children == null) {
				tree.children = new LinkedList<NestedLogit>();
			}
			if(!alternativesProcessed.containsKey(alternative)) {
				NestedLogitData childData = new NestedLogitData();
				childData.setNestName(alternative);
				childData.setElasticity(1.0);
				childData.setUtility(new UtilityFunction());
                child = new NestedLogit(childData);
                child.parent = tree;
                tree.children.add(child);
				alternativesProcessed.put(alternative,child);
			}else{
				child = alternativesProcessed.get(alternative);
			}
			child.data.utility.addCoefficient(variable, value, variable.equalsIgnoreCase("ASC") ? LogitCoefficientType.INTERCEPT : LogitCoefficientType.MULTIPLIER);
		}
		return new MulitnomialLogit(tree);
	}

	@Override
	public DiscreteProbabilityDistribution evaluateProbabilities(LinkedHashMap<String, LinkedHashMap<String, Double>> inputData) {
		return tree.evaluateProbabilities(inputData);
	}

	@Override
	public String makeRandomChoice(LinkedHashMap<String, LinkedHashMap<String, Double>> inputData, Random rand) {
		return tree.makeRandomChoice(inputData,rand);
	}

	@Override
	public Double getExpectedMaximumUtility() {
		return tree.getExpectedMaximumUtility();
	}

	public LinkedList<String> getAlternativeNames(){
		LinkedList<String> names = new LinkedList<>();
		for(NestedLogit child : tree.children){
			names.add(child.getName());
		}
		return names;
	}

	@Override
	public MulitnomialLogit clone(){
		MulitnomialLogit mnl = new MulitnomialLogit(this.tree);
		mnl.tree = new NestedLogit(this.tree);
		return mnl;
    }
	@Override
	public void clear(){
		tree.clear();
	}
}
