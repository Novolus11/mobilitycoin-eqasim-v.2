package org.eqasim.core.simulation.termination.market_error;

import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsMarket;
import org.eqasim.core.simulation.termination.TerminationIndicatorSupplier;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Indicator that tracks the MobilityCoin market error as percentage of target.
 * 
 * Academic rationale for welfare computation:
 * - Error is measured against targetEmissionCoins (the market's equilibrium point)
 * - This gives a stable, economically meaningful reference
 * - For welfare analysis, we need supply ≈ demand at the margin
 * - A 1% error means the market is within 1% of equilibrium
 */
@Singleton
public class MarketErrorIndicator implements TerminationIndicatorSupplier, IterationEndsListener {
    
    private final MobilityCoinsMarket market;
    private double currentError = Double.NaN;
    private double targetCoins = Double.NaN;
    
    @Inject
    public MarketErrorIndicator(MobilityCoinsMarket market) {
        this.market = market;
    }
    
    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        // Get the latest error and target from the market
        currentError = market.getLatestError();
        targetCoins = market.getTargetEmissionCoins();
    }
    
    @Override
    public double getValue() {
        if (Double.isNaN(targetCoins) || targetCoins <= 0.0) {
            // No target yet, return NaN
            return Double.NaN;
        }
        
        // Guard against iteration 0 where error hasn't been calculated yet
        if (Double.isNaN(currentError)) {
            return Double.NaN;
        }
        
        // Return error as percentage of TARGET coins (not initial error)
        // This is the academically rigorous measure:
        // - 0.01 = 1% of target = market within 1% of equilibrium
        // - 0.005 = 0.5% = very tight convergence for welfare analysis
        return Math.abs(currentError) / targetCoins;
    }
    
    /**
     * Get the SIGNED error value (preserves direction for oscillation detection).
     * Positive = shortage exceeds excess, Negative = excess exceeds shortage
     * 
     * @return Signed market error as fraction of target coins
     */
    public double getSignedValue() {
        if (Double.isNaN(targetCoins) || targetCoins <= 0.0) {
            return Double.NaN;
        }
        
        if (Double.isNaN(currentError)) {
            return Double.NaN;
        }
        
        // Return SIGNED error (preserves direction for oscillation detection)
        return currentError / targetCoins;
    }
}

