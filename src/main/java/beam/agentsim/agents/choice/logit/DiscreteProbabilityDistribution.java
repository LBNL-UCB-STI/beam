package beam.agentsim.agents.choice.logit;

import java.util.LinkedHashMap;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;


public class DiscreteProbabilityDistribution {

	private LinkedHashMap<String,Double> pdf;
	private NavigableMap<Double, String> cdf;

	public DiscreteProbabilityDistribution(){ }
	
	public void addElement(String element, Double probability){
		if(this.pdf==null)this.pdf = new LinkedHashMap<String,Double>();
		this.pdf.put(element,probability);
		this.cdf = null;
	}
	
	public void setPDF(LinkedHashMap<String,Double> pdf){
		this.pdf = pdf;
		this.cdf = null;
	}
	
	private void createCDF() {
		if(this.pdf == null || this.pdf.size()==0){
			throw new RuntimeException("Cannot create a CDF based on a missing or empty PDF");
		}
		// First ensure the distribution is scaled to 1
		double scalingFactor = 0.0;
	    for (Double value : this.pdf.values()){
	    	if(!Double.isNaN(value) && value >1e-6)scalingFactor += value;
	    }
		if(scalingFactor==0.0){
			throw new RuntimeException("Cannot create a CDF based on a PDF without any non-zero probability density");
		}
		this.cdf = new TreeMap<Double, String>();
		
		double cumulProb = 0.0;
		String lastKey = null;
	    for (String key : this.pdf.keySet()){
	    	double theProb = Double.isInfinite(this.pdf.get(key)) ? 1.0 : this.pdf.get(key) / scalingFactor;
	    	if(theProb>1e-6){
				cumulProb += theProb;
				this.cdf.put(cumulProb, key);
				lastKey = key;
	    	}
	    }
		this.cdf.remove(this.cdf.lastKey());
		this.cdf.put(1.0,lastKey);
	}

	public String sample(Random rand){
		if(this.cdf == null)createCDF();
	   return this.cdf.ceilingEntry(rand.nextDouble()).getValue();
	}
	public LinkedHashMap<String,Double> getProbabilityDensityMap(){
		return this.pdf;
	}
}
