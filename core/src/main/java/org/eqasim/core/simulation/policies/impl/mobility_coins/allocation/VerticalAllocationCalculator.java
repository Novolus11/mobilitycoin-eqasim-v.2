package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Vertical Equity (VERTICAL) allocation scheme.
 *
 * TODO: Formel und Parameter hier einfügen.
 */
public class VerticalAllocationCalculator implements AllocationCalculator {

    public VerticalAllocationCalculator() {
        // TODO: Konstruktor-Parameter ergänzen sobald Formel feststeht
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {
        Map<Id<Person>, Double> allocations = new HashMap<>();

        // TODO: Vertical Equity-Logik implementieren

        return allocations;
    }

    @Override
    public String getDescription() {
        return "Vertical Equity (VERTICAL): TODO";
    }
}
