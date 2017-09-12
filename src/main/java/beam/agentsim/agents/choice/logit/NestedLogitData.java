package beam.agentsim.agents.choice.logit;

public class NestedLogitData {
	String nestName;
	Double elasticity = 1.0, expectedMaximumUtility;
	UtilityFunction utility;
	
	public String getNestName() {
		return nestName;
	}

	public void setNestName(String nestName) {
		this.nestName = nestName;
	}
	
	public NestedLogitData(Double elasticity, UtilityFunction utility) {
		this.elasticity = elasticity;
		this.utility = utility;
	}

	public Double getElasticity() {
		return elasticity;
	}

	public void setElasticity(Double elasticity) {
		this.elasticity = elasticity;
	}

	public UtilityFunction getUtility() {
		return utility;
	}

	public void setUtility(UtilityFunction utility) {
		this.utility = utility;
	}

	public NestedLogitData() {
	}
	public String toString(){
		return nestName;
	}
	public Double getExpectedMaxUtility() {
		return expectedMaximumUtility;
	}

	public void setExpectedMaxUtility(Double expectedMaximumUtility) {
		this.expectedMaximumUtility = expectedMaximumUtility;
	}
}
