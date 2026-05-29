package org.eqasim.core.simulation.policies.impl.mobility_coins;

import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsCalculator;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsMarket;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsParameters;
import org.eqasim.core.simulation.policies.impl.mobility_coins.strategies.RemoveExpensiveTripsStrategy;
import org.matsim.core.controler.AbstractModule;

import com.google.inject.Provider;
import com.google.inject.Singleton;

public class MobilityCoinsStrategyModule extends AbstractModule {
    
    @Override
    public void install() {
        // Register the strategy
        // The strategy checks tripDroppingEnabled internally - if false, it's a no-op
        addPlanStrategyBinding("RemoveExpensiveTrips").toProvider(RemoveExpensiveTripsStrategyProvider.class);
    }

    @Singleton
    private static class RemoveExpensiveTripsStrategyProvider implements Provider<RemoveExpensiveTripsStrategy> {
        private final MobilityCoinsMarket market;
        private final MobilityCoinsCalculator calculator;
        private final MobilityCoinsParameters parameters;

        @com.google.inject.Inject
        public RemoveExpensiveTripsStrategyProvider(MobilityCoinsMarket market, MobilityCoinsCalculator calculator,
                                                     MobilityCoinsParameters parameters) {
            this.market = market;
            this.calculator = calculator;
            this.parameters = parameters;
        }

        @Override
        public RemoveExpensiveTripsStrategy get() {
            // 100% drop probability for agents in debt who are selected for this strategy
            // The strategy weight (2%) already controls how many agents are considered
            // Note: The strategy checks tripDroppingEnabled internally - if false, it's a no-op
            return new RemoveExpensiveTripsStrategy(market, calculator, parameters, 1.0); 
        }
    }
}
