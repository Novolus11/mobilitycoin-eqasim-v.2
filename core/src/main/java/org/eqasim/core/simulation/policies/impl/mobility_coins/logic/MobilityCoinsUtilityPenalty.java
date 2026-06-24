package org.eqasim.core.simulation.policies.impl.mobility_coins.logic;

import java.util.List;

import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsDistances;
import org.eqasim.core.simulation.policies.utility.UtilityPenalty;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.core.router.TripStructureUtils;

/**
 * Translates the coin balance change of a candidate trip into a utility penalty that
 * enters the discrete mode-choice model.
 *
 * Two terms are combined:
 *
 * 1) Existing market-price term: beta × deltaCoins × marketPrice
 *    (negative for car/PT, zero or positive for walk/bike)
 *
 * 2) M term: deviation from per-trip budget
 *    M = (initialAllocation / numberOfTrips) + deltaCoins
 *    M > 0: trip is below budget ("good") -> beta_gain reward
 *    M ≤ 0: trip is above budget ("bad")  -> beta_loss penalty
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
		MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(elements);
		double deltaCoins = calculator.calculateCoinDelta(distances);
		double marketPrice = market.getMarketPrice_EUR_per_coin();

		// Term 1: existing market-price signal (absolute coin cost/gain of this trip)
		double coinValue_EUR = deltaCoins * marketPrice;
		double base_utility;
		if (deltaCoins < 0.0) {
			base_utility = parameters.beta_loss_u_per_coin * coinValue_EUR;
		} else {
			base_utility = parameters.beta_gain_u_per_coin * coinValue_EUR;
		}

		// Term 2: M term — deviation from per-trip budget
		// Wallet attribute equals I_initial during replanning (reset at end of each iteration)
		Object walletAttr = person.getAttributes().getAttribute(MobilityCoinsMarket.WALLET_ATTRIBUTE);
		double initialAllocation = (walletAttr != null) ? (Double) walletAttr : 0.0;
		int numberOfTrips = TripStructureUtils.getTrips(person.getSelectedPlan()).size();
		if (numberOfTrips > 0) {
			double M = (initialAllocation / numberOfTrips) + deltaCoins;
			double M_EUR = M * marketPrice;
			if (M > 0.0) {
				base_utility += parameters.beta_gain_M * M_EUR;
			} else {
				base_utility += parameters.beta_loss_M * M_EUR;
			}
		}

		return -base_utility;
	}
}
