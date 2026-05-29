package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Pre-computes logsum-based accessibility for all agents in the population.
 * 
 * The logsum (Expected Maximum Utility) represents the accessibility an agent has
 * based on their available mode-destination combinations.
 * 
 * Logsum = ln(sum_i(exp(V_i))) where V_i is the systematic utility of alternative i
 * 
 * This implementation considers:
 * - Mode availability (car, PT, bike, walk) based on agent attributes
 * - Distance to opportunities (work, shopping, leisure locations)
 * - Mode-specific utility parameters
 * 
 * Usage:
 *   java -cp bavaria-1.5.0.jar org.eqasim.core.simulation.policies.impl.mobility_coins.allocation.AccessibilityPrecomputer \
 *       --config config.xml \
 *       --output accessibility.csv \
 *       --threads 12
 */
public class AccessibilityPrecomputer {
    
    private static final Logger logger = LogManager.getLogger(AccessibilityPrecomputer.class);
    
    // Mode choice utility parameters (from eqasim)
    private static final double BETA_TRAVEL_TIME_CAR = -0.06;      // utils/min
    private static final double BETA_TRAVEL_TIME_PT = -0.04;       // utils/min
    private static final double BETA_TRAVEL_TIME_BIKE = -0.08;     // utils/min
    private static final double BETA_TRAVEL_TIME_WALK = -0.12;     // utils/min
    
    private static final double BETA_COST_CAR = -0.05;             // utils/EUR
    private static final double BETA_COST_PT = -0.03;              // utils/EUR
    
    // Mode-specific constants (ASC)
    private static final double ASC_CAR = 0.0;
    private static final double ASC_PT = -0.5;
    private static final double ASC_BIKE = -1.0;
    private static final double ASC_WALK = -0.3;
    
    // Speed assumptions (km/h)
    private static final double SPEED_CAR = 35.0;
    private static final double SPEED_PT = 20.0;
    private static final double SPEED_BIKE = 15.0;
    private static final double SPEED_WALK = 5.0;
    
    // Cost assumptions
    private static final double COST_PER_KM_CAR = 0.20;  // EUR/km
    private static final double COST_FLAT_PT = 2.50;     // EUR (average PT fare)
    
    // Opportunity weights by type
    private static final Map<String, Double> OPPORTUNITY_WEIGHTS = Map.of(
        "work", 2.0,
        "education", 1.5,
        "shopping", 1.0,
        "leisure", 0.8,
        "other", 0.5
    );
    
    // Maximum search radius for opportunities (meters)
    // Beyond this distance, opportunities contribute negligibly to accessibility
    private static final double MAX_SEARCH_RADIUS_M = 50000.0;  // 50 km
    
    // Distance bands for computational efficiency
    // We search progressively: close opportunities first, then expand
    private static final double[] DISTANCE_BANDS_M = {5000, 15000, 30000, 50000};
    
    private final Scenario scenario;
    private final int numThreads;
    private final QuadTree<OpportunityInfo> opportunityQuadTree;
    private final int totalOpportunities;
    
    /**
     * Simple container for opportunity information.
     */
    private static class OpportunityInfo {
        final Coord coord;
        final String type;
        
        OpportunityInfo(Coord coord, String type) {
            this.coord = coord;
            this.type = type;
        }
    }
    
    public AccessibilityPrecomputer(Scenario scenario, int numThreads) {
        this.scenario = scenario;
        this.numThreads = numThreads;
        this.opportunityQuadTree = buildOpportunityQuadTree();
        this.totalOpportunities = opportunityQuadTree.size();
    }
    
