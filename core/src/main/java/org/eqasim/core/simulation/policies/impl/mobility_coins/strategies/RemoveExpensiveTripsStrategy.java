package org.eqasim.core.simulation.policies.impl.mobility_coins.strategies;

import java.util.List;
import java.util.Random;

import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsCalculator;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsParameters;
import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsDistances;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsMarket;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.gbl.MatsimRandom;
import org.matsim.core.replanning.PlanStrategy;
import org.matsim.core.replanning.ReplanningContext;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;

/**
 * A strategy that removes expensive discretionary trips (Leisure, Shopping) 
 * if the agent's daily plan is too expensive or they are running low on coins.
 * 
 * The strategy is controlled by the tripDroppingEnabled parameter:
 * - When tripDroppingEnabled = false: Strategy does nothing (no-op)
 * - When tripDroppingEnabled = true: Strategy actively drops trips
 * 
 * IMPORTANT: Trip dropping is modulated by market error to ensure convergence.
 * This implements "Error-Responsive Elastic Demand":
 * - When error > 0 (shortage > excess): Market needs coins freed → allow trip dropping
 * - When error ≤ 0 (excess ≥ shortage): Market has surplus → suppress trip dropping
 * 
 * This prevents the "runaway excess" problem where continuous trip dropping 
 * releases coins that accumulate faster than the PID can compensate.
 */
public class RemoveExpensiveTripsStrategy implements PlanStrategy {
    private final MobilityCoinsMarket market;
    private final MobilityCoinsCalculator calculator;
    private final MobilityCoinsParameters parameters;
    private final double baseDropProbability;
    private final Random random;
    
    // Reference error for normalizing the drop probability (coins)
    // Calibrated to the initial market imbalance at iteration 0
    // This ensures demand response is normalized relative to starting conditions
    private double referenceError = -1.0; // Will be initialized on first call

    public RemoveExpensiveTripsStrategy(MobilityCoinsMarket market, MobilityCoinsCalculator calculator, 
                                         MobilityCoinsParameters parameters, double dropProbability) {
        this.market = market;
        this.calculator = calculator;
        this.parameters = parameters;
        this.baseDropProbability = dropProbability;
        this.random = MatsimRandom.getLocalInstance();
    }
    
    /**
     * Calculate the effective drop probability based on market error.
     * Implements elastic demand: dropping decreases as market approaches equilibrium.
     * 
     * The reference error (ε_ref) is calibrated to the initial market imbalance
     * at iteration t=0, ensuring proportional convergence:
     * - At error = ε_ref (initial): P(drop) = P_base (100%)
     * - At error = ε_ref/2: P(drop) = P_base/2 (50%)
     * - At error → 0: P(drop) → 0 (smooth landing)
     * 
     * @return Probability in range [0, baseDropProbability]
     */
    private double getEffectiveDropProbability() {
        double error = market.getLatestError();
        
        // Initialize reference error on first call (iteration 0's error magnitude)
        // This calibrates the demand response to the system's starting conditions
        if (referenceError < 0) {
            referenceError = Math.max(1.0, Math.abs(error)); // Avoid division by zero
        }
        
        // Only allow trip dropping when there's actual scarcity (positive error)
        // When error <= 0, the market already has excess coins, so don't drop trips
        if (error <= 0) {
            return 0.0;
        }
        
        // Scale probability linearly with error magnitude relative to initial error
        // At error = referenceError, probability = baseDropProbability
        // At error = 0, probability = 0
        double scaleFactor = Math.min(1.0, error / referenceError);
        return baseDropProbability * scaleFactor;
    }

    // Removed @Override to silence linter since it seems to want the other method
    public void run(ReplanningContext replanningContext) {
    }

    // Implement the method required by GenericPlanStrategy<Plan, Person>
    // public void run(org.matsim.api.core.v01.replanning.PlanStrategyModule.ReplanningContext context) {
       // Older interface?
    // }

