package org.eqasim.core.simulation.policies.impl.mobility_coins;

import java.io.File;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.simulation.mode_choice.AbstractEqasimExtension;
import org.eqasim.core.simulation.mode_choice.ParameterDefinition;
import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsCalculator;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsMarket;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsParameters;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsRoutingPenalty;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsUtilityPenalty;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.CommandLine;
import org.matsim.core.controler.OutputDirectoryHierarchy;

import com.google.inject.Provides;
import com.google.inject.Singleton;

public class MobilityCoinsPolicyExtension extends AbstractEqasimExtension {
	private static final Logger logger = LogManager.getLogger(MobilityCoinsPolicyExtension.class);
	private final CommandLine cmd;

	public MobilityCoinsPolicyExtension(CommandLine cmd) {
		this.cmd = cmd;
	}

	@Override
	protected void installEqasimExtension() {
		// Register MobilityCoinsMarket as controler listener for iteration ends
		// (this is where the market price is updated and metrics are logged each iteration)
		addControlerListenerBinding().to(MobilityCoinsMarket.class);

		// Install Strategy Module (for trip dropping via RemoveExpensiveTripsStrategy)
		install(new MobilityCoinsStrategyModule());

		logger.info("MobilityCoinsPolicyExtension installed with MobilityCoinsStrategyModule for trip dropping");
	}

	@Provides
	@Singleton
	MobilityCoinsParameters provideMobilityCoinsParameters() {
		MobilityCoinsParameters parameters = new MobilityCoinsParameters();
		ParameterDefinition.applyCommandLine("moco", cmd, parameters);
		return parameters;
	}

	@Provides
	@Singleton
	MobilityCoinsPolicyFactory provideMobilityCoinsPolicyFactory(
			MobilityCoinsRoutingPenalty routingPenalty, MobilityCoinsUtilityPenalty utilityPenalty) {
		return new MobilityCoinsPolicyFactory(getConfig(), routingPenalty, utilityPenalty);
	}

	@Provides
	@Singleton
	MobilityCoinsRoutingPenalty provideMobilityCoinsRoutingPenalty(ModeParameters modeParameters,
			MobilityCoinsMarket market, MobilityCoinsCalculator calculator) {
		return new MobilityCoinsRoutingPenalty(modeParameters, market, calculator);
	}

	@Provides
	@Singleton
	MobilityCoinsUtilityPenalty provideMobilityCoinsUtilityPenalty(MobilityCoinsMarket market,
			MobilityCoinsCalculator calculator, MobilityCoinsParameters parameters) {
		return new MobilityCoinsUtilityPenalty(market, calculator, parameters);
	}

	@Provides
	@Singleton
	MobilityCoinsCalculator provideMobilityCoinsCalculator(MobilityCoinsParameters parameters) {
		return new MobilityCoinsCalculator(parameters);
	}

	@Provides
	@Singleton
	MobilityCoinsMarket provideMobilityCoinsMarket(MobilityCoinsParameters parameters,
			MobilityCoinsCalculator calculator, Population population, MobilityCoinsWriter writer, MobilityCoinsWalletWriter walletWriter) {
		return new MobilityCoinsMarket(parameters, calculator, population, writer, walletWriter);
	}

	static public final String MARKET_OUTPUT_PATH = "mobility_coins.csv";
	static public final String WALLET_OUTPUT_PATH = "wallets.csv";

	@Provides
	@Singleton
	MobilityCoinsWriter provideMobilityCoinsWriter(OutputDirectoryHierarchy outputDirectoryHierarchy) {
		File outputPath = new File(outputDirectoryHierarchy.getOutputFilename(MARKET_OUTPUT_PATH));
		return new MobilityCoinsWriter(outputPath);
	}

	@Provides
	@Singleton
	MobilityCoinsWalletWriter provideMobilityCoinsWalletWriter(OutputDirectoryHierarchy outputDirectoryHierarchy) {
		File outputPath = new File(outputDirectoryHierarchy.getOutputFilename(WALLET_OUTPUT_PATH));
		return new MobilityCoinsWalletWriter(outputPath);
	}
}