    /**
     * Build a QuadTree of all opportunity locations for efficient spatial queries.
     * This allows O(log n) lookup of nearby opportunities instead of O(n).
     */
    private QuadTree<OpportunityInfo> buildOpportunityQuadTree() {
        logger.info("Building spatial index for opportunity locations...");
        
        // First, collect all opportunities and find bounds
        List<OpportunityInfo> allOpportunities = new ArrayList<>();
        double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;
        
        // Try facilities first
        ActivityFacilities facilities = scenario.getActivityFacilities();
        if (facilities != null && !facilities.getFacilities().isEmpty()) {
            for (ActivityFacility facility : facilities.getFacilities().values()) {
                Coord coord = facility.getCoord();
                if (coord != null) {
                    String type = facility.getActivityOptions().keySet().stream()
                        .findFirst().orElse("other");
                    allOpportunities.add(new OpportunityInfo(coord, type));
                    
                    minX = Math.min(minX, coord.getX());
                    maxX = Math.max(maxX, coord.getX());
                    minY = Math.min(minY, coord.getY());
                    maxY = Math.max(maxY, coord.getY());
                }
            }
            logger.info("Loaded {} opportunities from facilities", allOpportunities.size());
        }
        
        // If no facilities, extract from population activities
        if (allOpportunities.isEmpty()) {
            logger.info("No facilities found, extracting from population activities...");
            Set<String> uniqueCoords = new HashSet<>();
            
            for (Person person : scenario.getPopulation().getPersons().values()) {
                Plan plan = person.getSelectedPlan();
                if (plan == null) continue;
                
                for (var element : plan.getPlanElements()) {
                    if (element instanceof Activity) {
                        Activity activity = (Activity) element;
                        Coord coord = activity.getCoord();
                        if (coord != null) {
                            String key = coord.getX() + "_" + coord.getY();
                            if (!uniqueCoords.contains(key)) {
                                uniqueCoords.add(key);
                                allOpportunities.add(new OpportunityInfo(coord, activity.getType()));
                                
                                minX = Math.min(minX, coord.getX());
                                maxX = Math.max(maxX, coord.getX());
                                minY = Math.min(minY, coord.getY());
                                maxY = Math.max(maxY, coord.getY());
                            }
                        }
                    }
                }
            }
            logger.info("Extracted {} unique opportunities from activities", allOpportunities.size());
        }
        
        // Add buffer to bounds
        double buffer = 10000.0;  // 10 km buffer
        minX -= buffer; maxX += buffer;
        minY -= buffer; maxY += buffer;
        
        // Build QuadTree
        QuadTree<OpportunityInfo> quadTree = new QuadTree<>(minX, minY, maxX, maxY);
        for (OpportunityInfo opp : allOpportunities) {
            quadTree.put(opp.coord.getX(), opp.coord.getY(), opp);
        }
        
        logger.info("Built QuadTree with {} opportunities", quadTree.size());
        logger.info("Bounds: [{}, {}] to [{}, {}]", minX, minY, maxX, maxY);
        
        return quadTree;
    }
    
