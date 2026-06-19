package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Calculates the total number of coins based on baseline scenario emissions.
 * 
 * The calculation follows this logic:
 * 1. Read the baseline legs file and calculate total emissions (gCO2) from car and PT trips
 * 2. Convert emissions to coins using cost_coins_per_gco2 parameter
 * 3. Apply reduction percentage to get the target coin budget
 * 
 * Formula: totalCoins = baselineEmissions * cost_coins_per_gco2 * (1 - reductionPercentage)
 * 
 * For example, with 1% reduction and cost_coins_per_gco2 = 0.001:
 * - If baseline emissions = 10,000,000,000 gCO2 (10 Mt)
 * - Total coins at 100% = 10,000,000,000 * 0.001 = 10,000,000 coins
 * - Total coins at 99% (1% reduction) = 10,000,000 * 0.99 = 9,900,000 coins
 */
public class BaselineCoinsCalculator {
    
    private static final Logger logger = LogManager.getLogger(BaselineCoinsCalculator.class);
    
    private final String baselineResultsPath;
    private final double emissionsGco2PerKmCar;
    private final double emissionsGco2PerKmTransit;
    private final double costCoinsPerGco2;
    private final double reductionPercentage;
    
    /**
     * @param baselineResultsPath Path to the baseline results directory (containing eqasim_legs.csv)
     * @param emissionsGco2PerKmCar Emissions per km for car (gCO2/km)
     * @param emissionsGco2PerKmTransit Emissions per km for transit (gCO2/km)
     * @param costCoinsPerGco2 Conversion rate from gCO2 to coins (default: 0.001 = 1 coin per kgCO2)
     * @param reductionPercentage Reduction target (0.01 = 1%, 0.10 = 10%)
     */
    public BaselineCoinsCalculator(String baselineResultsPath, 
                                   double emissionsGco2PerKmCar,
                                   double emissionsGco2PerKmTransit,
                                   double costCoinsPerGco2,
                                   double reductionPercentage) {
        this.baselineResultsPath = baselineResultsPath;
        this.emissionsGco2PerKmCar = emissionsGco2PerKmCar;
        this.emissionsGco2PerKmTransit = emissionsGco2PerKmTransit;
        this.costCoinsPerGco2 = costCoinsPerGco2;
        this.reductionPercentage = reductionPercentage;
    }
    
    /**
     * Calculate total coins based on baseline emissions and reduction percentage.
     * @return Total coins to be allocated
     */
    public double calculateTotalCoins() {
        // Find the baseline legs file
        String legsFilePath = findLegsFile();
        if (legsFilePath == null) {
            logger.error("Could not find baseline legs file in: {}", baselineResultsPath);
            return 0.0;
        }
        
        // Calculate baseline emissions
        double totalEmissions = calculateBaselineEmissions(legsFilePath);
        
        // Convert to coins at 100% level
        double coinsAt100Percent = totalEmissions * costCoinsPerGco2;
        
        // Apply reduction
        double targetCoins = coinsAt100Percent * (1.0 - reductionPercentage);
        
        logger.info("Baseline coins calculation:");
        logger.info("  - Baseline emissions: {} gCO2 ({} tCO2)", totalEmissions, totalEmissions / 1_000_000);
        logger.info("  - Conversion rate: {} coins/gCO2 (= {} coins/kgCO2)", costCoinsPerGco2, costCoinsPerGco2 * 1000);
        logger.info("  - Coins at 100%: {}", coinsAt100Percent);
        logger.info("  - Reduction target: {}%", reductionPercentage * 100);
        logger.info("  - Target coins ({}% of baseline): {}", (1.0 - reductionPercentage) * 100, targetCoins);
        
        return targetCoins;
    }
    
    /**
     * Find the legs file in the baseline results directory.
     * @return Path to the legs file, or null if not found
     */
    private String findLegsFile() {
        File baseDir = new File(baselineResultsPath);
        
        // Try various file names
        String[] possibleNames = {
            "eqasim_legs.csv",
            "output_legs.csv",
            "legs.csv"
        };
        
        for (String name : possibleNames) {
            File f = new File(baseDir, name);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        
        // AUCH .GZ-VERSIONEN PRUEFEN UND ZURUECKGEBEN - IOUtils KANN DIESE TRANSPARENT LESEN
        for (String name : possibleNames) {
            File f = new File(baseDir, name + ".gz");
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }

        return null;
    }
    
    /**
     * Calculate total emissions from the baseline legs file.
     * @param legsFilePath Path to the legs CSV file
     * @return Total emissions in gCO2
     */
    private double calculateBaselineEmissions(String legsFilePath) {
        double totalCarKm = 0.0;
        double totalTransitKm = 0.0;
        
        try (BufferedReader reader = new BufferedReader(new FileReader(legsFilePath))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                logger.error("Empty baseline legs file: {}", legsFilePath);
                return 0.0;
            }
            
            // Parse header to find column indices
            String[] headers = headerLine.split(";");
            int vehicleDistanceIdx = -1;
            int modeIdx = -1;
            
            for (int i = 0; i < headers.length; i++) {
                String h = headers[i].trim();
                if ("vehicle_distance".equals(h) || "routed_distance".equals(h)) {
                    vehicleDistanceIdx = i;
                }
                if ("mode".equals(h)) {
                    modeIdx = i;
                }
            }
            
            if (vehicleDistanceIdx < 0 || modeIdx < 0) {
                logger.error("Missing required columns in baseline legs file. Found: vehicle_distance={}, mode={}", 
                           vehicleDistanceIdx, modeIdx);
                return 0.0;
            }
            
            // Read data
            String line;
            long lineCount = 0;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(";");
                if (parts.length <= Math.max(vehicleDistanceIdx, modeIdx)) {
                    continue;
                }
                
                String mode = parts[modeIdx].trim();
                double distance_m;
                try {
                    distance_m = Double.parseDouble(parts[vehicleDistanceIdx].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                
                double distance_km = distance_m / 1000.0;
                
                if ("car".equals(mode) || "car_passenger".equals(mode)) {
                    totalCarKm += distance_km;
                } else if ("pt".equals(mode)) {
                    totalTransitKm += distance_km;
                }
                // walk and bicycle have zero emissions
                
                lineCount++;
                if (lineCount % 1_000_000 == 0) {
                    logger.info("  Processed {} million legs...", lineCount / 1_000_000);
                }
            }
            
            logger.info("Baseline distances: {} km car, {} km transit", totalCarKm, totalTransitKm);
            
        } catch (IOException e) {
            logger.error("Error reading baseline legs file: {}", legsFilePath, e);
            return 0.0;
        }
        
        // Calculate emissions
        double carEmissions = totalCarKm * emissionsGco2PerKmCar;
        double transitEmissions = totalTransitKm * emissionsGco2PerKmTransit;
        double totalEmissions = carEmissions + transitEmissions;
        
        logger.info("Baseline emissions: {} gCO2 from car, {} gCO2 from transit, {} gCO2 total",
                   carEmissions, transitEmissions, totalEmissions);
        
        return totalEmissions;
    }
    
    /**
     * Get the baseline emissions (without reduction) as coins.
     * @return Coins at 100% baseline
     */
    public double getBaselineCoins() {
        return calculateTotalCoins() / (1.0 - reductionPercentage);
    }
}

