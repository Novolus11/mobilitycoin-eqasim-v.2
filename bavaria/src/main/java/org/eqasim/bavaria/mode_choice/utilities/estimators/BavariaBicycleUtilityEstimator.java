package org.eqasim.bavaria.mode_choice.utilities.estimators;

import java.util.List;

import org.eqasim.bavaria.mode_choice.parameters.BavariaModeParameters;
import org.eqasim.bavaria.mode_choice.utilities.predictors.BavariaPersonPredictor;
import org.eqasim.bavaria.mode_choice.utilities.variables.BavariaPersonVariables;
import org.eqasim.core.simulation.mode_choice.utilities.estimators.BikeUtilityEstimator;
import org.eqasim.core.simulation.mode_choice.utilities.predictors.BikePredictor;
import org.eqasim.core.simulation.mode_choice.utilities.predictors.PersonPredictor;
import org.eqasim.core.simulation.mode_choice.utilities.variables.CarVariables;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

import com.google.inject.Inject;

public class BavariaBicycleUtilityEstimator extends BikeUtilityEstimator {
	private final BavariaModeParameters parameters;
	private final BavariaPersonPredictor bavariaPersonPredictor;

	@Inject
	public BavariaBicycleUtilityEstimator(BavariaModeParameters parameters, PersonPredictor personPredictor, BavariaPersonPredictor bavariaPersonPredictor,
			BikePredictor predictor) {
		super(parameters, personPredictor, predictor);
		this.parameters = parameters;
	    this.bavariaPersonPredictor = bavariaPersonPredictor;
	}

	protected double estimateAccessEgressTimeUtility(CarVariables variables) {
		return parameters.betaAccessTime_u_min * variables.accessEgressTime_min;
	}

	protected double estimateHighIncomeUtility(BavariaPersonVariables variables) {
		return variables.isHighIncome ? parameters.bavariaBicycle.isHighIncome : 0.0;
	}

	@Override
	public double estimateUtility(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
        BavariaPersonVariables personVariables = bavariaPersonPredictor.predictVariables(person, trip, elements);

		double utility = 0.0;

		utility += super.estimateUtility(person, trip, elements);
		utility += estimateHighIncomeUtility(personVariables);

		return utility;
	}
}
