package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Horizontal Equity Socioeconomic (HE_SOCIO) allocation scheme.
 *
 * TODO: Formel und Parameter hier einfügen.
 */
public class HeSocioAllocationCalculator implements AllocationCalculator {

    public HeSocioAllocationCalculator() {
        // TODO: Konstruktor-Parameter ergänzen sobald Formel feststeht
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {
        Map<Id<Person>, Double> allocations = new HashMap<>();

        // TODO: Horizontal Equity Socioeconomic-Logik implementieren

        return allocations;
    }

    @Override
    public String getDescription() {
        return "Horizontal Equity Socioeconomic (HE_SOCIO): TODO";
    }
}
