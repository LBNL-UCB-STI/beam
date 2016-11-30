package beam.replanning.io;

import org.matsim.utils.objectattributes.attributable.Attributes;

public class ActFromCsv implements PlanElement {
	
	public ActFromCsv(double actEndTime) {
		super();
		this.actEndTime = actEndTime;
	}

	double actEndTime;

}
