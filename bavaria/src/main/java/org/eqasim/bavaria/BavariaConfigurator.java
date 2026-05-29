package org.eqasim.bavaria;

import org.eqasim.core.simulation.EqasimConfigurator;
import org.eqasim.bavaria.mode_choice.BavariaModeChoiceModule;
import org.eqasim.bavaria.simulation.BicycleAdjustmentListener;
import org.matsim.core.config.CommandLine;
import org.matsim.core.controler.Controler;

public class BavariaConfigurator extends EqasimConfigurator {
	public BavariaConfigurator(CommandLine cmd) {
		super(cmd);

		registerModule(new BavariaModeChoiceModule(cmd));
	}

	@Override
	public void configureController(Controler controller) {
		super.configureController(controller);
		
		// Install the bicycle adjustment listener for congestion modeling
		BicycleAdjustmentListener.install(controller);
	}
}
