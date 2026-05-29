package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Income-based allocation: Coins allocated inversely proportional to income.
 * Low-income agents receive more coins to ensure equity.
 * 
 * The formula uses inverse proportionality with a progressive factor:
 * - weight_i = (1 / income_i)^progressiveFactor
 * - coins_i = totalCoins * weight_i / sum(weight_j) for all j
 * 
 * Higher progressive factor (> 1.0) increases redistribution toward low income.
 * Default progressive factor of 1.0 gives standard inverse proportionality.
 */
public class IncomeBasedAllocationCalculator implements AllocationCalculator {
    
    private static final Logger logger = LogManager.getLogger(IncomeBasedAllocationCalculator.class);
    
    private final double progressiveFactor;
    
    // Income category midpoints in EUR/month (for Bavaria synthetic population)
    // Categories based on actual data: 0-500, 500-1000, 1000-1250, 1250-1500, 1500-2000, 
    // 2000-2500, 2500-3000, 3000-3500, 3500-4000, 4000-5000, 5000+
    private static final Map<String, Double> INCOME_MIDPOINTS = Map.ofEntries(
        Map.entry("0-500", 250.0),
        Map.entry("500-750", 625.0),      // In case this exists
        Map.entry("500-1000", 750.0),     // Actual category in Bavaria data
        Map.entry("750-1000", 875.0),     // In case this exists
        Map.entry("1000-1250", 1125.0),
        Map.entry("1250-1500", 1375.0),
        Map.entry("1500-2000", 1750.0),
        Map.entry("2000-2500", 2250.0),
        Map.entry("2500-3000", 2750.0),
        Map.entry("3000-3500", 3250.0),
        Map.entry("3500-4000", 3750.0),
        Map.entry("4000-5000", 4500.0),
        Map.entry("5000+", 6000.0)        // Assumed midpoint for open-ended category
    );
    
    // Default income for unknown categories
    private static final double DEFAULT_INCOME = 3000.0;
    
    /**
     * @param progressiveFactor Factor for progressive redistribution (1.0 = proportional, >1.0 = more progressive)
     */
    public IncomeBasedAllocationCalculator(double progressiveFactor) {
        this.progressiveFactor = progressiveFactor;
    }
    
    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {
        Map<Id<Person>, Double> allocations = new HashMap<>();
        Map<Id<Person>, Double> weights = new HashMap<>();
        
        double totalWeight = 0.0;
        int unknownIncomeCount = 0;
        
        // Calculate weights for each person (inverse proportional to income)
        for (Person person : population.getPersons().values()) {
            double income = getIncome(person);
            if (income <= 0) {
                income = DEFAULT_INCOME;
                unknownIncomeCount++;
            }
            
            // Weight = (1/income)^progressiveFactor
            // Higher income → lower weight → fewer coins
            double weight = Math.pow(1.0 / income, progressiveFactor);
            weights.put(person.getId(), weight);
            totalWeight += weight;
        }
        
        if (unknownIncomeCount > 0) {
            logger.warn("Used default income for {} persons with unknown income", unknownIncomeCount);
        }
        
        // Allocate coins proportionally to weights
        if (totalWeight > 0) {
            for (Person person : population.getPersons().values()) {
                double weight = weights.get(person.getId());
                double share = weight / totalWeight;
                double coins = totalCoins * share;
                allocations.put(person.getId(), coins);
            }
        } else {
            // Fallback to uniform
            logger.warn("Total weight is zero, falling back to uniform allocation");
            double coinsPerPerson = totalCoins / population.getPersons().size();
            for (Person person : population.getPersons().values()) {
                allocations.put(person.getId(), coinsPerPerson);
            }
        }
        
        // Log allocation statistics
        logAllocationStats(population, allocations);
        
        return allocations;
    }
    
    /**
     * Get income value for a person from their attributes.
     * @param person The person
     * @return Income in EUR/month
     */
    private double getIncome(Person person) {
        // First, try to get householdIncome attribute
        Object incomeObj = person.getAttributes().getAttribute("householdIncome");
        if (incomeObj != null) {
            String incomeCategory = incomeObj.toString();
            Double midpoint = INCOME_MIDPOINTS.get(incomeCategory);
            if (midpoint != null) {
                return midpoint;
            }
            // Try to parse as number
            try {
                return Double.parseDouble(incomeCategory);
            } catch (NumberFormatException e) {
                // Unknown category, will use default
            }
        }
        
        // Try highIncome boolean attribute
        Object highIncomeObj = person.getAttributes().getAttribute("highIncome");
        if (highIncomeObj != null && Boolean.TRUE.equals(highIncomeObj)) {
            return INCOME_MIDPOINTS.get("5000+");
        }
        
        return DEFAULT_INCOME;
    }
    
    private void logAllocationStats(Population population, Map<Id<Person>, Double> allocations) {
        double minCoins = Double.MAX_VALUE;
        double maxCoins = Double.MIN_VALUE;
        double totalCoins = 0.0;
        
        for (Double coins : allocations.values()) {
            minCoins = Math.min(minCoins, coins);
            maxCoins = Math.max(maxCoins, coins);
            totalCoins += coins;
        }
        
        double avgCoins = totalCoins / allocations.size();
        
        logger.info("Income-based allocation (progressiveFactor={}): min={}, max={}, avg={}, total={}", 
                   progressiveFactor, minCoins, maxCoins, avgCoins, totalCoins);
    }
    
    @Override
    public String getDescription() {
        return String.format("Income-based allocation (progressiveFactor=%.2f): Low income receives more coins", progressiveFactor);
    }
}

