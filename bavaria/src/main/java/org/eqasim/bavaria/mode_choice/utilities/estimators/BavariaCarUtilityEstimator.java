package org.eqasim.bavaria.mode_choice.utilities.estimators;

import java.util.List;

import org.eqasim.bavaria.mode_choice.parameters.BavariaModeParameters;
import org.eqasim.bavaria.mode_choice.utilities.predictors.BavariaPersonPredictor;
import org.eqasim.bavaria.mode_choice.utilities.variables.BavariaPersonVariables;
import org.eqasim.core.simulation.mode_choice.utilities.estimators.CarUtilityEstimator;
import org.eqasim.core.simulation.mode_choice.utilities.predictors.CarPredictor;
import org.eqasim.core.simulation.mode_choice.utilities.variables.CarVariables;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

import com.google.inject.Inject;

public class BavariaCarUtilityEstimator extends CarUtilityEstimator {
	private final BavariaModeParameters parameters;
	private final CarPredictor predictor;
	private final BavariaPersonPredictor personPredictor;

	@Inject
	public BavariaCarUtilityEstimator(BavariaModeParameters parameters, CarPredictor predictor, BavariaPersonPredictor personPredictor) {
		super(parameters, predictor);

		this.parameters = parameters;
		this.predictor = predictor;
		this.personPredictor = personPredictor;
	}

	protected double estimateAccessEgressTimeUtility(CarVariables variables) {
		return parameters.betaAccessTime_u_min * variables.accessEgressTime_min;
	}

	protected double estimateHighIncomeUtility(BavariaPersonVariables variables) {
		return variables.isHighIncome ? parameters.bavariaCar.isHighIncome : 0.0;
	}

	protected double estimatePtSubscriptionUtility(BavariaPersonVariables variables) {
		return variables.hasSubscription ? parameters.bavariaCar.hasPtSubscription : 0.0;
	}

	protected double estimateWorkPurposeUtility(DiscreteModeChoiceTrip trip) {
		return trip.getDestinationActivity().getType().equals("work") ? parameters.bavariaCar.isWorkTrip : 0.0;
	}

	protected double estimateShoppingPurposeUtility(DiscreteModeChoiceTrip trip) {
		return trip.getDestinationActivity().getType().equals("shop") ? parameters.bavariaCar.isShoppingTrip : 0.0;
	}


	@Override
	public double estimateUtility(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
		CarVariables variables = predictor.predictVariables(person, trip, elements);
		BavariaPersonVariables personVariables = personPredictor.predictVariables(person, trip, elements);

		double utility = 0.0;
		
		utility += estimateConstantUtility();
		utility += estimateTravelTimeUtility(variables);
		utility += estimateAccessEgressTimeUtility(variables);
		utility += estimateMonetaryCostUtility(variables);
		utility += estimateHighIncomeUtility(personVariables);
		utility += estimatePtSubscriptionUtility(personVariables);
		utility += estimateWorkPurposeUtility(trip);
		utility += estimateShoppingPurposeUtility(trip);

		return utility;
	}
}
