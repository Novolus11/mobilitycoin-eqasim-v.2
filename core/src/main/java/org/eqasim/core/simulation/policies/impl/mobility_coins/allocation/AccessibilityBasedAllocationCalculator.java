package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Accessibility-based allocation: Coins allocated inversely proportional to accessibility.
 * Agents with low accessibility (e.g., rural areas with poor public transport) receive 
 * more coins because they have fewer alternatives to car travel.
 * 
 * Accessibility is measured using the logsum-based accessibility (Expected Maximum Utility).
 * This can be pre-calculated from the mode choice model.
 * 
 * The formula uses inverse proportionality with a progressive factor:
 * - weight_i = (maxAccessibility - accessibility_i + minOffset)^progressiveFactor
 * - coins_i = totalCoins * weight_i / sum(weight_j) for all j
 * 
 * Note: Since accessibility values can vary widely and may be negative (for logsums),
 * we use a transformation to ensure positive weights.
 */
public class AccessibilityBasedAllocationCalculator implements AllocationCalculator {
    
    private static final Logger logger = LogManager.getLogger(AccessibilityBasedAllocationCalculator.class);
    
    private final double progressiveFactor;
    private final String accessibilityFilePath;
    
    /**
     * @param progressiveFactor Factor for progressive redistribution (1.0 = proportional, >1.0 = more progressive)
     * @param accessibilityFilePath Path to CSV file with pre-calculated accessibility values per person
     */
    public AccessibilityBasedAllocationCalculator(double progressiveFactor, String accessibilityFilePath) {
        this.progressiveFactor = progressiveFactor;
        this.accessibilityFilePath = accessibilityFilePath;
    }
    
    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {
        Map<Id<Person>, Double> allocations = new HashMap<>();
        
        // Read accessibility values if file is provided
        Map<String, Double> accessibilityMap = new HashMap<>();
        if (accessibilityFilePath != null && !accessibilityFilePath.isEmpty()) {
            accessibilityMap = readAccessibilityFile();
        }
        
        // If no accessibility data, try to compute from person attributes or use uniform
        if (accessibilityMap.isEmpty()) {
            logger.warn("No accessibility data available, trying to compute from person attributes");
            accessibilityMap = computeAccessibilityFromAttributes(population);
        }
        
        // If still no data, fallback to uniform
        if (accessibilityMap.isEmpty()) {
            logger.warn("No accessibility data found, falling back to uniform allocation");
            double coinsPerPerson = totalCoins / population.getPersons().size();
            for (Person person : population.getPersons().values()) {
                allocations.put(person.getId(), coinsPerPerson);
            }
            return allocations;
        }
        
        // Find min and max accessibility for normalization
        double minAccessibility = Double.MAX_VALUE;
        double maxAccessibility = Double.MIN_VALUE;
        
        for (Person person : population.getPersons().values()) {
            String personId = person.getId().toString();
            Double accessibility = accessibilityMap.get(personId);
            if (accessibility != null) {
                minAccessibility = Math.min(minAccessibility, accessibility);
                maxAccessibility = Math.max(maxAccessibility, accessibility);
            }
        }
        
        // Calculate weights (inverse of accessibility)
        Map<Id<Person>, Double> weights = new HashMap<>();
        double totalWeight = 0.0;
        int missingAccessibilityCount = 0;
        
        // Use transformation to get positive weights where low accessibility gets high weight
        // weight = (maxAccessibility - accessibility + offset)^progressiveFactor
        double offset = 1.0; // Small offset to avoid zero weights
        double range = maxAccessibility - minAccessibility;
        if (range < 0.001) {
            range = 1.0; // Avoid division by zero
        }
        
        for (Person person : population.getPersons().values()) {
            String personId = person.getId().toString();
            Double accessibility = accessibilityMap.get(personId);
            
            double weight;
            if (accessibility != null) {
                // Transform: high accessibility → low weight, low accessibility → high weight
                double normalizedAccessibility = (accessibility - minAccessibility) / range;
                // Invert: 1 - normalized gives high values for low accessibility
                double invertedValue = (1.0 - normalizedAccessibility) + offset;
                weight = Math.pow(invertedValue, progressiveFactor);
            } else {
                // Missing accessibility: use median weight (0.5 + offset)
                weight = Math.pow(0.5 + offset, progressiveFactor);
                missingAccessibilityCount++;
            }
            
            weights.put(person.getId(), weight);
            totalWeight += weight;
        }
        
        if (missingAccessibilityCount > 0) {
            logger.warn("Used median accessibility for {} persons with missing data", missingAccessibilityCount);
        }
        
        // Allocate coins proportionally to weights
        for (Person person : population.getPersons().values()) {
            double weight = weights.get(person.getId());
            double share = weight / totalWeight;
            double coins = totalCoins * share;
            allocations.put(person.getId(), coins);
        }
        
        // Log statistics
        logAllocationStats(population, allocations);
        
        return allocations;
    }
    
