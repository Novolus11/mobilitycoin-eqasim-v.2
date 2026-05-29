package org.eqasim.core.simulation.analysis;

import org.eqasim.core.analysis.DefaultPersonAnalysisFilter;
import org.eqasim.core.analysis.PersonAnalysisFilter;
import org.eqasim.core.analysis.activities.ActivityListener;
import org.eqasim.core.analysis.activities.EnrichedActivityListener;
import org.eqasim.core.analysis.legs.LegListener;
import org.eqasim.core.analysis.pt.PublicTransportLegListener;
import org.eqasim.core.analysis.trips.TripListener;
import org.eqasim.core.analysis.trips.EnrichedTripListener;
import java.util.Optional;
import org.eqasim.core.components.travel_time.TravelTimeRecorder;
import org.eqasim.core.scenario.cutter.network.RoadNetwork;
import org.eqasim.core.simulation.analysis.stuck.StuckAnalysisModule;
import org.eqasim.core.simulation.modes.drt.analysis.DrtAnalysisModule;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.router.RoutingModeMainModeIdentifier;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.api.core.v01.population.Population;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsCalculator;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsMarket;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsParameters;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.Injector;

public class EqasimAnalysisModule extends AbstractModule {
	@Override
	public void install() {
		addControlerListenerBinding().to(AnalysisOutputListener.class);
		addControlerListenerBinding().to(LinkFlowMocoAnalysisListener.class);
		addControlerListenerBinding().to(ResultMetricsListener.class);

		if (getConfig().getModules().containsKey(MultiModeDrtConfigGroup.GROUP_NAME)) {
			install(new DrtAnalysisModule());
		} else {
			// Would be better if there was a way to add the module above as an overriding
			// module from this method.
			// That way we could simply bind the two classes below before the if clause
			bind(DefaultPersonAnalysisFilter.class);
			bind(PersonAnalysisFilter.class).to(DefaultPersonAnalysisFilter.class);
		}

		install(new StuckAnalysisModule());
		
		bind(AnalysisMainModeIdentifier.class).toInstance(new RoutingModeMainModeIdentifier());
	}

	@Provides
	@Singleton
	public TripListener provideTripListener(Network network, PersonAnalysisFilter personFilter) {
		return new TripListener(network, personFilter);
	}

	@Provides
	@Singleton
	public EnrichedTripListener provideEnrichedTripListener(Network network, Population population, PersonAnalysisFilter personFilter, Injector injector) {
		// Try to get mobility coins components, but make them optional
		MobilityCoinsMarket market = null;
		MobilityCoinsCalculator calculator = null;
		
		try {
			market = injector.getInstance(MobilityCoinsMarket.class);
			calculator = injector.getInstance(MobilityCoinsCalculator.class);
		} catch (Exception e) {
			// Mobility coins not available, use null values which will default to 0
		}
		
		return new EnrichedTripListener(network, population, personFilter, 
			market != null ? Optional.of(market) : Optional.empty(),
			calculator != null ? Optional.of(calculator) : Optional.empty());
	}

	@Provides
	@Singleton
	public LegListener provideLegListener(Network network, PersonAnalysisFilter personFilter) {
		return new LegListener(network, personFilter);
	}

	@Provides
	@Singleton
	public PublicTransportLegListener providePublicTransportListener(Network network, TransitSchedule schedule,
			PersonAnalysisFilter personFilter) {
		return new PublicTransportLegListener(schedule);
	}
	
	@Provides
	@Singleton
	public ActivityListener provideActivityListener(PersonAnalysisFilter personFilter) {
		return new ActivityListener(personFilter);
	}

	@Provides
	@Singleton
	public EnrichedActivityListener provideEnrichedActivityListener(PersonAnalysisFilter personFilter, 
			Population population, Injector injector) {
		// Try to get mobility coins components, but make them optional
		MobilityCoinsMarket market = null;
		MobilityCoinsCalculator calculator = null;
		
		try {
			market = injector.getInstance(MobilityCoinsMarket.class);
			calculator = injector.getInstance(MobilityCoinsCalculator.class);
		} catch (Exception e) {
			// Mobility coins not available, use null values which will default to 0
		}
		
		return new EnrichedActivityListener(personFilter, market, calculator, population);
	}

	@Provides
	@Singleton
	public TravelTimeRecorder travelTimeRecorder(Network network, Config config) {
		// THis code was copy pasted from QSim::initSimTimer
		double startTime = config.qsim().getStartTime().orElse(0);
		double stopTime = config.qsim().getEndTime().orElse(Double.MAX_VALUE);
		if (stopTime == 0) {
			stopTime = Double.MAX_VALUE;
		}
		return new TravelTimeRecorder(new RoadNetwork(network), startTime, stopTime, 600);
	}

	@Provides
	@Singleton
	public LinkFlowMocoAnalysisListener provideLinkFlowMocoAnalysisListener(
			org.eqasim.core.components.config.EqasimConfigGroup config,
			org.matsim.core.controler.OutputDirectoryHierarchy outputDirectory,
			Network network, 
			org.eqasim.core.simulation.termination.TerminationState terminationState,
			Injector injector) {
		// Try to get mobility coins parameters, but provide default if not available
		MobilityCoinsParameters mobilityCoinsParameters = null;
		
		try {
			mobilityCoinsParameters = injector.getInstance(MobilityCoinsParameters.class);
		} catch (Exception e) {
			// Mobility coins not available, use default parameters
			mobilityCoinsParameters = new MobilityCoinsParameters();
		}
		
		return new LinkFlowMocoAnalysisListener(config, outputDirectory, network, mobilityCoinsParameters, terminationState);
	}

	@Provides
	@Singleton
	public ResultMetricsListener provideResultMetricsListener(
			org.matsim.core.controler.OutputDirectoryHierarchy outputDirectory,
			Network network,
			org.eqasim.core.simulation.termination.TerminationState terminationState) {
		return new ResultMetricsListener(outputDirectory, network, terminationState);
	}
}
