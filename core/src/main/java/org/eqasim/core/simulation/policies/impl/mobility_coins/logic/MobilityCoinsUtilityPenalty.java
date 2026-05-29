package org.eqasim.core.simulation.policies.impl.mobility_coins.logic;

import java.util.List;

import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsDistances;
import org.eqasim.core.simulation.policies.utility.UtilityPenalty;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;

/**
 * Translates the coin balance change of a candidate trip into a utility penalty that
 * enters the discrete mode-choice model.
 *
 * The idea of the tradable-credit scheme: every trip changes an agent's coin balance
 * ({@code deltaCoins}; negative for car/PT, positive for walking/cycling). Multiplying
 * by the current market price gives a monetary value, which is weighted by behavioral
 * parameters ({@code beta_loss}/{@code beta_gain}) to obtain the perceived (dis)utility.
 */
public class MobilityCoinsUtilityPenalty implements UtilityPenalty {
	private final MobilityCoinsParameters parameters;
	private final MobilityCoinsMarket market;
	private final MobilityCoinsCalculator calculator;

	public MobilityCoinsUtilityPenalty(MobilityCoinsMarket market, MobilityCoinsCalculator calculator,
			MobilityCoinsParameters parameters) {
		this.market = market;
		this.calculator = calculator;
		this.parameters = parameters;
	}

	@Override
	public double calculatePenalty(String mode, Person person, DiscreteModeChoiceTrip trip,
			List<TripCandidate> previousTrips, List<? extends PlanElement> elements) {
		// calculate modal distances
		MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(elements);

		// calculate gains and losses in coins
		double deltaCoins = calculator.calculateCoinDelta(distances);

		// convert coins to EUR value at the current market price
		double marketPrice = market.getMarketPrice_EUR_per_coin();
		double coinValue_EUR = deltaCoins * marketPrice;

		// apply behavioral parameter (utils/EUR) based on gain or loss
		// beta_loss_u_per_coin and beta_gain_u_per_coin are in [utils/EUR]
		double base_utility;
		if (deltaCoins < 0.0) { // losses (e.g., car, pt)
			base_utility = parameters.beta_loss_u_per_coin * coinValue_EUR;
		} else { // gains (e.g., walk, bike)
			base_utility = parameters.beta_gain_u_per_coin * coinValue_EUR;
		}

		// we need to return a penalty (= inverse of added utility)
		return -base_utility;
	}
}
