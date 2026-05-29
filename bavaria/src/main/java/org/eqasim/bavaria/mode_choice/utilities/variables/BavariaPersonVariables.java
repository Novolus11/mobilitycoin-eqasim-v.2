package org.eqasim.bavaria.mode_choice.utilities.variables;

import org.eqasim.core.simulation.mode_choice.utilities.variables.BaseVariables;

public class BavariaPersonVariables implements BaseVariables {
	public final boolean hasSubscription;
	public final boolean hasDrivingPermit;
	public final boolean isHighIncome;
	public final boolean isMunichResident;

	public BavariaPersonVariables(boolean hasSubscription, boolean hasDrivingPermit, boolean isHighIncome, boolean isMunichResident) {
		this.hasSubscription = hasSubscription;
		this.hasDrivingPermit = hasDrivingPermit;
		this.isHighIncome = isHighIncome;
		this.isMunichResident = isMunichResident;
	}
}
