package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.Map;

/**
 * Interface for calculating individual coin allocations for each person.
 */
public interface AllocationCalculator {
    
    /**
     * Calculate the initial coin allocation for each person in the population.
     * 
     * @param population The population to allocate coins to
     * @param totalCoins The total number of coins to distribute
     * @return A map from person ID to their allocated coins
     */
    Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins);
    
    /**
     * Get a description of this allocation scheme for logging purposes.
     * @return Human-readable description of the allocation scheme
     */
    String getDescription();
}

