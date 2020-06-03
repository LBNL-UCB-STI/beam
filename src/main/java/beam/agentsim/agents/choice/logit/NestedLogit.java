package beam.agentsim.agents.choice.logit;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Random;

public class NestedLogit implements AbstractLogit {
    private static final Logger log = LoggerFactory.getLogger(NestedLogit.class);
    public final NestedLogitData data;
    public NestedLogit parent;
    public LinkedList<NestedLogit> children;
    public LinkedList<NestedLogit> ancestorNests;
    private DiscreteProbabilityDistribution cdf;

    public NestedLogit(NestedLogit tree) {
        this.data = new NestedLogitData();
        this.data.setElasticity(tree.data.getElasticity());
        this.data.setNestName(tree.data.getNestName());
        this.data.setUtility(tree.data.getUtility());
        this.parent = tree.parent;
        this.children = tree.children;
        this.ancestorNests = tree.ancestorNests;
    }

    public NestedLogit(NestedLogitData data) {
        this.data = data;
    }

    public static NestedLogit nestedLogitFactory(String nestedLogitTreeAsXML) {
        SAXBuilder saxBuilder = new SAXBuilder();
        InputStream stream = new ByteArrayInputStream(nestedLogitTreeAsXML.getBytes(StandardCharsets.UTF_8));
        Document document;
        try {
            document = saxBuilder.build(stream);
            return NestedLogit.nestedLogitFactory(document.getRootElement());
        } catch (JDOMException | IOException e) {
            log.error("exception occurred due to ", e);
        }
        return null;
    }

    public static NestedLogit nestedLogitFactory(Element rootElem) {
        NestedLogitData theData = new NestedLogitData();
        theData.setNestName(rootElem.getAttributeValue("name"));
        NestedLogit tree = new NestedLogit(theData);
        UtilityFunctionJava utility;
        for (int i = 0; i < rootElem.getChildren().size(); i++) {
            Element elem = (Element) rootElem.getChildren().get(i);
            switch (elem.getName().toLowerCase()) {
                case "elasticity":
                    theData.setElasticity(Double.parseDouble(elem.getValue()));
                    break;
                case "utility":
                    utility = new UtilityFunctionJava();
                    for (int j = 0; j < elem.getChildren().size(); j++) {
                        Element paramElem = (Element) elem.getChildren().get(j);
                        if (paramElem.getName().toLowerCase().equals("param")) {
                            utility.addCoefficient(paramElem.getAttributeValue("name"), Double.parseDouble(paramElem.getValue()), LogitCoefficientType.valueOf(paramElem.getAttributeValue("type")));
                        }
                    }
                    theData.setUtility(utility);
                    if (tree.parent != null) {
                        tree.ancestorNests = new LinkedList<>();
                        establishAncestry(tree, tree.parent);
                    }
                    break;
                case "nestedlogit":
                case "alternative":
                    if (tree.children == null) {
                        tree.children = new LinkedList<>();
                    }
                    NestedLogit child = NestedLogit.nestedLogitFactory(elem);
                    child.parent = tree;
                    tree.children.add(child);
                    break;
            }
        }
        return tree;
    }

    private static void establishAncestry(NestedLogit tree, NestedLogit ancestor) {
        if (ancestor != null) {
            tree.ancestorNests.add(ancestor);
            establishAncestry(tree, ancestor.parent);
        }
    }

    @Override
    public DiscreteProbabilityDistribution evaluateProbabilities(LinkedHashMap<String, LinkedHashMap<String, Double>> inputData) {
        LinkedHashMap<NestedLogit, Double> conditionalProbs = new LinkedHashMap<>();

        getExpOfExpectedMaximumUtility(inputData, conditionalProbs);
        LinkedHashMap<String, Double> marginalProbs = marginalizeAlternativeProbabilities(conditionalProbs);
        cdf = new DiscreteProbabilityDistribution();
        cdf.setPDF(marginalProbs);
        return cdf;
    }

    @Override
    public String makeRandomChoice(LinkedHashMap<String, LinkedHashMap<String, Double>> inputData, Random rand) {
        if (cdf == null) evaluateProbabilities(inputData);
        return cdf.sample(rand);
    }

    @Override
    public Double getUtilityOfAlternative(LinkedHashMap<String, LinkedHashMap<String, Double>> inputData) {
        if (inputData.containsKey(this.getName())) {
            return this.data.getUtility().evaluateFunction(inputData.get(this.getName()));
        } else {
            for (NestedLogit child : this.children) {
                if (inputData.containsKey(child.getName())) {
                    return child.data.getUtility().evaluateFunction(inputData.get(child.getName()));
                }
            }
        }
        return Double.NaN;
    }

    @Override
    public void clear() {
        cdf = null;
    }

    private LinkedHashMap<String, Double> marginalizeAlternativeProbabilities(LinkedHashMap<NestedLogit, Double> conditionalProbs) {
        LinkedHashMap<String, Double> marginalProbs = new LinkedHashMap<>();
        for (NestedLogit node : conditionalProbs.keySet()) {
            if (node.isAlternative()) {
                double marginal = propogateNestProbs(node, conditionalProbs);
                marginalProbs.put(node.data.getNestName(), marginal);
            }
        }
        return marginalProbs;
    }

