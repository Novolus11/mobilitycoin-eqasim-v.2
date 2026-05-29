package org.eqasim.core.analysis.activities;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.List;

import org.eqasim.core.analysis.PersonAnalysisFilter;
import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsDistances;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsCalculator;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsMarket;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;

import com.google.common.base.Verify;

public class EnrichedActivityListener implements ActivityStartEventHandler, ActivityEndEventHandler, PersonDepartureEventHandler {
	final private Collection<EnrichedActivityItem> activities = new LinkedList<>();
	final private Map<Id<Person>, EnrichedActivityItem> ongoing = new HashMap<>();
	final private Map<Id<Person>, Integer> activityIndex = new HashMap<>();
	final private Map<Id<Person>, String> lastMode = new HashMap<>();

	final private PersonAnalysisFilter personFilter;
	final private MobilityCoinsMarket market;
	final private MobilityCoinsCalculator calculator;
	final private Population population;

	public EnrichedActivityListener(PersonAnalysisFilter personFilter, MobilityCoinsMarket market, 
			MobilityCoinsCalculator calculator, Population population) {
		this.personFilter = personFilter;
		this.market = market;
		this.calculator = calculator;
		this.population = population;
	}

	public Collection<EnrichedActivityItem> getActivityItems() {
		return activities;
	}

	@Override
	public void reset(int iteration) {
		activities.clear();
		ongoing.clear();
		activityIndex.clear();
		lastMode.clear();
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		if (personFilter.analyzePerson(event.getPersonId())) {
			lastMode.put(event.getPersonId(), event.getLegMode());
		}
	}

	@Override
	public void handleEvent(ActivityStartEvent event) {
		if (personFilter.analyzePerson(event.getPersonId())) {
			if (!TripStructureUtils.isStageActivityType(event.getActType())) {
				int personActivityIndex = Objects.requireNonNull(activityIndex.get(event.getPersonId())) + 1;
				activityIndex.put(event.getPersonId(), personActivityIndex);

				// Calculate mobility coin information
				String mode = lastMode.getOrDefault(event.getPersonId(), "walk");
				double currentMarketPrice = market != null ? market.getMarketPrice_EUR_per_coin() : 0.0;
				double walletAfter = getPersonWallet(event.getPersonId());
				
				// Calculate coins charged for this trip
				double mobilityCoinsCharged = calculateMobilityCoinsCharged(event.getPersonId(), mode);
				double walletBefore = walletAfter + mobilityCoinsCharged;

				// Create enriched activity item
				EnrichedActivityItem activity = new EnrichedActivityItem(event.getPersonId(), personActivityIndex, 
						event.getActType(), event.getTime(), Double.POSITIVE_INFINITY, 
						event.getCoord().getX(), event.getCoord().getY(), event.getFacilityId(), event.getLinkId(),
						mode, walletBefore, walletAfter, mobilityCoinsCharged, currentMarketPrice);

				activities.add(activity);
				ongoing.put(event.getPersonId(), activity);
			}
		}
	}

	@Override
	public void handleEvent(ActivityEndEvent event) {
		if (personFilter.analyzePerson(event.getPersonId())) {
			if (!TripStructureUtils.isStageActivityType(event.getActType())) {
				EnrichedActivityItem activity = ongoing.remove(event.getPersonId());

				if (activity == null) {
					if (event.getCoord() == null) {
						// can happen for BeforeVrpSchedule activities of DRT vehicles
						return;
					}

					// this is the first activity
					String mode = "walk"; // First activity has no previous trip
					double currentMarketPrice = market != null ? market.getMarketPrice_EUR_per_coin() : 0.0;
					double walletAfter = getPersonWallet(event.getPersonId());
					double mobilityCoinsCharged = 0.0; // No charge for first activity
					double walletBefore = walletAfter;

					activity = new EnrichedActivityItem(event.getPersonId(), 0, event.getActType(), 
							Double.NEGATIVE_INFINITY, event.getTime(), 
							event.getCoord().getX(), event.getCoord().getY(), 
							event.getFacilityId(), event.getLinkId(),
							mode, walletBefore, walletAfter, mobilityCoinsCharged, currentMarketPrice);

					Verify.verify(activityIndex.put(event.getPersonId(), 0) == null);

					activities.add(activity);
					ongoing.put(event.getPersonId(), activity);
				} else {
					activity.endTime = event.getTime();
				}
			}
		}
	}

	private double getPersonWallet(Id<Person> personId) {
		if (market != null) {
			Person person = population.getPersons().get(personId);
			if (person != null && person.getAttributes().getAttribute(MobilityCoinsMarket.WALLET_ATTRIBUTE) != null) {
				return (Double) person.getAttributes().getAttribute(MobilityCoinsMarket.WALLET_ATTRIBUTE);
			}
		}
		return 2.8; // Default wallet value
	}

	private double calculateMobilityCoinsCharged(Id<Person> personId, String mode) {
		// Walking and biking don't cost anything as per user requirement
		if ("walk".equals(mode) || "bike".equals(mode)) {
			return 0.0;
		}

		if (calculator == null || market == null) {
			return 0.0;
		}

		try {
			Person person = population.getPersons().get(personId);
			if (person != null) {
				List<Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
				if (!trips.isEmpty()) {
					// Get the last trip for this person to calculate coins charged
					Trip lastTrip = trips.get(trips.size() - 1);
					MobilityCoinsDistances distances = MobilityCoinsDistances.calculate(lastTrip.getTripElements());
					double coinsDelta = calculator.calculateCoinDelta(distances);
					
					// Convert negative coin delta to positive charge
					if (coinsDelta < 0) {
						return Math.abs(coinsDelta) * market.getMarketPrice_EUR_per_coin();
					}
				}
			}
		} catch (Exception e) {
			// If there's any error calculating coins, return 0
			return 0.0;
		}

		return 0.0;
	}
} 