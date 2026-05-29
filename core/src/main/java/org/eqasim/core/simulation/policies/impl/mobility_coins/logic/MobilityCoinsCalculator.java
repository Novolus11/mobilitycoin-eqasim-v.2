package org.eqasim.core.simulation.policies.impl.mobility_coins.logic;

import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsDistances;
import org.eqasim.core.simulation.policies.impl.mobility_coins.allocation.AgeExemptAllocationCalculator;
import org.matsim.api.core.v01.population.Person;

/**
 * This class calculates gains and losses based on the per-mode distances.
 */
public class MobilityCoinsCalculator {
    private final MobilityCoinsParameters parameters;

    public MobilityCoinsCalculator(MobilityCoinsParameters parameters) {
        this.parameters = parameters;
    }

    /**
     * Calculate coin delta for a trip.
     * @param distances The distances traveled by different modes
     * @return The coin delta (negative = cost, positive = gain)
     */
    public double calculateCoinDelta(MobilityCoinsDistances distances) {
        double coins = 0.0;

        coins -= parameters.cost_coins_per_gco2 * parameters.emissions_gco2_per_km_car * distances.car_km();
        coins -= parameters.cost_coins_per_gco2 * parameters.emissions_gco2_per_km_car * distances.carPassenger_km();

        coins -= parameters.cost_coins_per_gco2 * parameters.emissions_gco2_per_km_transit * distances.transit_km();

        coins += parameters.incentive_coins_per_km_bicycle * distances.bicycle_km();
        coins += parameters.incentive_coins_per_km_walking * distances.walk_km();

        return coins;
    }
    
    /**
     * Calculate coin delta for a trip, considering whether the person is exempt.
     * Exempt persons (based on AGE_EXEMPT allocation scheme) have zero coin deltas.
     * 
     * @param distances The distances traveled by different modes
     * @param person The person making the trip
     * @return The coin delta (0 if exempt, otherwise normal calculation)
     */
    public double calculateCoinDelta(MobilityCoinsDistances distances, Person person) {
        // Check if person is exempt (e.g., due to age in AGE_EXEMPT scheme)
        if (AgeExemptAllocationCalculator.isExempt(person)) {
            return 0.0; // Exempt persons don't gain or lose coins
        }
        
        return calculateCoinDelta(distances);
    }
    
    /**
     * Check if the MobilityCoin system is active (has non-zero parameters).
     * @return true if the system is active
     */
    public boolean isActive() {
        return parameters.cost_coins_per_gco2 > 0 
            || parameters.incentive_coins_per_km_bicycle > 0 
            || parameters.incentive_coins_per_km_walking > 0;
    }
}
