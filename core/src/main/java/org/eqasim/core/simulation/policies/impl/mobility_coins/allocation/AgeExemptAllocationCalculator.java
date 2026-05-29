package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.population.PersonUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Age-exempt allocation: Young (under minAge) and elderly (over maxAge) are exempt
 * from the MobilityCoin system. They travel as-is without charges or incentives.
 *
 * The coin allocation is calculated as:
 * 1. Take the status quo coins of eligible agents only (baseline * eligible_fraction)
 * 2. Reduce this by the emission reduction target of all agents
 * 3. Allocate the result to eligible agents
 *
 * This ensures eligible agents receive appropriate coins for their emission reduction burden,
 * while exempt agents receive 0 coins and don't pay charges.
 */
public class AgeExemptAllocationCalculator implements AllocationCalculator {
    
    private static final Logger logger = LogManager.getLogger(AgeExemptAllocationCalculator.class);
    
    public static final String MOCO_EXEMPT_ATTRIBUTE = "mocoExempt";
    
    private final int minAge;
    private final int maxAge;
    private final double reductionPercentage;

    /**
     * @param minAge Minimum age for MobilityCoin participation (exclusive, agents below are exempt)
     * @param maxAge Maximum age for MobilityCoin participation (exclusive, agents above are exempt)
     * @param reductionPercentage The reduction percentage used for coin calculation
     */
    public AgeExemptAllocationCalculator(int minAge, int maxAge, double reductionPercentage) {
        this.minAge = minAge;
        this.maxAge = maxAge;
        this.reductionPercentage = reductionPercentage;
    }
    
    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {
        Map<Id<Person>, Double> allocations = new HashMap<>();
        
        int exemptCount = 0;
        int participantCount = 0;
        
        // First pass: count participants and mark exempt agents
        for (Person person : population.getPersons().values()) {
            int age = getAge(person);
            boolean isExempt = age < minAge || age > maxAge;
            
            // Store exemption status as attribute for use in coin calculations
            person.getAttributes().putAttribute(MOCO_EXEMPT_ATTRIBUTE, isExempt);
            
            if (isExempt) {
                exemptCount++;
            } else {
                participantCount++;
            }
        }
        
        logger.info("Age-exempt allocation: {} exempt agents (age < {} or > {}), {} participating agents",
                   exemptCount, minAge, maxAge, participantCount);
        
        // For AGE_EXEMPT: Calculate coins based on eligible agents' baseline emissions
        // Status quo coins of eligible agents only, reduced by emission reduction target of all agents
        double baselineTotalCoins = totalCoins / (1.0 - reductionPercentage);
        double eligibleFraction = (double) participantCount / population.getPersons().size();

        // Status quo coins for eligible agents
        double statusQuoEligibleCoins = baselineTotalCoins * eligibleFraction;

        // Reduce by emission reduction target of all agents
        double reductionAmount = baselineTotalCoins * reductionPercentage;
        double adjustedTotalCoins = Math.max(0, statusQuoEligibleCoins - reductionAmount);

        // Calculate coins per participating agent
        double coinsPerParticipant = participantCount > 0 ? adjustedTotalCoins / participantCount : 0.0;

        // Second pass: allocate coins
        for (Person person : population.getPersons().values()) {
            boolean isExempt = (Boolean) person.getAttributes().getAttribute(MOCO_EXEMPT_ATTRIBUTE);

            if (isExempt) {
                // Exempt agents get no coins and don't participate
                allocations.put(person.getId(), 0.0);
            } else {
                allocations.put(person.getId(), coinsPerParticipant);
            }
        }
        
        logger.info("Age-exempt allocation: status quo eligible coins={}, reduction amount={}, adjusted total={}, coins per participating agent={}",
                   statusQuoEligibleCoins, reductionAmount, adjustedTotalCoins, coinsPerParticipant);
        
        return allocations;
    }
    
    /**
     * Get age of a person from their attributes.
     * @param person The person
     * @return Age in years
     */
    private int getAge(Person person) {
        // Try to get age from MATSim PersonUtils
        Integer age = PersonUtils.getAge(person);
        if (age != null) {
            return age;
        }
        
        // Try to get from attributes directly
        Object ageObj = person.getAttributes().getAttribute("age");
        if (ageObj != null) {
            if (ageObj instanceof Integer) {
                return (Integer) ageObj;
            }
            try {
                return Integer.parseInt(ageObj.toString());
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        
        // Default to working age if unknown
        logger.warn("Unknown age for person {}, assuming working age", person.getId());
        return 30; // Default assumption
    }
    
    /**
     * Check if a person is exempt from the MobilityCoin system.
     * @param person The person to check
     * @return true if exempt, false otherwise
     */
    public static boolean isExempt(Person person) {
        Object exemptObj = person.getAttributes().getAttribute(MOCO_EXEMPT_ATTRIBUTE);
        return exemptObj != null && Boolean.TRUE.equals(exemptObj);
    }
    
    @Override
    public String getDescription() {
        return String.format("Age-exempt allocation: Ages %d-%d participate (status quo coins reduced by %d%% target), others exempt",
                           minAge, maxAge, (int)(reductionPercentage * 100));
    }
}

