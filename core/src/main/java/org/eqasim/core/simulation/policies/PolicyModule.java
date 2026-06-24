package org.eqasim.core.simulation.policies;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eqasim.core.simulation.policies.config.PoliciesConfigGroup;
import org.eqasim.core.simulation.policies.config.PolicyConfigGroup;
import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsPolicyExtension;
import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsPolicyFactory;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsRoutingPenalty;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsUtilityPenalty;
import org.eqasim.core.simulation.policies.routing.RoutingPenalty;
import org.eqasim.core.simulation.policies.routing.SumRoutingPenalty;
import org.eqasim.core.simulation.policies.utility.SumPenalty;
import org.eqasim.core.simulation.policies.utility.UtilityPenalty;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.CommandLine;
import org.matsim.core.controler.AbstractModule;

import com.google.common.base.Verify;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.MapBinder;

public class PolicyModule extends AbstractModule {
	private final CommandLine cmd;

	public PolicyModule(CommandLine cmd) {
		this.cmd = cmd;
	}

	@Override
	public void install() {
		// This trimmed-down build only ships the MobilityCoin policy. The eqasim policy
		// framework still supports multiple policies side by side via the MapBinder below.
		install(new MobilityCoinsPolicyExtension(cmd));

		var policyBinder = MapBinder.newMapBinder(binder(), String.class, PolicyFactory.class);
		policyBinder.addBinding(MobilityCoinsPolicyFactory.POLICY_NAME).to(MobilityCoinsPolicyFactory.class);
	}

	@Provides
	@Singleton
	// MoCo-Erweiterung: zwei neue Parameter ergänzt (MobilityCoinsUtilityPenalty,
	// MobilityCoinsRoutingPenalty) fuer den Fallback weiter unten.
	// Originalzeile war: Map<String, Policy> providePolicies(Map<String, PolicyFactory> factories, Population population) {
	Map<String, Policy> providePolicies(Map<String, PolicyFactory> factories, Population population,
			MobilityCoinsUtilityPenalty utilityPenalty, MobilityCoinsRoutingPenalty routingPenalty) {
		PoliciesConfigGroup policyConfig = PoliciesConfigGroup.get(getConfig());
		Map<String, Policy> policies = new HashMap<>();

		// MoCo-Erweiterung: fruehes return auskommentiert — ohne Fallback unten wuerde
		// die Policy nie aktiv, weil EqasimConfigurator.updateConfig() den PoliciesConfigGroup-
		// Inhalt aus der XML beim Ersetzen des generischen ConfigGroup-Objekts verliert.
		// if (policyConfig == null) {
		// 	return policies;
		// }

		Set<String> names = new HashSet<>();

		if (policyConfig != null) {
			for (var collection : policyConfig.getParameterSets().values()) {
				for (var raw : collection) {
					PolicyConfigGroup policy = (PolicyConfigGroup) raw;

					if (policy.active) {
						Verify.verify(policy.policyName != null && policy.policyName.length() > 0,
								"Policy names must be set");

						if (!names.add(policy.policyName)) {
							throw new IllegalStateException("Duplicate policy name: " + policy.policyName);
						}

						PolicyPersonFilter filter = AttributePersonFilter.create(population, policy);

						policies.put(policy.policyName,
								factories.get(policy.getName()).createPolicy(policy.policyName, filter));
					}
				}
			}
		}

		// MoCo-Erweiterung: Fallback — falls mobilityCoins nicht ueber die XML-Config
		// aktiviert wurde (policyConfig null oder Parameterset fehlt), wird die Policy
		// direkt ueber die bereits als Guice-Singletons vorhandenen Penalty-Instanzen
		// erzeugt. So ist MobilityCoinsUtilityPenalty immer im SumPenalty enthalten.
		if (!policies.containsKey(MobilityCoinsPolicyFactory.POLICY_NAME)) {
			policies.put(MobilityCoinsPolicyFactory.POLICY_NAME,
					new DefaultPolicy(routingPenalty, utilityPenalty));
		}

		return policies;
	}

	@Provides
	UtilityPenalty provideUtilityPenalty(Map<String, Policy> policies) {
		List<UtilityPenalty> penalties = new LinkedList<>();

		for (Policy policy : policies.values()) {
			UtilityPenalty penalty = policy.getUtilityPenalty();

			if (penalty != null) {
				penalties.add(penalty);
			}
		}

		return new SumPenalty(penalties);
	}

	@Provides
	RoutingPenalty provideRoutingPenalty(Map<String, Policy> policies) {
		List<RoutingPenalty> penalties = new LinkedList<>();

		for (Policy policy : policies.values()) {
			RoutingPenalty penalty = policy.getRoutingPenalty();

			if (penalty != null) {
				penalties.add(penalty);
			}
		}

		return new SumRoutingPenalty(penalties);
	}
}
