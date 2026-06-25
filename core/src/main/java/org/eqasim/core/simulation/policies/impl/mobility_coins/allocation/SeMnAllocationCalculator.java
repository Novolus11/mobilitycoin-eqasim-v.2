package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Spatial Equity and Mobility Needs (SE_MN) allocation scheme.
 *
 * TODO: Formel und Parameter hier einfügen.
 */
public class SeMnAllocationCalculator implements AllocationCalculator {

    public SeMnAllocationCalculator() {
        // TODO: Konstruktor-Parameter ergänzen sobald Formel feststeht
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {
        Map<Id<Person>, Double> allocations = new HashMap<>();

        // TODO: Spatial Equity and Mobility Needs-Logik implementieren

        return allocations;
    }

    @Override
    public String getDescription() {
        return "Spatial Equity and Mobility Needs (SE_MN): TODO";
    }
}
