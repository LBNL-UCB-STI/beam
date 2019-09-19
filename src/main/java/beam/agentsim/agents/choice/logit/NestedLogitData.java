package beam.agentsim.agents.choice.logit;

public class NestedLogitData {
    String nestName;
    Double elasticity = 1.0, expectedMaximumUtility = Double.NaN;
    UtilityFunctionJava utility;

    public NestedLogitData(Double elasticity, UtilityFunctionJava utility) {
        this.elasticity = elasticity;
        this.utility = utility;
    }

    public NestedLogitData() {
    }

    public String getNestName() {
        return nestName;
    }

    public void setNestName(String nestName) {
        this.nestName = nestName;
    }

    public Double getElasticity() {
        return elasticity;
    }

    public void setElasticity(Double elasticity) {
        this.elasticity = elasticity;
    }

    public UtilityFunctionJava getUtility() {
        return utility;
    }

    public void setUtility(UtilityFunctionJava utility) {
        this.utility = utility;
    }

    public String toString() {
        return nestName;
    }

    public Double getExpectedMaxUtility() {
        return expectedMaximumUtility;
    }

    public void setExpectedMaxUtility(Double expectedMaximumUtility) {
        this.expectedMaximumUtility = expectedMaximumUtility;
    }
}
