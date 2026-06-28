package org.eqasim.core.simulation.policies.impl.mobility_coins.logic;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsDistances;
import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsWriter;
import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsWriter.Entry;
import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsWalletWriter;
import org.eqasim.core.simulation.policies.impl.mobility_coins.allocation.*;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.api.core.v01.Id;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Heart of the MobilityCoin (tradable-credit) scheme.
 *
 * <p>WHAT IT DOES, once per MATSim iteration ({@link #notifyIterationEnds}):
 * <ol>
 *   <li>Reconstructs every agent's coin balance from their selected plan: trips with car/PT
 *       <em>cost</em> coins, walking/cycling can <em>earn</em> coins (see
 *       {@link MobilityCoinsCalculator}).</li>
 *   <li>Aggregates the market: total <em>shortage</em> (agents in debt) vs. <em>excess</em>
 *       (agents with leftover coins).</li>
 *   <li>Updates the single market price (EUR per coin) with a proportional controller so the
 *       market clears towards the emission-reduction target ({@link #updateMarketPrice}). This
 *       price is what makes coin-consuming modes more/less attractive in mode choice.</li>
 *   <li>Re-distributes next iteration's coin budget across agents using the configured
 *       (possibly heterogeneous) allocation scheme, and subtracts coins already earned via
 *       incentives so the total supply stays at the emission target
 *       ({@link #updateDynamicAllocation}).</li>
 *   <li>Writes per-iteration market metrics and per-agent wallets to CSV.</li>
 * </ol>
 *
 * <p>Two key economic ideas a student should take away:
 * <ul>
 *   <li><b>Heterogeneous allocation</b>: the total coin budget is fixed (from the emission
 *       target), but {@link AllocationCalculator} decides <em>who</em> gets how many coins
 *       (uniform, by income, by accessibility, by age, or grandfathered).</li>
 *   <li><b>Incentive-adaptive allocation</b>: coins earned from walking/cycling add to supply,
 *       so the agency hands out fewer allocated coins to keep the emission cap intact.</li>
 * </ul>
 */
public class MobilityCoinsMarket implements IterationEndsListener {
    private static final Logger logger = LogManager.getLogger(MobilityCoinsMarket.class);
    
    static public final String WALLET_ATTRIBUTE = "wallet";

    private final MobilityCoinsParameters parameters;
    private final MobilityCoinsCalculator calculator;
    private final MobilityCoinsWriter writer;
    private final MobilityCoinsWalletWriter walletWriter;

    private final Population population;
    private final TransitSchedule transitSchedule;

    // Allocation calculator for individual coin distribution
    private AllocationCalculator allocationCalculator;
    
    // =========================================================================
    // EMISSION-BASED DYNAMIC ALLOCATION
    // =========================================================================
    // The system now targets EMISSION REDUCTION, not a fixed coin allocation.
    // - baselineEmissions: Total emissions from baseScenario (gCO2)
    // - targetEmissions: baselineEmissions × (1 - reductionPercentage)
    // - targetEmissionCoins: targetEmissions × cost_coins_per_gco2
    //
    // Each iteration, coins are dynamically adjusted:
    // - incentiveCoins: Coins generated from walking/biking (adds to supply)
    // - effectiveAllocation: targetEmissionCoins - incentiveCoins
    //
    // This ensures: allocation + incentives = targetEmissionCoins (constant)
    // So the emission target is maintained regardless of incentive level.
    // =========================================================================
    
    // Baseline emission data (calculated once at startup)
    private double baselineEmissions_gCO2 = 0.0;           // Total baseline emissions
    private double targetEmissions_gCO2 = 0.0;             // Target after reduction
    private double targetEmissionCoins = 0.0;              // Target in coin terms
    
    // Dynamic allocation tracking (updated each iteration)
    private double currentIncentiveCoins = 0.0;            // Last iteration's incentive coins
    private double currentEffectiveAllocation = 0.0;       // Adjusted allocation target
    private double currentAllocatedCoins = 0.0;            // Coins actually in wallets

    // =========================================================================
    // NORMALIZED P-CONTROLLER (Proportional Only)
    // =========================================================================
    // The market price is steered by a simple proportional (P) controller:
    // - No integral term → no windup from the replanning lag
    // - No derivative term → no noise amplification
    // - Error is normalized by the emission target → gains scale automatically
    //   across different reduction targets (1-10%)
    // - Adaptive damping at small errors → prevents overshoot
    //
    // Design rationale:
    // - With ~5% replanning per iteration the system reacts with a ~20-iteration lag,
    //   so a pure P-controller (no integral term) avoids windup from delayed feedback.
    // - The proportional gain Kp is chosen in three tiers depending on how much
    //   emission-reduction "pressure" the run applies (via cost_coins_per_gco2).
    // - Typical convergence: 20-50 iterations.
    // =========================================================================

    // Three-tier proportional gain based on emission reduction pressure.

    // HIGH-PRESSURE scenarios (cost_coins_per_gco2 >= 0.008)
    // Lowest gain to prevent overshoot: agents are very sensitive at high charges.
    private static final double Kp_TMC_HIGH = 0.25;

    // MEDIUM-PRESSURE scenarios (0.003 <= cost_coins_per_gco2 < 0.008)
    // Moderate gain for balanced scenarios.
    private static final double Kp_TMC_MID = 0.5;

    // LOW-PRESSURE scenarios (cost_coins_per_gco2 < 0.003)
    // Highest gain: agents are least responsive to price changes at low charge levels,
    // so larger price steps are needed to move them.
    private static final double Kp_TMC_LOW = 1.0;

    // Thresholds for determining pressure tiers
    // Based on cost_coins_per_gco2 parameter
    private static final double COST_THRESHOLD_HIGH = 0.008;  // High-pressure threshold
    private static final double COST_THRESHOLD_LOW = 0.003;   // Low-pressure threshold

    // State tracking for error history
    private double lastError = 0.0;

    // updated after every iteration
    private double marketPrice_EUR_per_coin;
    
    // Track the last price adjustment for logging purposes
    private double lastPriceAdjustment = 0.0;
    
    // Track the latest error for termination criteria and trip dropping strategy
    private double latestError = 0.0;

    // Counter for dropped leisure trips (reset each iteration)
    private int droppedLeisureTripsCount = 0;

    public double getMarketPrice_EUR_per_coin() {
        // used wherever the market price is needed
        return marketPrice_EUR_per_coin;
    }
    
    /**
     * Increment the counter for dropped leisure trips.
     * Called by RemoveExpensiveTripsStrategy when a trip is dropped.
     */
    public synchronized void incrementDroppedLeisureTrips() {
        droppedLeisureTripsCount++;
    }
    
    /**
     * Get and reset the dropped leisure trips counter.
     * @return The count of dropped trips since last reset.
     */
    public synchronized int getAndResetDroppedLeisureTripsCount() {
        int count = droppedLeisureTripsCount;
        droppedLeisureTripsCount = 0;
        return count;
    }
    
    /**
     * Gets the latest error value used for market price updates.
     * This is shortage minus excess and is used by the trip dropping strategy.
     * 
     * @return The error used for PID controller
     */
    public double getLatestError() {
        return latestError;
    }
    
    /**
     * Get the target emission coins (emission budget).
     * Used for market error termination criterion.
     * 
     * @return The target emission coins representing market equilibrium point
     */
    public double getTargetEmissionCoins() {
        return targetEmissionCoins;
    }

    public MobilityCoinsMarket(MobilityCoinsParameters parameters, MobilityCoinsCalculator calculator,
            Population population, MobilityCoinsWriter writer, MobilityCoinsWalletWriter walletWriter,
            TransitSchedule transitSchedule) {
        this.parameters = parameters;
        this.population = population;
        this.calculator = calculator;
        this.writer = writer;
        this.walletWriter = walletWriter;
        this.transitSchedule = transitSchedule;
        this.marketPrice_EUR_per_coin = parameters.initialMarketPrice_EUR_per_coin;

        // Calculate baseline emissions and target (BEFORE wallet initialization)
        calculateEmissionTargets();

        // Initialize wallets based on market scheme
        initializeWallets();
    }
    
    /**
     * Calculate emission targets from baseline scenario.
     * This is called ONCE at startup and defines the fixed emission reduction target.
     */
    private void calculateEmissionTargets() {
        logger.info("=== Calculating Emission Targets ===");
        
        if (parameters.baselineResultsPath == null || parameters.baselineResultsPath.isEmpty()) {
            logger.warn("No baselineResultsPath provided. Using fallback calculation.");
            // Fallback: estimate from initialCoins_per_person
            int numPersons = population.getPersons().size();
            this.targetEmissionCoins = parameters.initialCoins_per_person * numPersons 
                                       * (1.0 - parameters.reductionPercentage);
            this.baselineEmissions_gCO2 = this.targetEmissionCoins / parameters.cost_coins_per_gco2 
                                          / (1.0 - parameters.reductionPercentage);
            this.targetEmissions_gCO2 = this.baselineEmissions_gCO2 * (1.0 - parameters.reductionPercentage);
            return;
        }
        
        // Use BaselineCoinsCalculator to get emissions
        BaselineCoinsCalculator baselineCalc = new BaselineCoinsCalculator(
            parameters.baselineResultsPath,
            parameters.emissions_gco2_per_km_car,
            parameters.emissions_gco2_per_km_transit,
            parameters.cost_coins_per_gco2 > 0 ? parameters.cost_coins_per_gco2 : 0.001,
            0.0  // No reduction for baseline calculation
        );
        
        // Get 100% baseline coins (no reduction)
        double baseline100PercentCoins = baselineCalc.calculateTotalCoins();
        
        // Calculate emissions from coins
        double costCoins = parameters.cost_coins_per_gco2 > 0 ? parameters.cost_coins_per_gco2 : 0.001;
        this.baselineEmissions_gCO2 = baseline100PercentCoins / costCoins;
        this.targetEmissions_gCO2 = this.baselineEmissions_gCO2 * (1.0 - parameters.reductionPercentage);
        this.targetEmissionCoins = baseline100PercentCoins * (1.0 - parameters.reductionPercentage);
        
        // Initialize effective allocation to target (no incentives yet)
        this.currentEffectiveAllocation = this.targetEmissionCoins;
        
        logger.info("Emission-based targets:");
        logger.info("  - Baseline emissions: {} gCO2 ({} tCO2)", baselineEmissions_gCO2, baselineEmissions_gCO2 / 1_000_000);
        logger.info("  - Reduction target: {}%", parameters.reductionPercentage * 100);
        logger.info("  - Target emissions: {} gCO2 ({} tCO2)", targetEmissions_gCO2, targetEmissions_gCO2 / 1_000_000);
        logger.info("  - Target emission coins: {}", targetEmissionCoins);
        logger.info("=== Emission Targets Complete ===");
    }
    
    /**
     * Initialize wallets based on the selected market scheme and allocation scheme.
     * 
     * The initialization process follows these steps:
     * 1. Determine total coins to distribute based on allocation scheme and baseline data
     * 2. Select appropriate allocation calculator
     * 3. Calculate individual allocations for each person
     * 4. Apply allocations to person wallets
     */
    private void initializeWallets() {
        logger.info("=== Initializing MobilityCoin Wallets ===");
        logger.info("Allocation Scheme: {}", parameters.allocationScheme);

        // Calculate total coins to distribute
        double totalCoins = calculateTotalCoinsToDistribute();
        logger.info("Total coins to distribute: {}", totalCoins);
        
        // Create allocation calculator based on scheme
        this.allocationCalculator = createAllocationCalculator();
        logger.info("Allocation method: {}", allocationCalculator.getDescription());
        
        // Calculate individual allocations
        Map<Id<Person>, Double> allocations = allocationCalculator.calculateAllocations(population, totalCoins);
        
        // Apply allocations to persons
        for (Person person : population.getPersons().values()) {
            Double coins = allocations.get(person.getId());
            if (coins == null) {
                coins = 0.0;
                logger.warn("No allocation found for person {}, setting to 0", person.getId());
            }
            person.getAttributes().putAttribute(WALLET_ATTRIBUTE, coins);
        }
        
        // Log statistics
        logAllocationStatistics(allocations);
        logger.info("=== Wallet Initialization Complete ===");
    }
    
    /**
     * Calculate total coins to distribute based on configuration.
     * 
     * If baselineResultsPath is provided and allocation scheme requires baseline data,
     * the total coins are computed from baseline emissions with the reduction percentage.
     * 
     * Otherwise, uses the uniform allocation: initialCoins_per_person * population_size
     */
    private double calculateTotalCoinsToDistribute() {
        // Check if we should compute from baseline
        if (parameters.baselineResultsPath != null && !parameters.baselineResultsPath.isEmpty()) {
            File baselineDir = new File(parameters.baselineResultsPath);
            if (baselineDir.exists() && baselineDir.isDirectory()) {
                logger.info("Computing total coins from baseline at: {}", parameters.baselineResultsPath);
                
                BaselineCoinsCalculator baselineCalc = new BaselineCoinsCalculator(
                    parameters.baselineResultsPath,
                    parameters.emissions_gco2_per_km_car,
                    parameters.emissions_gco2_per_km_transit,
                    parameters.cost_coins_per_gco2 > 0 ? parameters.cost_coins_per_gco2 : 0.001, // default if not set
                    parameters.reductionPercentage
                );
                
                double totalCoins = baselineCalc.calculateTotalCoins();
                if (totalCoins > 0) {
                    return totalCoins;
                }
                logger.warn("Baseline calculation returned 0 coins, falling back to uniform method");
            } else {
                logger.warn("Baseline results path does not exist: {}", parameters.baselineResultsPath);
            }
        }
        
        // Fallback: use uniform method based on initialCoins_per_person
        int numPersons = population.getPersons().size();
        double totalCoins = parameters.initialCoins_per_person * numPersons;
        logger.info("Using uniform calculation: {} persons * {} coins/person = {} total coins",
                   numPersons, parameters.initialCoins_per_person, totalCoins);
        return totalCoins;
    }
    
    /**
     * Create the appropriate allocation calculator based on the configured scheme.
     */
    private AllocationCalculator createAllocationCalculator() {
        switch (parameters.allocationScheme) {
            case UNIFORM:
                return new UniformAllocationCalculator();
                
            case GRANDFATHERING:
                String legsPath = findBaselineLegsFile();
                if (legsPath != null) {
                    return new GrandfatheringAllocationCalculator(
                        legsPath,
                        parameters.emissions_gco2_per_km_car,
                        parameters.emissions_gco2_per_km_transit
                    );
                }
                logger.warn("Baseline legs file not found for GRANDFATHERING, falling back to UNIFORM");
                return new UniformAllocationCalculator();
                
            case INCOME:
                return new IncomeBasedAllocationCalculator(parameters.incomeProgressiveFactor);
                
            case ACCESSIBILITY:
                // Use pre-computed accessibility file if provided, otherwise compute from attributes
                String accessibilityFile = (parameters.accessibilityFilePath != null && 
                                            !parameters.accessibilityFilePath.isEmpty()) 
                                           ? parameters.accessibilityFilePath : null;
                if (accessibilityFile != null) {
                    logger.info("Using pre-computed accessibility from: {}", accessibilityFile);
                } else {
                    logger.info("Computing accessibility from person attributes (carAvailability, ptSubscription, etc.)");
                }
                return new AccessibilityBasedAllocationCalculator(
                    parameters.accessibilityProgressiveFactor,
                    accessibilityFile
                );
                
            case AGE_EXEMPT:
                return new AgeExemptAllocationCalculator(
                    parameters.ageExemptMinAge,
                    parameters.ageExemptMaxAge,
                    parameters.reductionPercentage
                );

            case SUFFI:
                return new SuffiAllocationCalculator(transitSchedule);

            case VERTICAL:
                if (parameters.agentParamsFilePath == null || parameters.agentParamsFilePath.isBlank()) {
                    throw new IllegalStateException(
                            "VERTICAL allocation benötigt --moco:agentParamsFilePath (agent_params.csv).");
                }
                try {
                    Map<String, AgentParametersPrecomputer.AgentParams> verticalAgentParams =
                            AgentParametersPrecomputer.readResults(parameters.agentParamsFilePath);
                    return new VerticalAllocationCalculator(
                            verticalAgentParams,
                            parameters.verticalWeightIncome,
                            parameters.verticalWeightPt,
                            parameters.verticalWeightVehicle,
                            parameters.verticalWeightHousehold);
                } catch (IOException e) {
                    throw new RuntimeException(
                            "VERTICAL: Fehler beim Lesen von " + parameters.agentParamsFilePath, e);
                }

            case SE_MN:
                if (parameters.agentParamsFilePath == null || parameters.agentParamsFilePath.isBlank()) {
                    throw new IllegalStateException(
                            "SE_MN allocation benötigt --moco:agentParamsFilePath (agent_params.csv).");
                }
                try {
                    Map<String, AgentParametersPrecomputer.AgentParams> seMnAgentParams =
                            AgentParametersPrecomputer.readResults(parameters.agentParamsFilePath);
                    return new SeMnAllocationCalculator(
                            seMnAgentParams,
                            parameters.seMnWeightZoneHome,
                            parameters.seMnWeightZoneWork,
                            parameters.seMnWeightZoneEducation,
                            parameters.seMnWeightPt,
                            parameters.seMnWeightDistance,
                            parameters.seMnWeightTime);
                } catch (IOException e) {
                    throw new RuntimeException(
                            "SE_MN: Fehler beim Lesen von " + parameters.agentParamsFilePath, e);
                }

            case UTIL:
                return new UtilAllocationCalculator();

            case HE_SOCIO:
                if (parameters.agentParamsFilePath == null || parameters.agentParamsFilePath.isBlank()) {
                    throw new IllegalStateException(
                            "HE_SOCIO allocation benötigt --moco:agentParamsFilePath (agent_params.csv).");
                }
                try {
                    Map<String, AgentParametersPrecomputer.AgentParams> heSocioAgentParams =
                            AgentParametersPrecomputer.readResults(parameters.agentParamsFilePath);
                    return new HeSocioAllocationCalculator(
                            heSocioAgentParams,
                            parameters.heSocioWeightIncome,
                            parameters.heSocioWeightHome,
                            parameters.heSocioWeightHousehold);
                } catch (IOException e) {
                    throw new RuntimeException(
                            "HE_SOCIO: Fehler beim Lesen von " + parameters.agentParamsFilePath, e);
                }

            case HE_LS:
                if (parameters.agentParamsFilePath == null || parameters.agentParamsFilePath.isBlank()) {
                    throw new IllegalStateException(
                            "HE_LS allocation benötigt --moco:agentParamsFilePath (agent_params.csv).");
                }
                try {
                    Map<String, AgentParametersPrecomputer.AgentParams> heLsAgentParams =
                            AgentParametersPrecomputer.readResults(parameters.agentParamsFilePath);
                    return new HeLsAllocationCalculator(
                            heLsAgentParams,
                            parameters.heLsWeightEmployment,
                            parameters.heLsWeightAge,
                            parameters.heLsWeightDistance);
                } catch (IOException e) {
                    throw new RuntimeException(
                            "HE_LS: Fehler beim Lesen von " + parameters.agentParamsFilePath, e);
                }

            default:
                logger.warn("Unknown allocation scheme: {}, using UNIFORM", parameters.allocationScheme);
                return new UniformAllocationCalculator();
        }
    }
    
    /**
     * Find the baseline legs file for grandfathering allocation.
     */
    private String findBaselineLegsFile() {
        if (parameters.baselineResultsPath == null || parameters.baselineResultsPath.isEmpty()) {
            return null;
        }
        
        File baseDir = new File(parameters.baselineResultsPath);
        String[] possibleNames = {"eqasim_legs.csv", "output_legs.csv", "legs.csv"};
        
        for (String name : possibleNames) {
            File f = new File(baseDir, name);
            if (f.exists() && f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        
        return null;
    }
    
    /**
     * Dynamically update coin allocations based on incentive coins generated.
     * 
     * This implements the emission-based allocation logic:
     * - Total effective supply = targetEmissionCoins (fixed)
     * - Incentive coins add to supply, so we reduce allocation to compensate
     * - effectiveAllocation = targetEmissionCoins - incentiveCoins
     * 
     * Called at the end of each iteration to prepare for the next iteration.
     * 
     * @param incentiveCoinsGenerated Coins earned from walking/biking this iteration
     */
    private void updateDynamicAllocation(double incentiveCoinsGenerated) {
        // Store current incentive coins
        this.currentIncentiveCoins = incentiveCoinsGenerated;
        
        // Calculate new effective allocation target
        // This is the key formula: allocation + incentives = targetEmissionCoins
        double newEffectiveAllocation = this.targetEmissionCoins - incentiveCoinsGenerated;
        
        // Ensure non-negative allocation
        if (newEffectiveAllocation < 0) {
            logger.warn("Incentive coins ({}) exceed target emission coins ({}). Setting allocation to 0.",
                       incentiveCoinsGenerated, targetEmissionCoins);
            newEffectiveAllocation = 0.0;
        }
        
        this.currentEffectiveAllocation = newEffectiveAllocation;

        // Recalculate individual allocations based on the new total and write them back to
        // the agents' wallets, using the configured (possibly heterogeneous) allocation scheme.
        if (this.allocationCalculator != null) {
            Map<Id<Person>, Double> newAllocations =
                allocationCalculator.calculateAllocations(population, newEffectiveAllocation);

            // Apply new allocations to persons
            for (Person person : population.getPersons().values()) {
                Double coins = newAllocations.get(person.getId());
                if (coins == null) {
                    coins = 0.0;
                }
                person.getAttributes().putAttribute(WALLET_ATTRIBUTE, coins);
            }

            this.currentAllocatedCoins = newEffectiveAllocation;

            logger.info("Dynamic allocation update:");
            logger.info("  - Target emission coins (fixed): {}", targetEmissionCoins);
            logger.info("  - Incentive coins generated: {}", incentiveCoinsGenerated);
            logger.info("  - New effective allocation: {}", newEffectiveAllocation);
            logger.info("  - Allocation change: {}", newEffectiveAllocation - currentAllocatedCoins);
        }
    }
    
    /**
     * Get the current effective allocation target.
     * This is the targetEmissionCoins minus incentive coins.
     */
    public double getCurrentEffectiveAllocation() {
        return currentEffectiveAllocation;
    }
    
    /**
     * Get baseline emissions in gCO2.
     */
    public double getBaselineEmissions() {
        return baselineEmissions_gCO2;
    }
    
    /**
     * Get target emissions in gCO2 (after reduction).
     */
    public double getTargetEmissions() {
        return targetEmissions_gCO2;
    }
    
    /**
     * Log statistics about the coin allocation.
     */
    private void logAllocationStatistics(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        double sum = 0.0;
        int zeroCount = 0;
        
        for (Double coins : allocations.values()) {
            min = Math.min(min, coins);
            max = Math.max(max, coins);
            sum += coins;
            if (coins == 0.0) {
                zeroCount++;
            }
        }
        
        double avg = sum / allocations.size();
        
        logger.info("Allocation Statistics:");
        logger.info("  - Total allocated: {} coins", sum);
        logger.info("  - Number of persons: {}", allocations.size());
        logger.info("  - Min allocation: {} coins", min);
        logger.info("  - Max allocation: {} coins", max);
        logger.info("  - Average allocation: {} coins", avg);
        logger.info("  - Persons with 0 coins: {} ({}%)", zeroCount, 100.0 * zeroCount / allocations.size());
    }

    private double calculateBalance() {
        // calculate a balance of coins in the system
        double globalBalance = 0.0;

        // the idea is that we go through the current configuration of the population
        // and reconstruct the coins lost / gained
        for (Person person : population.getPersons().values()) {
            double personBalance = getInitialCoins(person);

            for (Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(trip.getTripElements());
                double coinsDelta = calculator.calculateCoinDelta(distances, person);
                personBalance += coinsDelta;
            }

            // Note: we discussed this change in our first meeting after your implementation 
            globalBalance += personBalance;

            /*
            globalDeltaToTarget += Math.max(0.0, personBalance);

            if (personBalance < 0.0) {
                // agent needed to buy extra coins
                globalDeltaToTarget -= personBalance;
            }
            */

        }

        return globalBalance;
    }

    /**
     * Calculates the total amount of coins used/earned across all persons in the current iteration.
     * A negative value indicates coins were used up (spent), while a positive value indicates coins were earned.
     * 
     * @param population The population to analyze
     * @return The total coinsDelta across all persons
     */
    public double calculateUsedCoins() {
        double totalCoinsCharged = 0.0;

        for (Person person : population.getPersons().values()) {
            for (Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(trip.getTripElements());
                double coinsDelta = calculator.calculateCoinDelta(distances, person);
                totalCoinsCharged += coinsDelta;
            }
        }
        // return the inverse of the total coins used up so that spent coins are positive and earned coins are negative
        return -totalCoinsCharged;
    }

    /**
     * Calculates the total excess coins (positive wallet balances) at the end of the current iteration.
     * This represents the total coins held by persons who have more coins than they started with.
     * 
     * @return The total excess coins across all persons with positive balances
     */
    public double calculateExcessCoins() {
        double totalExcessCoins = 0.0;

        for (Person person : population.getPersons().values()) {
            double personBalance = getInitialCoins(person);

            for (Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(trip.getTripElements());
                double coinsDelta = calculator.calculateCoinDelta(distances, person);
                personBalance += coinsDelta;
            }

            // Only count positive balances as excess
            if (personBalance > 0.0) {
                totalExcessCoins += personBalance;
            }
        }

        return totalExcessCoins;
    }

    /**
     * Calculates the total shortage coins (negative wallet balances converted to positive values) at the end of the current iteration.
     * This represents the total coins deficit for persons who have fewer coins than they started with.
     * 
     * @return The total shortage coins across all persons with negative balances (as positive values)
     */
    public double calculateShortageCoins() {
        double totalShortageCoins = 0.0;

        for (Person person : population.getPersons().values()) {
            double personBalance = getInitialCoins(person);

            for (Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(trip.getTripElements());
                double coinsDelta = calculator.calculateCoinDelta(distances, person);
                personBalance += coinsDelta;
            }

            // Only count negative balances as shortage (convert to positive)
            if (personBalance < 0.0) {
                totalShortageCoins += Math.abs(personBalance);
            }
        }

        return totalShortageCoins;
    }

    /**
     * Calculates the total coins available in the market system.
     * This includes the initially allocated coins to all persons plus all coins generated 
     * through sustainable transport modes (walking, biking, etc.) throughout the simulation.
     * 
     * @return The total coins in the market (initial allocation + generated coins)
     */
    public double totalCoinsInTheMarket() {
        double totalCoins = 0.0;

        for (Person person : population.getPersons().values()) {
            // Add initial coins for this person
            totalCoins += getInitialCoins(person);

            // Add all positive coin deltas (generated coins) from trips
            for (Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(trip.getTripElements());
                double coinsDelta = calculator.calculateCoinDelta(distances, person);

                // Only add positive coin deltas (coins generated/earned)
                if (coinsDelta > 0.0) {
                    totalCoins += coinsDelta;
                }
            }
        }

        return totalCoins;
    }

    /**
     * Calculates the total coins spent across all non-ecological trips made by all persons.
     * This represents the sum of all coin charges from trips that cost coins.
     * 
     * @return The total coins spent (as positive value)
     */
    public double totalCoinsSpent() {
        double totalSpent = 0.0;

        for (Person person : population.getPersons().values()) {
            for (Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(trip.getTripElements());
                double coinsDelta = calculator.calculateCoinDelta(distances, person);

                // Only count negative coin deltas (costs), convert to positive
                if (coinsDelta < 0.0) {
                    totalSpent += Math.abs(coinsDelta);
                }
            }
        }

        return totalSpent;
    }

    /**
     * Calculates the total coins rewarded across all ecological trips made by all persons.
     * This represents the sum of all coin rewards from sustainable transport modes.
     * 
     * @return The total coins rewarded
     */
    public double totalCoinsRewarded() {
        double totalRewarded = 0.0;

        for (Person person : population.getPersons().values()) {
            for (Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(trip.getTripElements());
                double coinsDelta = calculator.calculateCoinDelta(distances, person);

                // Only count positive coin deltas (rewards)
                if (coinsDelta > 0.0) {
                    totalRewarded += coinsDelta;
                }
            }
        }

        return totalRewarded;
    }

    /**
     * Calculates the total coins allocated initially to all persons.
     * 
     * @return The total initially allocated coins across all persons
     */
    public double totalCoinsAllocated() {
        double totalAllocated = 0.0;

        for (Person person : population.getPersons().values()) {
            totalAllocated += getInitialCoins(person);
        }

        return totalAllocated;
    }

    /**
     * Gets the number of persons in the population.
     * 
     * @return The total number of persons
     */
    public int numberOfPersons() {
        return population.getPersons().size();
    }

    /**
     * Gets the average number of coins allocated per person.
     * For UNIFORM allocation, this equals initialCoins_per_person.
     * For heterogeneous allocations (INCOME, ACCESSIBILITY, etc.), this is the actual average.
     * 
     * @return The average coins allocated per person
     */
    public double avgCoinsAllocatedPerPerson() {
        double totalAllocated = totalCoinsAllocated();
        int numPersons = population.getPersons().size();

        // For AGE_EXEMPT, calculate average only over eligible agents
        if (parameters.allocationScheme == MobilityCoinsParameters.AllocationScheme.AGE_EXEMPT) {
            int eligibleCount = 0;
            for (Person person : population.getPersons().values()) {
                if (!AgeExemptAllocationCalculator.isExempt(person)) {
                    eligibleCount++;
                }
            }
            return (eligibleCount > 0) ? totalAllocated / eligibleCount : 0.0;
        }

        // For other schemes, calculate over all persons
        return (numPersons > 0) ? totalAllocated / numPersons : 0.0;
    }

    /*
    private double calculateMarketPrice(double globalBalance) {
        // update rule
        if (globalBalance > parameters.targetCoins) {
            // too many coins used, make more expensive
            return marketPrice_EUR_per_coin + parameters.marketPriceUpdate;
        } else {
            // not enough coins used, make less expensive
            return marketPrice_EUR_per_coin - parameters.marketPriceUpdate;
        }
    }
    */

    /**
     * Calculate PID output using NORMALIZED error.
     * 
     * @param error The market imbalance in coins (shortage minus excess)
     * @return Price adjustment in EUR
     */
    private double calculatePIDOutput(double error) {
        // Get target for normalization - use EMISSION-BASED target (not old parameters.targetCoins)
        // targetEmissionCoins is calculated from baseline and represents the actual coin budget
        double target = this.targetEmissionCoins;
        if (target <= 0) {
            // Fallback: use parameters.targetCoins if emission target not calculated
            target = parameters.targetCoins;
        }
        if (target <= 0) {
            // Ultimate fallback: estimate from population
            target = parameters.initialCoins_per_person * population.getPersons().size() 
                     * (1.0 - parameters.reductionPercentage);
        }
        if (target <= 0) {
            target = 1000000; // Safety fallback
        }
        
        // Calculate NORMALIZED error (percentage of target)
        double normalizedError = error / target;
        
        // Log normalized error for debugging
        logger.info("P-Controller: error={:.0f} coins, target={:.0f}, normalizedError={:.2f}%", 
                   error, target, normalizedError * 100);
        
        // === PROPORTIONAL RESPONSE ===
        // Three-tier gain selection based on emission reduction pressure.
        // Low-pressure scenarios (low cost_coins_per_gco2) need higher gain
        // to compensate for reduced agent responsiveness to price changes.
        double kp;
        if (parameters.cost_coins_per_gco2 >= COST_THRESHOLD_HIGH) {
            kp = Kp_TMC_HIGH;  // High pressure: 0.25
        } else if (parameters.cost_coins_per_gco2 >= COST_THRESHOLD_LOW) {
            kp = Kp_TMC_MID;   // Medium pressure: 0.5
        } else {
            kp = Kp_TMC_LOW;   // Low pressure: 1.0
        }

        // Base response: kp * normalizedError
        // high-pressure (>=0.008): 10% error → 0.025 EUR
        // mid-pressure (0.003-0.008): 10% error → 0.05 EUR
        // low-pressure (<0.003): 10% error → 0.10 EUR
        double baseOutput = kp * normalizedError;
        
        // === ADAPTIVE DAMPING FOR REPLANNING INERTIA ===
        // 5% replanning creates ~20-iteration lag for full system response.
        // Need conservative damping to prevent overshoot from delayed feedback.
        // Pressure-dependent damping threshold: low-pressure scenarios
        // (cost_coins_per_gco2 < 0.003) use a tighter 1.5% threshold to avoid permanent
        // damping at their naturally small error levels; mid/high-pressure keep 5%.
        double dampingFactor = 1.0;
        double dampingThreshold = (parameters.cost_coins_per_gco2 < COST_THRESHOLD_LOW) ? 0.015 : 0.05;
        if (Math.abs(normalizedError) < dampingThreshold) {
            dampingFactor = 0.5 + 0.5 * (Math.abs(normalizedError) / dampingThreshold);
        }

        double pidOutput = baseOutput * dampingFactor;
        
        // === HARD SAFETY LIMITS ===
        // Maximum price change per iteration (EUR per coin)
        double maxStep = 0.10;
        pidOutput = Math.max(-maxStep, Math.min(maxStep, pidOutput));
        
        // Prevent negative prices
        if (marketPrice_EUR_per_coin + pidOutput < 0.0) {
            pidOutput = -marketPrice_EUR_per_coin;
        }
        
        // Log output
        logger.info("P-Controller (TMC) output: {:.4f} EUR (damping: {:.2f}, Kp: {:.1f})",
                   pidOutput, dampingFactor, kp);
        
        // Store error for next iteration
        lastError = error;
        
        return pidOutput;
    }
    
    /**
     * Update the market price for the tradable-credit (TMC) scheme.
     *
     * The price is steered by a proportional controller (see {@link #calculatePIDOutput})
     * so that the coin market clears: when more coins are demanded (shortage) than offered
     * (excess), the price rises, which makes coin-consuming modes (car, PT) less attractive
     * in mode choice, and vice versa.
     */
    private void updateMarketPrice(double error) {
        // Calculate the proportional controller output (EUR per coin)
        double pidOutput = calculatePIDOutput(error);

        // Calculate new price based on current price and controller adjustment
        double updatedMarketPrice = marketPrice_EUR_per_coin + pidOutput;

        // Ensure price stays non-negative (no upper bound - let market find equilibrium)
        // Upper bound removed: edge cases with high charges need high prices to converge
        // Lower bound 0.0: price = 0 indicates excess coins, system has no effect
        // Termination is based on mode share stability, not price level
        updatedMarketPrice = Math.max(0.0, updatedMarketPrice);

        // Track the price adjustment
        lastPriceAdjustment = updatedMarketPrice - marketPrice_EUR_per_coin;

        // Update market price
        marketPrice_EUR_per_coin = updatedMarketPrice;
    }

    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        // calculate a balance of coins in the system
        double globalBalance = calculateBalance();
        // calculate the total amount of coins used/earned across all persons in the current iteration
        double totalCoinsCharged = calculateUsedCoins();
        // calculate excess and shortage coins
        double excessCoins = calculateExcessCoins();
        double shortageCoins = calculateShortageCoins();
        double shortageMinusExcess = shortageCoins - excessCoins;
        // calculate total coins in the market
        double totalCoinsInMarket = totalCoinsInTheMarket();
        // calculate total coins spent and rewarded (incentive coins)
        double totalSpent = totalCoinsSpent();
        double totalRewarded = totalCoinsRewarded();  // This is incentive coins!
        // calculate allocation metrics
        double totalAllocated = totalCoinsAllocated();
        int numPersons = numberOfPersons();
        double avgCoinsPerPerson = avgCoinsAllocatedPerPerson();

        // Calculate eligible persons count (for AGE_EXEMPT: 18-65 year olds, for others: all persons)
        int numEligiblePersons = numPersons;
        if (parameters.allocationScheme == MobilityCoinsParameters.AllocationScheme.AGE_EXEMPT) {
            numEligiblePersons = 0;
            for (Person person : population.getPersons().values()) {
                if (!AgeExemptAllocationCalculator.isExempt(person)) {
                    numEligiblePersons++;
                }
            }
        }

        // Calculate average coins without exemption consideration
        // For AGE_EXEMPT: what average would be if all persons were eligible (uniform allocation)
        // For others: same as avgCoinsPerPerson
        double avgCoinsPerPersonWoExempt = avgCoinsPerPerson;
        if (parameters.allocationScheme == MobilityCoinsParameters.AllocationScheme.AGE_EXEMPT) {
            avgCoinsPerPersonWoExempt = (numPersons > 0) ? totalAllocated / numPersons : 0.0;
        }
        
        // =====================================================================
        // EMISSION-BASED DYNAMIC ALLOCATION
        // =====================================================================
        // The key insight: incentive coins (totalRewarded) add to the supply,
        // so we must reduce the allocation to maintain the emission target.
        //
        // Total effective supply = allocation + incentives = targetEmissionCoins (constant)
        // Therefore: allocation = targetEmissionCoins - incentives
        //
        // This is done AFTER calculating this iteration's metrics but BEFORE
        // the next iteration starts, so wallets are ready for replanning.
        // =====================================================================
        
        // Update dynamic allocation for NEXT iteration based on THIS iteration's incentives
        updateDynamicAllocation(totalRewarded);
        
        // Calculate baseline 100% coins (before reduction) - now use stored value
        double baselineCoins100Pct = (parameters.reductionPercentage < 1.0 && targetEmissionCoins > 0) 
            ? targetEmissionCoins / (1.0 - parameters.reductionPercentage) 
            : totalAllocated;
        
        // Revenue from excess coins at the current market price (reporting only)
        double excessRevenue = excessCoins * marketPrice_EUR_per_coin;

        // Calculate error (how far we are from target)
        // double error = totalCoinsCharged - parameters.targetCoins;
        // After DocSem: New logic below implemented to make rewards noticeable
        // double error = totalSpent - parameters.targetCoins;
        double error = shortageMinusExcess;
        // double error = -globalBalance;
        
        // Store the error for trip dropping strategy and termination criterion
        this.latestError = error;

        // Save the price USED during this iteration (before the controller update)
        double priceUsedThisIteration = marketPrice_EUR_per_coin;

        // Update market price using the proportional controller (TMC scheme)
        updateMarketPrice(error);
        
        // Now marketPrice_EUR_per_coin is the NEW price for next iteration
        double priceForNextIteration = marketPrice_EUR_per_coin;
        
        // Get and reset the dropped leisure trips counter
        int droppedLeisureTrips = getAndResetDroppedLeisureTripsCount();

        // Collect wallet data for all persons
        Map<Id<Person>, Double> initialWallets = new TreeMap<>();
        Map<Id<Person>, Double> finalWallets = new TreeMap<>();
        Map<Id<Person>, Person> persons = new TreeMap<>(population.getPersons());
        for (Person person : population.getPersons().values()) {
            double personBalance = getInitialCoins(person);
            double totalCoinsDelta = 0.0;
            
            // Calculate total coins delta for this person
            for (Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(trip.getTripElements());
                double coinsDelta = calculator.calculateCoinDelta(distances, person);
                totalCoinsDelta += coinsDelta;
            }
            
            // Store initial and final wallet balances
            initialWallets.put(person.getId(), personBalance);
            finalWallets.put(person.getId(), personBalance + totalCoinsDelta);
        }

        // Write wallet data with initial and final balances (gzipped)
        walletWriter.writeWallets(initialWallets, finalWallets, persons, event.getIteration());

        // track prices
        writer.writeMarketPrice(
                new Entry(event.getIteration(), 
                         // Simulation identification
                         parameters.allocationScheme.name(),
                         parameters.reductionPercentage,
                         parameters.tripDroppingEnabled,
                         parameters.initialMarketPrice_EUR_per_coin,
                         // Configuration parameters
                         parameters.emissions_gco2_per_km_car,
                         parameters.emissions_gco2_per_km_transit,
                         parameters.cost_coins_per_gco2,
                         parameters.incentive_coins_per_km_bicycle,
                         parameters.incentive_coins_per_km_walking,
                         // Population metrics
                         numPersons, numEligiblePersons, avgCoinsPerPerson, avgCoinsPerPersonWoExempt, baselineCoins100Pct, totalAllocated,
                         // Market metrics
                         excessCoins, excessRevenue, shortageCoins, shortageMinusExcess, globalBalance, error,
                         totalCoinsInMarket, totalCoinsCharged, totalSpent, totalRewarded,
                         // Emission-based dynamic allocation metrics
                         targetEmissionCoins, currentEffectiveAllocation,
                         parameters.targetCoins, 
                         // Market price tracking
                         // market_price: price USED during this iteration
                         // calculated/smoothed: NEW price for next iteration (after the controller update)
                         priceUsedThisIteration, priceForNextIteration, priceForNextIteration, lastPriceAdjustment,
                         droppedLeisureTrips));
    }

    static public double getInitialCoins(Person person) {
        return (Double) person.getAttributes().getAttribute(WALLET_ATTRIBUTE);
    }
}
