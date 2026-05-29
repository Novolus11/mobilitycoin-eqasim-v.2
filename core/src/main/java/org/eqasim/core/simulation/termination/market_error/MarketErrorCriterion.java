package org.eqasim.core.simulation.termination.market_error;

import java.util.List;

import org.eqasim.core.simulation.termination.IterationData;
import org.eqasim.core.simulation.termination.TerminationCriterionCalculator;
import org.eqasim.core.simulation.termination.TerminationData;

/**
 * Termination criterion based on market error convergence.
 * 
 * Terminates when ALL conditions are met:
 * 1. Average absolute error over 'horizon' iterations below threshold
 * 2. No sign changes in error (no oscillation through zero)
 * 
 * Academic rationale for welfare computation:
 * - Error is measured against targetEmissionCoins (the emission budget)
 * - 1% threshold: Market within 1% of equilibrium (supply ≈ demand)
 * - Oscillation detection: Prevents false convergence during zero-crossing
 * - Achievable within ~80-100 iterations given 5% replanning inertia
 * - Welfare impact: (0.01)² = 0.01% - negligible for surplus calculations
 * 
 * Example: For 37M target coins, 1% threshold = max 370K coins error
 */
public class MarketErrorCriterion implements TerminationCriterionCalculator {
    
    private final double threshold;
    private final int horizon;
    
    public MarketErrorCriterion(double threshold, int horizon) {
        this.threshold = threshold;
        this.horizon = horizon;
    }
    
    @Override
    public double calculate(List<TerminationData> history, IterationData iteration) {
        // Need at least 'horizon' iterations to calculate
        if (history.size() < horizon) {
            return threshold; // Not converged yet
        }
        
        // Get last 'horizon' iterations
        List<TerminationData> recent = history.subList(
            Math.max(0, history.size() - horizon), 
            history.size()
        );
        
        // Calculate average absolute error over horizon
        double sumError = 0.0;
        int count = 0;
        
        for (TerminationData data : recent) {
            Double error = data.indicators.get("market_error");
            if (error != null && !Double.isNaN(error)) {
                sumError += error;
                count++;
            }
        }
        
        if (count == 0) {
            return threshold; // No valid data, not converged
        }
        
        double avgError = sumError / count;
        
        // Check for oscillations (sign changes in signed error)
        // This detects if market is crossing through zero rather than converging
        boolean hasPositive = false;
        boolean hasNegative = false;
        
        for (TerminationData data : recent) {
            Double signedError = data.indicators.get("market_error_signed");
            if (signedError != null && !Double.isNaN(signedError)) {
                if (signedError > 0.001) {  // Small threshold to avoid noise near zero
                    hasPositive = true;
                } else if (signedError < -0.001) {
                    hasNegative = true;
                }
            }
        }
        
        // If we have both positive and negative signed errors, market is oscillating
        if (hasPositive && hasNegative) {
            // Market is oscillating through zero - NOT converged
            // Return a value that indicates we're not converged
            return Math.max(threshold, avgError);
        }
        
        // No oscillation detected, use standard threshold check
        // Return how far we are from threshold
        // 0.0 means avgError <= threshold (converged)
        // Positive value means still above threshold
        return Math.max(0.0, avgError - threshold);
    }
}

