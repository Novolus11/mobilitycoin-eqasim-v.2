package org.eqasim.bavaria.mode_choice.utilities.estimators;

import java.util.List;

import org.eqasim.bavaria.mode_choice.parameters.BavariaModeParameters;
import org.eqasim.bavaria.mode_choice.utilities.predictors.BavariaPersonPredictor;
import org.eqasim.bavaria.mode_choice.utilities.variables.BavariaPersonVariables;
import org.eqasim.core.simulation.mode_choice.utilities.estimators.WalkUtilityEstimator;
import org.eqasim.core.simulation.mode_choice.utilities.predictors.WalkPredictor;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

import com.google.inject.Inject;

public class BavariaWalkUtilityEstimator extends WalkUtilityEstimator {
	private final BavariaModeParameters parameters;
	private final BavariaPersonPredictor personPredictor;

	@Inject
	public BavariaWalkUtilityEstimator(BavariaModeParameters parameters, BavariaPersonPredictor personPredictor,
			WalkPredictor predictor) {
		super(parameters, predictor);
		this.parameters = parameters;
		this.personPredictor = personPredictor;
	}

	protected double estimateHighIncomeUtility(BavariaPersonVariables variables) {
		return variables.isHighIncome ? parameters.bavariaWalk.isHighIncome : 0.0;
	}
	
	protected double estimateDrivingPermitUtility(BavariaPersonVariables variables) {
		return variables.hasDrivingPermit ? parameters.bavariaWalk.hasDrivingPermit : 0.0;
	}

	protected double estimatePtSubscriptionUtility(BavariaPersonVariables variables) {
		return variables.hasSubscription ? parameters.bavariaWalk.hasPtSubscription : 0.0;
	}

	protected double estimateMunichResidentUtility(BavariaPersonVariables variables) {
		return variables.isMunichResident ? parameters.bavariaWalk.isMunichResident : 0.0;
	}

	@Override
	public double estimateUtility(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
		BavariaPersonVariables personVariables = personPredictor.predictVariables(person, trip, elements);

		double utility = 0.0;

		utility += super.estimateUtility(person, trip, elements);
		utility += estimateHighIncomeUtility(personVariables);
		utility += estimateDrivingPermitUtility(personVariables);
		utility += estimatePtSubscriptionUtility(personVariables);
		utility += estimateMunichResidentUtility(personVariables);

		return utility;
	}
}




