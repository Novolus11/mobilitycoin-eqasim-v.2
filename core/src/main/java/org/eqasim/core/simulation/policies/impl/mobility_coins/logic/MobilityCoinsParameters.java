package org.eqasim.core.simulation.policies.impl.mobility_coins.logic;

import org.eqasim.core.simulation.mode_choice.ParameterDefinition;

/**
 * All tunable parameters of the MobilityCoin scheme.
 *
 * <p>Every public field here can be overridden from the command line using the {@code moco:}
 * prefix, e.g. {@code --moco:cost_coins_per_gco2 0.001 --moco:allocationScheme INCOME}
 * (see {@code ParameterDefinition.applyCommandLine} in
 * {@code MobilityCoinsPolicyExtension}). Defaults below were calibrated for the Bavaria
 * scenario; change them per experiment rather than editing the code.
 *
 * <p>The most important knobs for a student:
 * <ul>
 *   <li>{@link #allocationScheme} - how the (fixed) coin budget is split between agents.</li>
 *   <li>{@link #cost_coins_per_gco2} - how many coins one gram of CO2 costs (the "price" of
 *       emitting); 0 effectively switches the scheme off.</li>
 *   <li>{@link #reductionPercentage} - target emission reduction vs. the baseline.</li>
 *   <li>{@link #incentive_coins_per_km_bicycle}/{@link #incentive_coins_per_km_walking} -
 *       coins earned for active modes.</li>
 * </ul>
 */
public class MobilityCoinsParameters implements ParameterDefinition {

    /**
     * Allocation scheme determines how the initial coins are distributed across agents.
     * This is the "heterogeneous coin allocation" knob of the MobilityCoin system: the
     * total coin budget is fixed (derived from the emission target), but how it is split
     * between agents differs per scheme.
     */
    public enum AllocationScheme {
        UNIFORM,          // All agents receive the same number of coins (initialCoins_per_person)
        GRANDFATHERING,   // Coins allocated based on baseline emissions (high emitters get more)
        INCOME,           // Coins allocated inversely to income (low income gets more)
        ACCESSIBILITY,    // Coins allocated based on accessibility (low accessibility gets more)
        AGE_EXEMPT        // Young (<18) and elderly (>65) are exempt; working-age carry the burden
    }

    // Allocation scheme to use (default is UNIFORM for backward compatibility)
    public AllocationScheme allocationScheme = AllocationScheme.UNIFORM;
    
    // assumed CO2eq for one km of driving the car
    public double emissions_gco2_per_km_car = 147.0;

    // assumed CO2eq for one km of riding in transit
    public double emissions_gco2_per_km_transit = 68.0;

    // Equivalent of one coin in CO2eq
    public double cost_coins_per_gco2 = 0.000; // 0.001;

    // incentive in coins for riding one km the bicycle
    public double incentive_coins_per_km_bicycle = 0.00; // 0.01; // Dann 0.01, dann 0.1, dann 1

    // incentive in coins for walking one km
    public double incentive_coins_per_km_walking = 0.00; // 0.01; // Dann 0.01, dann 0.1, dann 1

    // marginal utility for coin losses (Term 1: absolute coin cost × market price)
    public double beta_loss_u_per_coin = 0.103; // 0.063; 0.56;

    // marginal utility for coin gains (Term 1: only active when incentives > 0)
    public double beta_gain_u_per_coin = 0.731; // 0.286; 6.14;

    // M-term betas (Term 2: deviation from per-trip budget × market price)
    // beta_loss_M > beta_gain_M reflects classical prospect-theory loss aversion
    public double beta_gain_M = 1.0;
    public double beta_loss_M = 2.0;


    // blending factor when updating market price
    public double marketPriceSmoothing = 0.2; // HÖRL value 0.1

    // Initial market price in EUR per coin
    // Starting LOW (0.1) ensures the PID controller always converges in one direction (upward)
    // This provides more predictable convergence behavior across all scenarios
    // The PID will increase the price until supply/demand equilibrium is reached
    public double initialMarketPrice_EUR_per_coin = 0.1;

    // target coins volume
    public double targetCoins = 2329827.48;     // 224649.59; // 2203819.75; // <- 2.75 * population // for 1% population -> 224649.59; // 265000.0; // * 0.001;

    // update coins delta
    public double marketPriceUpdate = 0.1;

    // initial coins per person
    public double initialCoins_per_person = 2.81832; //<- 2.9 reduced by incentive coins in 0.01 incentive case // 2.9;// 2.75;
    
    // === Parameters for Individual Allocation Schemes ===
    
    // Path to baseline results directory for computing total baseline emissions
    // Used for GRANDFATHERING allocation and total coins calculation
    public String baselineResultsPath = "";
    
    // Reduction percentage for total coins (0.01 = 1%, 0.10 = 10%)
    // Total available coins = baseline emissions * (1 - reductionPercentage) * cost_coins_per_gco2
    public double reductionPercentage = 0.01;
    
    // === Parameters for AGE_EXEMPT allocation ===
    // Minimum age for MobilityCoin system participation (agents below are exempt)
    public int ageExemptMinAge = 18;
    // Maximum age for MobilityCoin system participation (agents above are exempt)
    public int ageExemptMaxAge = 65;
    
    // === Parameters for INCOME allocation ===
    // Income allocation uses inverse proportionality: low income gets more coins
    // The formula: coins_i = totalCoins * (1 / income_i)^progressiveFactor / sum(...)
    // Progressive factor controls redistribution intensity:
    //   0.0 = uniform (no redistribution)
    //   0.5 = moderate (≈3:1 ratio low vs high income) - RECOMMENDED
    //   1.0 = full inverse proportionality (10:1 ratio - too extreme)
    public double incomeProgressiveFactor = 0.5;
    
    // === Parameters for ACCESSIBILITY allocation ===
    // Accessibility allocation uses inverse proportionality: low accessibility gets more coins
    // Progressive factor controls redistribution intensity:
    //   0.5 = moderate redistribution toward low accessibility areas - RECOMMENDED
    //   1.0 = full inverse proportionality (may be too extreme)
    public double accessibilityProgressiveFactor = 0.5;
    
    // Path to pre-computed accessibility CSV file (optional)
    // Format: person_id;accessibility (or person_id;logsum or person_id;emu)
    // If empty, accessibility is computed from person attributes (carAvailability, ptSubscription, etc.)
    // Pre-computing accessibility is RECOMMENDED as logsum calculation is expensive
    public String accessibilityFilePath = "";
    
    // === Parameters for Trip Dropping (Elastic Demand) ===
    // Enable/disable trip dropping strategy (RemoveExpensiveTrips)
    // When enabled, agents with negative coin balance may drop discretionary trips
    public boolean tripDroppingEnabled = false;
    
    // Weight of trip dropping strategy in the replanning mechanism (0.0 to 1.0)
    // This controls what fraction of agents are selected for trip dropping each iteration
    // Default: 0.02 = 2% of agents are considered for trip dropping
    public double tripDroppingWeight = 0.02;
}
