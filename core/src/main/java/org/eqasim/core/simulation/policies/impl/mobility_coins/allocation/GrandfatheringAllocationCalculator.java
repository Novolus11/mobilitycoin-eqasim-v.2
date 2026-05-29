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
 * Grandfathering allocation: Coins allocated based on historical (baseline) emissions.
 * Agents who emitted more in the baseline get more coins (proportional to their emissions).
 * This rewards status quo behavior and gives high emitters more budget to maintain their lifestyle.
 */
public class GrandfatheringAllocationCalculator implements AllocationCalculator {
    
    private static final Logger logger = LogManager.getLogger(GrandfatheringAllocationCalculator.class);
    
    private final String baselineLegsPath;
    private final double emissionsGco2PerKmCar;
    private final double emissionsGco2PerKmTransit;
    
    /**
     * @param baselineLegsPath Path to the baseline eqasim_legs.csv file
     * @param emissionsGco2PerKmCar Emissions per km for car
     * @param emissionsGco2PerKmTransit Emissions per km for transit
     */
    public GrandfatheringAllocationCalculator(String baselineLegsPath, 
                                              double emissionsGco2PerKmCar, 
                                              double emissionsGco2PerKmTransit) {
        this.baselineLegsPath = baselineLegsPath;
        this.emissionsGco2PerKmCar = emissionsGco2PerKmCar;
        this.emissionsGco2PerKmTransit = emissionsGco2PerKmTransit;
    }
    
    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {
        Map<Id<Person>, Double> allocations = new HashMap<>();
        
        // Read baseline emissions per person
        Map<String, Double> baselineEmissions = readBaselineEmissions();
        
        // Calculate total baseline emissions for persons in current population
        double totalBaselineEmissions = 0.0;
        for (Person person : population.getPersons().values()) {
            String personId = person.getId().toString();
            double emissions = baselineEmissions.getOrDefault(personId, 0.0);
            totalBaselineEmissions += emissions;
        }
        
        logger.info("Grandfathering allocation: Total baseline emissions = {} gCO2", totalBaselineEmissions);
        
        // Allocate coins proportionally to baseline emissions
        if (totalBaselineEmissions > 0) {
            for (Person person : population.getPersons().values()) {
                String personId = person.getId().toString();
                double emissions = baselineEmissions.getOrDefault(personId, 0.0);
                double share = emissions / totalBaselineEmissions;
                double coins = totalCoins * share;
                allocations.put(person.getId(), coins);
            }
        } else {
            // Fallback to uniform if no baseline emissions found
            logger.warn("No baseline emissions found, falling back to uniform allocation");
            double coinsPerPerson = totalCoins / population.getPersons().size();
            for (Person person : population.getPersons().values()) {
                allocations.put(person.getId(), coinsPerPerson);
            }
        }
        
        return allocations;
    }
    
    /**
     * Read baseline emissions per person from the legs CSV file.
     * @return Map from person_id to total emissions in gCO2
     */
    private Map<String, Double> readBaselineEmissions() {
        Map<String, Double> emissions = new HashMap<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(baselineLegsPath))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                logger.error("Empty baseline legs file: {}", baselineLegsPath);
                return emissions;
            }
            
            // Parse header to find column indices
            String[] headers = headerLine.split(";");
            int personIdIdx = -1;
            int vehicleDistanceIdx = -1;
            int modeIdx = -1;
            
            for (int i = 0; i < headers.length; i++) {
                switch (headers[i].trim()) {
                    case "person_id":
                        personIdIdx = i;
                        break;
                    case "vehicle_distance":
                        vehicleDistanceIdx = i;
                        break;
                    case "mode":
                        modeIdx = i;
                        break;
                }
            }
            
            if (personIdIdx < 0 || vehicleDistanceIdx < 0 || modeIdx < 0) {
                logger.error("Missing required columns in baseline legs file. Found: person_id={}, vehicle_distance={}, mode={}", 
                           personIdIdx, vehicleDistanceIdx, modeIdx);
                return emissions;
            }
            
            // Read data
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(";");
                if (parts.length <= Math.max(Math.max(personIdIdx, vehicleDistanceIdx), modeIdx)) {
                    continue;
                }
                
                String personId = parts[personIdIdx].trim();
                String mode = parts[modeIdx].trim();
                double distance_m;
                try {
                    distance_m = Double.parseDouble(parts[vehicleDistanceIdx].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                
                double distance_km = distance_m / 1000.0;
                double legEmissions = 0.0;
                
                if ("car".equals(mode) || "car_passenger".equals(mode)) {
                    legEmissions = distance_km * emissionsGco2PerKmCar;
                } else if ("pt".equals(mode)) {
                    legEmissions = distance_km * emissionsGco2PerKmTransit;
                }
                // walk and bicycle have zero emissions
                
                emissions.merge(personId, legEmissions, Double::sum);
            }
            
            logger.info("Read baseline emissions for {} persons from {}", emissions.size(), baselineLegsPath);
            
        } catch (IOException e) {
            logger.error("Error reading baseline legs file: {}", baselineLegsPath, e);
        }
        
        return emissions;
    }
    
    @Override
    public String getDescription() {
        return "Grandfathering allocation: Coins proportional to baseline emissions";
    }
}

