package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Uniform allocation: All agents receive the same number of coins.
 */
public class UniformAllocationCalculator implements AllocationCalculator {
    
    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {
        Map<Id<Person>, Double> allocations = new HashMap<>();
        
        int numPersons = population.getPersons().size();
        double coinsPerPerson = numPersons > 0 ? totalCoins / numPersons : 0.0;
        
        for (Person person : population.getPersons().values()) {
            allocations.put(person.getId(), coinsPerPerson);
        }
        
        return allocations;
    }
    
    @Override
    public String getDescription() {
        return "Uniform allocation: All agents receive equal coins";
    }
}