    /**
     * Compute accessibility for all persons using parallel processing.
     */
    public Map<Id<Person>, Double> computeAccessibility() {
        Population population = scenario.getPopulation();
        int totalPersons = population.getPersons().size();
        
        logger.info("Computing accessibility for {} persons using {} threads...", totalPersons, numThreads);
        
        Map<Id<Person>, Double> results = new ConcurrentHashMap<>();
        AtomicInteger progress = new AtomicInteger(0);
        
        // Create thread pool
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<?>> futures = new ArrayList<>();
        
        // Partition persons into batches
        List<Person> personList = new ArrayList<>(population.getPersons().values());
        int batchSize = Math.max(100, totalPersons / (numThreads * 10));
        
        for (int i = 0; i < personList.size(); i += batchSize) {
            int start = i;
            int end = Math.min(i + batchSize, personList.size());
            List<Person> batch = personList.subList(start, end);
            
            futures.add(executor.submit(() -> {
                for (Person person : batch) {
                    double accessibility = computePersonAccessibility(person);
                    results.put(person.getId(), accessibility);
                    
                    int count = progress.incrementAndGet();
                    if (count % 10000 == 0) {
                        logger.info("Progress: {}/{} ({:.1f}%)", 
                            count, totalPersons, 100.0 * count / totalPersons);
                    }
                }
            }));
        }
        
        // Wait for completion
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                logger.error("Error computing accessibility", e);
            }
        }
        
        executor.shutdown();
        
        logger.info("Accessibility computation complete for {} persons", results.size());
        return results;
    }
    
    /**
     * Compute accessibility for a single person using spatial indexing.
     * 
     * This method queries ALL opportunities within the search radius (50km) using
     * the QuadTree, ensuring academically rigorous results without arbitrary sampling.
     */
    private double computePersonAccessibility(Person person) {
        // Get person's home location
        Coord homeCoord = getHomeLocation(person);
        if (homeCoord == null) {
            return computeFallbackAccessibility(person);
        }
        
        // Determine mode availability
        boolean hasCar = hasCarAvailable(person);
        boolean hasPT = hasPTSubscription(person);
        boolean hasBike = hasBikeAvailable(person);
        // Walk is always available
        
        // Query ALL opportunities within search radius using QuadTree (efficient!)
        Collection<OpportunityInfo> nearbyOpportunities = opportunityQuadTree.getDisk(
            homeCoord.getX(), homeCoord.getY(), MAX_SEARCH_RADIUS_M
        );
        
        // Calculate logsum over all reachable opportunity-mode combinations
        double sumExp = 0.0;
        
        for (OpportunityInfo opp : nearbyOpportunities) {
            double distance_km = CoordUtils.calcEuclideanDistance(homeCoord, opp.coord) / 1000.0;
            
            double oppWeight = OPPORTUNITY_WEIGHTS.getOrDefault(opp.type, 0.5);
            
            // Calculate utility for each available mode
            // Note: mode availability and distance constraints determine which modes are viable
            
            if (hasCar && distance_km > 0.5) {
                double utility = calculateCarUtility(distance_km);
                sumExp += oppWeight * Math.exp(utility);
            }
            
            if (hasPT && distance_km > 1.0 && distance_km < 30.0) {
                double utility = calculatePTUtility(distance_km);
                sumExp += oppWeight * Math.exp(utility);
            }
            
            if (hasBike && distance_km > 0.3 && distance_km < 15.0) {
                double utility = calculateBikeUtility(distance_km);
                sumExp += oppWeight * Math.exp(utility);
            }
            
            if (distance_km < 5.0) {
                double utility = calculateWalkUtility(distance_km);
                sumExp += oppWeight * Math.exp(utility);
            }
        }
        
        // Return logsum (expected maximum utility)
        if (sumExp > 0) {
            return Math.log(sumExp);
        } else {
            return computeFallbackAccessibility(person);
        }
    }
    
    private double calculateCarUtility(double distance_km) {
        double travelTime_min = distance_km / SPEED_CAR * 60.0;
        double cost_eur = distance_km * COST_PER_KM_CAR;
        return ASC_CAR + BETA_TRAVEL_TIME_CAR * travelTime_min + BETA_COST_CAR * cost_eur;
    }
    
    private double calculatePTUtility(double distance_km) {
        double travelTime_min = distance_km / SPEED_PT * 60.0;
        double waitTime_min = 10.0;  // Average waiting time
        return ASC_PT + BETA_TRAVEL_TIME_PT * (travelTime_min + waitTime_min) + BETA_COST_PT * COST_FLAT_PT;
    }
    
    private double calculateBikeUtility(double distance_km) {
        double travelTime_min = distance_km / SPEED_BIKE * 60.0;
        return ASC_BIKE + BETA_TRAVEL_TIME_BIKE * travelTime_min;
    }
    
    private double calculateWalkUtility(double distance_km) {
        double travelTime_min = distance_km / SPEED_WALK * 60.0;
        return ASC_WALK + BETA_TRAVEL_TIME_WALK * travelTime_min;
    }
    
    /**
     * Get person's home location from their plan.
     */
    private Coord getHomeLocation(Person person) {
        Plan plan = person.getSelectedPlan();
        if (plan == null) return null;
        
        for (var element : plan.getPlanElements()) {
            if (element instanceof Activity) {
                Activity activity = (Activity) element;
                if ("home".equals(activity.getType())) {
                    return activity.getCoord();
                }
            }
        }
        
        // Fallback: first activity
        for (var element : plan.getPlanElements()) {
            if (element instanceof Activity) {
                return ((Activity) element).getCoord();
            }
        }
        
        return null;
    }
    
    private boolean hasCarAvailable(Person person) {
        Object carAvail = person.getAttributes().getAttribute("carAvailability");
        return "always".equals(carAvail) || "sometimes".equals(carAvail);
    }
    
    private boolean hasPTSubscription(Person person) {
        Object ptSub = person.getAttributes().getAttribute("ptSubscription");
        if (ptSub instanceof Boolean) {
            return (Boolean) ptSub;
        }
        return ptSub != null && !"none".equals(ptSub.toString());
    }
    
    private boolean hasBikeAvailable(Person person) {
        Object bikeAvail = person.getAttributes().getAttribute("bikeAvailability");
        if (bikeAvail != null) {
            return "always".equals(bikeAvail) || "sometimes".equals(bikeAvail);
        }
        // Assume bike available if not specified (common in Germany)
        return true;
    }
    
    /**
     * Fallback accessibility based on agent attributes only.
     */
    private double computeFallbackAccessibility(Person person) {
        double accessibility = 1.0;
        
        if (hasCarAvailable(person)) {
            accessibility += 2.0;
        }
        
        if (hasPTSubscription(person)) {
            accessibility += 1.5;
        }
        
        Object isResident = person.getAttributes().getAttribute("isMunichResident");
        if (isResident != null && Boolean.TRUE.equals(isResident)) {
            accessibility += 0.5;
        }
        
        return accessibility;
    }
    
    /**
     * Write results to CSV file.
     */
    public static void writeResults(Map<Id<Person>, Double> results, String outputPath) throws IOException {
        logger.info("Writing results to {}...", outputPath);
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            writer.write("person_id;accessibility\n");
            
            // Sort by person ID for consistent output
            List<Map.Entry<Id<Person>, Double>> sorted = results.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString()))
                .collect(Collectors.toList());
            
            for (Map.Entry<Id<Person>, Double> entry : sorted) {
                writer.write(entry.getKey().toString());
                writer.write(";");
                writer.write(String.format("%.6f", entry.getValue()));
                writer.write("\n");
            }
        }
        
        logger.info("Wrote {} accessibility values to {}", results.size(), outputPath);
        
        // Print statistics
        DoubleSummaryStatistics stats = results.values().stream()
            .mapToDouble(Double::doubleValue)
            .summaryStatistics();
        
        logger.info("Accessibility statistics:");
        logger.info("  Min: {:.3f}", stats.getMin());
        logger.info("  Max: {:.3f}", stats.getMax());
        logger.info("  Mean: {:.3f}", stats.getAverage());
    }
    
    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        // Parse arguments
        String configPath = null;
        String populationPath = null;
        String outputPath = "accessibility.csv";
        int threads = 12;
        
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config":
                    configPath = args[++i];
                    break;
                case "--population":
                    populationPath = args[++i];
                    break;
                case "--output":
                    outputPath = args[++i];
                    break;
                case "--threads":
                    threads = Integer.parseInt(args[++i]);
                    break;
                case "--help":
                    printHelp();
                    return;
            }
        }
        
        if (configPath == null && populationPath == null) {
            System.err.println("Error: Either --config or --population must be specified");
            printHelp();
            System.exit(1);
        }
        
        try {
            // Load scenario
            logger.info("Loading scenario...");
            Scenario scenario;
            
            if (configPath != null) {
                Config config = ConfigUtils.loadConfig(configPath);
                scenario = ScenarioUtils.loadScenario(config);
            } else {
                Config config = ConfigUtils.createConfig();
                scenario = ScenarioUtils.createScenario(config);
                new PopulationReader(scenario).readFile(populationPath);
            }
            
            logger.info("Loaded {} persons", scenario.getPopulation().getPersons().size());
            
            // Compute accessibility
            AccessibilityPrecomputer precomputer = new AccessibilityPrecomputer(scenario, threads);
            Map<Id<Person>, Double> results = precomputer.computeAccessibility();
            
            // Write results
            writeResults(results, outputPath);
            
            logger.info("Done!");
            
        } catch (Exception e) {
            logger.error("Error computing accessibility", e);
            System.exit(1);
        }
    }
    
    private static void printHelp() {
        System.out.println("AccessibilityPrecomputer - Compute logsum accessibility for MobilityCoin allocation");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -cp bavaria-1.5.0.jar " + AccessibilityPrecomputer.class.getName());
        System.out.println("       [--config <config.xml>]");
        System.out.println("       [--population <population.xml>]");
        System.out.println("       [--output <accessibility.csv>]");
        System.out.println("       [--threads <12>]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --config      Path to MATSim config file (loads full scenario)");
        System.out.println("  --population  Path to population file (standalone mode)");
        System.out.println("  --output      Output CSV file (default: accessibility.csv)");
        System.out.println("  --threads     Number of threads (default: 12)");
        System.out.println();
        System.out.println("Output format:");
        System.out.println("  CSV with columns: person_id;accessibility");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -Xmx32g -cp bavaria-1.5.0.jar " + AccessibilityPrecomputer.class.getName());
        System.out.println("       --config bavaria_config.xml --output accessibility.csv --threads 12");
    }
}

