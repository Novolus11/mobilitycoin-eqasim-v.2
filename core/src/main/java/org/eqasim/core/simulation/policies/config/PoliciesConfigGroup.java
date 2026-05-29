package org.eqasim.core.simulation.policies.config;

import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsConfigGroup;
import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsPolicyFactory;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ReflectiveConfigGroup;

public class PoliciesConfigGroup extends ReflectiveConfigGroup {
	static public final String CONFIG_NAME = "eqasim:policies";

	public PoliciesConfigGroup() {
		super(CONFIG_NAME);
	}

	@Override
	public ConfigGroup createParameterSet(String type) {
		switch (type) {
			case MobilityCoinsPolicyFactory.POLICY_NAME:
				return new MobilityCoinsConfigGroup();
			default:
				throw new IllegalStateException("Unknown policy type: " + type);
		}
	}

	static public PoliciesConfigGroup get(Config config) {
		return (PoliciesConfigGroup) config.getModules().get(CONFIG_NAME);
	}
}