    private double propogateNestProbs(NestedLogit node, LinkedHashMap<NestedLogit, Double> conditionalProbs) {
        if (node.parent == null) {
            return 1.0; // Top level
        } else {
            return conditionalProbs.get(node) * propogateNestProbs(node.parent, conditionalProbs);
        }
    }

    //FIXME: Side-effecting...
    public double getExpOfExpectedMaximumUtility(LinkedHashMap<String, LinkedHashMap<String, Double>> inputData, LinkedHashMap<NestedLogit, Double> conditionalProbs) {
        if (this.isAlternative()) {
            // Default is -Inf which renders this alternative empty if no input data found
            double utilOfAlternative = Double.NEGATIVE_INFINITY;
            double expOfUtil = 0.0;
            if (inputData.containsKey(this.data.getNestName())) {
                utilOfAlternative = this.data.getUtility().evaluateFunction(inputData.get(this.data.getNestName()));
                // At this point if we see -Inf, set to very negative number but keep probability of this alternative non-zero
                if (utilOfAlternative == Double.NEGATIVE_INFINITY) {
                    utilOfAlternative = -Double.MAX_VALUE;
                }
                expOfUtil = Math.max(Double.MIN_VALUE, Math.exp(utilOfAlternative / this.data.getElasticity()));
            }
            this.data.setExpectedMaxUtility(utilOfAlternative);
            return expOfUtil;
        } else {
            double sumOfExpOfExpMaxUtil = 0.0;
            for (NestedLogit child : this.children) {
                double expOfExpMaxUtil = child.getExpOfExpectedMaximumUtility(inputData, conditionalProbs);
                conditionalProbs.put(child, expOfExpMaxUtil);
                sumOfExpOfExpMaxUtil += expOfExpMaxUtil;
            }
            if (sumOfExpOfExpMaxUtil > 0.0) {
                if (sumOfExpOfExpMaxUtil < Double.POSITIVE_INFINITY) {
                    for (NestedLogit child : this.children) {
                        conditionalProbs.put(child, conditionalProbs.get(child) / sumOfExpOfExpMaxUtil);
                    }
                } else {
                    int numInf = 0;
                    for (NestedLogit child : this.children) {
                        if (conditionalProbs.get(child) == Double.POSITIVE_INFINITY) {
                            numInf++;
                        }
                    }
                    for (NestedLogit child : this.children) {
                        if (conditionalProbs.get(child) == Double.POSITIVE_INFINITY) {
                            conditionalProbs.put(child, 1.0 / numInf);
                        } else {
                            conditionalProbs.put(child, 0.0);
                        }
                    }

                }
            }
            this.data.setExpectedMaxUtility(Math.log(sumOfExpOfExpMaxUtil) * this.data.getElasticity());
            return Math.pow(sumOfExpOfExpMaxUtil, this.data.getElasticity());
        }
    }

    public Double getMarginalProbability(String nestName) {
        if (this.cdf == null) {
            return null;
        } else {
            LinkedHashMap<String, Double> probabilityDensityMap = new LinkedHashMap<>(cdf.getProbabilityDensityMap());
            return sumMarginalProbsOfNest(this, nestName, probabilityDensityMap);
        }
    }

    @Override
    public Double getExpectedMaximumUtility() {
        return this.data.getExpectedMaxUtility();
    }

    public Double getExpectedMaximumUtility(String nestName) {
        if (this.data.getNestName().equals(nestName)) {
            return this.data.getExpectedMaxUtility();
        } else if (!this.isAlternative()) {
            for (NestedLogit child : this.children) {
                Double expMax = child.getExpectedMaximumUtility(nestName);
                if (expMax != null) return expMax;
            }
        }
        return null;
    }

    private Double sumMarginalProbsOfNest(NestedLogit node, String nestName, LinkedHashMap<String, Double> pdf) {
        return sumMarginalProbsOfNest(this, nestName, pdf, false);
    }

    private Double sumMarginalProbsOfNest(NestedLogit node, String nestName, LinkedHashMap<String, Double> pdf, Boolean startSumming) {
        if (!startSumming && node.data.getNestName().equals(nestName)) {
            return sumMarginalProbsOfNest(node, nestName, pdf, true);
        }
        if (node.isAlternative()) {
            return startSumming ? pdf.get(node.data.getNestName()) : 0.0;
        }
        Double sumChildren = 0.0;
        for (NestedLogit child : node.children) {
            sumChildren += sumMarginalProbsOfNest(child, nestName, pdf, startSumming);
        }
        return sumChildren;
    }

    private boolean isAlternative() {
        return this.children == null;
    }

    public String toString() {
        return this.data.getNestName();
    }

    public String toStringRecursive(int depth) {
        StringBuilder result = new StringBuilder();
        StringBuilder tabs = new StringBuilder();
        StringBuilder tabsPlusOne = new StringBuilder("  ");
        for (int i = 0; i < depth; i++) {
            tabs.append("  ");
            tabsPlusOne.append("  ");
        }
        result.append(tabs).append(this.data.getNestName()).append("\n");
        if ((this.children == null || this.children.isEmpty()) && this.data.getUtility() != null) {
            result.append(tabsPlusOne).append(this.data.getUtility().toString()).append("\n");
        } else {
            for (NestedLogit subnest : this.children) {
                result.append(subnest.toStringRecursive(depth + 1));
            }
        }
        return result.toString();
    }

    public String getName() {
        return this.data.getNestName();
    }

    public void setName(String name) {
        this.data.setNestName(name);
    }

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