    // @Override not used here if we implement via overloading, but let's try to match the interface
    // The linter says "The method getPerson() is undefined for the type HasPlansAndId<Plan,Person>"
    // HasPlansAndId usually has getId() and getSelectedPlan().
    // But we need the Person object.
    // Usually HasPlansAndId IS the Person object (Person extends HasPlansAndId).
    
    public void run(org.matsim.api.core.v01.population.HasPlansAndId<Plan, Person> person) {
        // If trip dropping is disabled, do nothing (no-op)
        if (!parameters.tripDroppingEnabled) {
            return;
        }
        
        // If HasPlansAndId is the Person, we cast it
        if (person instanceof Person) {
            Person realPerson = (Person) person;
            Plan plan = realPerson.getSelectedPlan();
            processPlan(realPerson, plan);
        }
    }
    
    private void processPlan(Person person, Plan plan) {
        // 1. Identify discretionary trips (tours)
        List<Trip> trips = TripStructureUtils.getTrips(plan);
        
        if (trips.size() < 2) return;
        
        // 2. Calculate actual coin balance after all trips
        // Start with initial coins allocation
        double currentBalance = MobilityCoinsMarket.getInitialCoins(person);
        
        // Add/subtract coin deltas from all trips in the plan
        // Use two-argument version to respect AGE_EXEMPT allocation (exempt persons return 0)
        for (Trip trip : trips) {
            MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(trip.getTripElements());
            double coinDelta = calculator.calculateCoinDelta(distances, person);
            currentBalance += coinDelta; // coinDelta is negative for car/pt, positive for walk/bike
        }
        
        // 3. If balance is negative, consider dropping a discretionary trip
        // Use error-responsive elastic demand: drop probability scales with market error
        // This ensures trip dropping decreases as market approaches equilibrium
        if (currentBalance < 0) {
            double effectiveDropProbability = getEffectiveDropProbability();
            if (random.nextDouble() < effectiveDropProbability) {
                tryDropDiscretionaryTour(plan);
            }
        }
    }
    
    private void tryDropDiscretionaryTour(Plan plan) {
        List<Trip> trips = TripStructureUtils.getTrips(plan);
        
        for (int i = 0; i < trips.size() - 1; i++) {
            Trip outboundTrip = trips.get(i);
            Activity destAct = (Activity) outboundTrip.getDestinationActivity();
            
            if (isDiscretionary(destAct)) {
                Trip returnTrip = trips.get(i+1);
                Activity finalAct = (Activity) returnTrip.getDestinationActivity();
                
                if (isHome(finalAct)) {
                    removeSubtour(plan, outboundTrip, returnTrip, destAct);
                    return; 
                }
            }
        }
    }

    private boolean isDiscretionary(Activity act) {
        String type = act.getType().toLowerCase();
        return type.contains("leisure") || type.contains("shop") || type.contains("freizeit") || type.contains("einkauf");
    }
    
    private boolean isHome(Activity act) {
        String type = act.getType().toLowerCase();
        return type.contains("home") || type.contains("zuhause");
    }

    private void removeSubtour(Plan plan, Trip outbound, Trip inbound, Activity activityToRemove) {
        List<PlanElement> elements = plan.getPlanElements();
        
        // Find the indices of what we need to remove:
        // - All legs of the outbound trip
        // - The destination activity
        // - All legs of the inbound trip
        
        // Get start index: first element after the origin activity of outbound trip
        Activity originAct = outbound.getOriginActivity();
        int startIndex = elements.indexOf(originAct) + 1;
        
        // Get end index: last element before the destination activity of inbound trip (which is home)
        Activity homeAct = inbound.getDestinationActivity();
        int endIndex = elements.indexOf(homeAct) - 1;
        
        if (startIndex <= 0 || endIndex < startIndex || endIndex >= elements.size()) {
            return; // Invalid indices, don't modify
        }
        
        // Remove all elements from endIndex down to startIndex (reverse order to maintain indices)
        for (int i = endIndex; i >= startIndex; i--) {
            elements.remove(i);
        }
        
        // Track the dropped trip in the market
        market.incrementDroppedLeisureTrips();
    }

    @Override
    public void init(ReplanningContext replanningContext) {
    }

    @Override
    public void finish() {
    }
}