    /**
     * Read accessibility values from a CSV file.
     * Expected format: person_id;accessibility (or person_id;logsum)
     * @return Map from person ID to accessibility value
     */
    private Map<String, Double> readAccessibilityFile() {
        Map<String, Double> accessibility = new HashMap<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(accessibilityFilePath))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                logger.error("Empty accessibility file: {}", accessibilityFilePath);
                return accessibility;
            }
            
            // Find column indices
            String[] headers = headerLine.split(";");
            int personIdIdx = -1;
            int accessibilityIdx = -1;
            
            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].trim().toLowerCase();
                if (h.equals("person_id") || h.equals("person")) {
                    personIdIdx = i;
                }
                if (h.equals("accessibility") || h.equals("logsum") || h.equals("emu")) {
                    accessibilityIdx = i;
                }
            }
            
            if (personIdIdx < 0 || accessibilityIdx < 0) {
                logger.error("Missing required columns in accessibility file. Need person_id and accessibility/logsum/emu");
                return accessibility;
            }
            
            // Read data
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(";");
                if (parts.length <= Math.max(personIdIdx, accessibilityIdx)) {
                    continue;
                }
                
                String personId = parts[personIdIdx].trim();
                try {
                    double value = Double.parseDouble(parts[accessibilityIdx].trim());
                    accessibility.put(personId, value);
                } catch (NumberFormatException e) {
                    // Skip invalid values
                }
            }
            
            logger.info("Read accessibility data for {} persons from {}", accessibility.size(), accessibilityFilePath);
            
        } catch (IOException e) {
            logger.error("Error reading accessibility file: {}", accessibilityFilePath, e);
        }
        
        return accessibility;
    }
    
    /**
     * Compute accessibility from person attributes if available.
     * Looks for 'carAvailability', 'bicycleAvailability', 'hasPtSubscription', 'isMunichResident'
     * and computes a simple accessibility score.
     */
    private Map<String, Double> computeAccessibilityFromAttributes(Population population) {
        Map<String, Double> accessibility = new HashMap<>();
        
        for (Person person : population.getPersons().values()) {
            double score = 0.0;
            
            // Car availability increases accessibility
            String carAvail = (String) person.getAttributes().getAttribute("carAvailability");
            if ("all".equals(carAvail)) {
                score += 3.0;
            } else if ("some".equals(carAvail)) {
                score += 1.5;
            }
            
            // Bicycle availability
            String bikeAvail = (String) person.getAttributes().getAttribute("bicycleAvailability");
            if ("all".equals(bikeAvail)) {
                score += 1.0;
            }
            
            // PT subscription
            Boolean hasPt = (Boolean) person.getAttributes().getAttribute("hasPtSubscription");
            if (Boolean.TRUE.equals(hasPt)) {
                score += 2.0;
            }
            
            // Munich residents have better PT access
            Boolean isMunich = (Boolean) person.getAttributes().getAttribute("isMunichResident");
            if (Boolean.TRUE.equals(isMunich)) {
                score += 1.5;
            }
            
            accessibility.put(person.getId().toString(), score);
        }
        
        return accessibility;
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
        
        logger.info("Accessibility-based allocation (progressiveFactor={}): min={}, max={}, avg={}, total={}", 
                   progressiveFactor, minCoins, maxCoins, avgCoins, totalCoins);
    }
    
    @Override
    public String getDescription() {
        return String.format("Accessibility-based allocation (progressiveFactor=%.2f): Low accessibility receives more coins", progressiveFactor);
    }
}

