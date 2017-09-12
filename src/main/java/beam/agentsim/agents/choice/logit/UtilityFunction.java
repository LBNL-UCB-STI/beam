package beam.agentsim.agents.choice.logit;

import java.util.HashMap;

public class UtilityFunction {
	HashMap<String, Double> coefficients = new HashMap<String, Double>();
	HashMap<String, LogitCoefficientType> coefficientTypes = new HashMap<String, LogitCoefficientType>();
	
	public UtilityFunction(){
	}
	public void addCoefficient(String variableName, double coefficient, LogitCoefficientType coefficientType){
		this.coefficients.put(variableName,coefficient);
		this.coefficientTypes.put(variableName, coefficientType);
	}
	public double evaluateFunction(HashMap<String, Double> valueMap){
		double utility = 0.0;
		for(String key : this.coefficients.keySet()){
			switch (this.coefficientTypes.get(key)) {
			case INTERCEPT:
				utility += this.coefficients.get(key);
				break;
			case MULTIPLIER:
				if(valueMap.get(key)==null)throw new RuntimeException("Expecting variable "+key+" but not contained in the input data");
				utility += this.coefficients.get(key) * valueMap.get(key);
				break;
			default:
				break;
			}
		}
		return(utility);
	}
	public Double getCoefficientValue(String variableName){
		return this.coefficients.get(variableName);
	}
	public String toString(){
		String result = "";
		for(String key : this.coefficients.keySet()){
			result += key+":"+this.coefficients.get(key)+", ";
		}
		return result;
	}

}
