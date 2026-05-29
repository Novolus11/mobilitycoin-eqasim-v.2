package org.eqasim.core.simulation.termination.market_error;

import org.eqasim.core.simulation.termination.EqasimTerminationModule;
import org.matsim.core.controler.AbstractModule;

import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * Module for market error-based termination criterion.
 * 
 * Configuration:
 * - threshold: 0.10 (10% of initial error)
 * - horizon: 5 iterations (average over last 5 iterations)
 * 
 * This ensures the market has converged to within 10% of the initial error
 * before terminating the simulation.
 */
public class TerminationMarketErrorModule extends AbstractModule {
    
    // Threshold: 1% of target coins (emission budget)
    // Academic rationale for welfare computation:
    // - Market must be within 1% of equilibrium (supply ≈ demand)
    // - For 37M target: max error = 370K coins
    // - Tighter than 2% to ensure price stability for welfare analysis
    // - Welfare impact: (0.01)² = 0.01% - negligible for surplus calculations
    // - Combined with oscillation detection ensures true convergence
    private static final double THRESHOLD = 0.01;
    
    // Horizon: Average over 5 iterations to smooth out oscillations
    private static final int HORIZON = 5;
    
    @Override
    public void install() {
        // Bind absolute error indicator (for display and basic check)
        EqasimTerminationModule.bindTerminationIndicator(binder(), "market_error")
            .to(MarketErrorIndicator.class);
        
        // Bind signed error indicator (for oscillation detection)
        EqasimTerminationModule.bindTerminationIndicator(binder(), "market_error_signed")
            .to(MarketErrorSignedIndicator.class);
        
        EqasimTerminationModule.bindTerminationCriterion(binder(), "market_error")
            .toProvider(() -> new MarketErrorCriterion(THRESHOLD, HORIZON));
        
        // Add the indicator as a controler listener
        addControlerListenerBinding().to(MarketErrorIndicator.class);
    }
    
    /**
     * Inner class to provide signed error values for oscillation detection.
     */
    static class MarketErrorSignedIndicator implements org.eqasim.core.simulation.termination.TerminationIndicatorSupplier {
        private final MarketErrorIndicator indicator;
        
        @com.google.inject.Inject
        public MarketErrorSignedIndicator(MarketErrorIndicator indicator) {
            this.indicator = indicator;
        }
        
        @Override
        public double getValue() {
            return indicator.getSignedValue();
        }
    }
    
    @Provides
    @Singleton
    public MarketErrorIndicator provideMarketErrorIndicator(
            org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsMarket market) {
        return new MarketErrorIndicator(market);
    }
}

