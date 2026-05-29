package org.eqasim.core.analysis.trips;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;

import org.eqasim.core.analysis.PersonAnalysisFilter;
import org.eqasim.core.components.transit.events.PublicTransitEvent;
import org.eqasim.core.simulation.policies.impl.mobility_coins.MobilityCoinsDistances;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsCalculator;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsMarket;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.GenericEvent;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.events.handler.ActivityStartEventHandler;
import org.matsim.api.core.v01.events.handler.GenericEventHandler;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.api.experimental.events.TeleportationArrivalEvent;
import org.matsim.core.api.experimental.events.handler.TeleportationArrivalEventHandler;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.vehicles.Vehicle;

public class EnrichedTripListener implements ActivityStartEventHandler, ActivityEndEventHandler, PersonDepartureEventHandler,
		PersonEntersVehicleEventHandler, PersonLeavesVehicleEventHandler, LinkEnterEventHandler,
		TeleportationArrivalEventHandler, GenericEventHandler {
	final private Network network;
	final private Population population;
	final private Optional<MobilityCoinsMarket> market;
	final private Optional<MobilityCoinsCalculator> calculator;

	final private Collection<EnrichedTripItem> trips = new LinkedList<>();
	final private Map<Id<Person>, EnrichedTripListenerItem> ongoing = new HashMap<>();
	final private Map<Id<Vehicle>, Collection<Id<Person>>> passengers = new HashMap<>();
	final private Map<Id<Person>, Integer> tripIndex = new HashMap<>();
	
	// Track wallet balances throughout the iteration (day) for accurate reporting
	final private Map<Id<Person>, Double> currentIterationWallets = new HashMap<>();

	final private PersonAnalysisFilter personFilter;

	public EnrichedTripListener(Network network, Population population, PersonAnalysisFilter personFilter,
			Optional<MobilityCoinsMarket> market, Optional<MobilityCoinsCalculator> calculator) {
		this.network = network;
		this.population = population;
		this.personFilter = personFilter;
		this.market = market;
		this.calculator = calculator;
	}

	public Collection<EnrichedTripItem> getTripItems() {
		return trips;
	}

	@Override
	public void reset(int iteration) {
		trips.clear();
		ongoing.clear();
		passengers.clear();
		tripIndex.clear();
		
		// Reset wallet tracking for new iteration (new day)
		currentIterationWallets.clear();
	}

	@Override
	public void handleEvent(ActivityEndEvent event) {
		if (personFilter.analyzePerson(event.getPersonId())) {
			if (!TripStructureUtils.isStageActivityType(event.getActType())) {
				Integer personTripIndex = tripIndex.get(event.getPersonId());
				network.getLinks().get(event.getLinkId()).getCoord();

				if (personTripIndex == null) {
					personTripIndex = 0;
				} else {
					personTripIndex = personTripIndex + 1;
				}

				EnrichedTripListenerItem item = new EnrichedTripListenerItem(event.getPersonId(), personTripIndex,
						network.getLinks().get(event.getLinkId()).getCoord(), event.getTime(), event.getActType(), event.getLinkId());

				// Get wallet before trip (tracking running balance throughout the day)
				item.personWalletBefore = getCurrentWalletBalance(event.getPersonId());
				
				// Get current market price
				item.currentMarketPrice = market.map(MobilityCoinsMarket::getMarketPrice_EUR_per_coin).orElse(1.0); // Use default initial price

				ongoing.put(event.getPersonId(), item);
				tripIndex.put(event.getPersonId(), personTripIndex);
			}
		}
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		if (personFilter.analyzePerson(event.getPersonId())) {
			EnrichedTripListenerItem item = ongoing.get(event.getPersonId());
			if (item != null) {
				item.mode = event.getRoutingMode();
			}
		}
	}

	@Override
	public void handleEvent(ActivityStartEvent event) {
		if (personFilter.analyzePerson(event.getPersonId())) {
			if (!TripStructureUtils.isStageActivityType(event.getActType())) {
				EnrichedTripListenerItem trip = ongoing.remove(event.getPersonId());

				if (trip != null) {
					trip.returning = event.getActType().equals("home");
					trip.followingPurpose = event.getActType();
					trip.travelTime = event.getTime() - trip.departureTime;
					trip.destination = network.getLinks().get(event.getLinkId()).getCoord();
					trip.destinationLinkId = event.getLinkId();
					trip.euclideanDistance = CoordUtils.calcEuclideanDistance(trip.origin, trip.destination);

					// Calculate coin charge for this trip
					trip.coinChargeForTrip = calculateCoinChargeForTrip(trip);
					
					// Calculate wallet after trip and update our tracking for next trip
					trip.personWalletAfter = trip.personWalletBefore + trip.coinChargeForTrip;
					
					// Update our iteration wallet tracking for this person's next trip
					currentIterationWallets.put(trip.personId, trip.personWalletAfter);

					trips.add(new EnrichedTripItem(trip.personId, trip.personTripId, trip.origin, trip.destination,
							trip.departureTime, trip.travelTime, trip.vehicleDistance, trip.routedDistance, trip.mode,
							trip.precedingPurpose, trip.followingPurpose, trip.returning, trip.euclideanDistance,
							trip.originLinkId, trip.destinationLinkId, 
							trip.personWalletBefore, trip.personWalletAfter, trip.coinChargeForTrip, trip.currentMarketPrice));
				}
			}
		}
	}

	@Override
	public void handleEvent(PersonEntersVehicleEvent event) {
		if (personFilter.analyzePerson(event.getPersonId())) {
			if (!passengers.containsKey(event.getVehicleId())) {
				passengers.put(event.getVehicleId(), new HashSet<>());
			}

			passengers.get(event.getVehicleId()).add(event.getPersonId());
		}
	}

	@Override
	public void handleEvent(PersonLeavesVehicleEvent event) {
		if (personFilter.analyzePerson(event.getPersonId())) {
			if (passengers.containsKey(event.getVehicleId())) {
				passengers.get(event.getVehicleId()).remove(event.getPersonId());

				if (passengers.get(event.getVehicleId()).size() == 0) {
					passengers.remove(event.getVehicleId());
				}

				// Last link is not traversed, so we should not count it!
				EnrichedTripListenerItem item = ongoing.get(event.getPersonId());
				if (item != null) {
					item.routedDistance -= item.lastAddedLinkDistance;
					item.vehicleDistance -= item.lastAddedLinkDistance;
				}
			}
		}
	}

	@Override
	public void handleEvent(LinkEnterEvent event) {
		Collection<Id<Person>> personIds = passengers.get(event.getVehicleId());

		if (personIds != null) {
			personIds.forEach(id -> {
				double linkDistance = network.getLinks().get(event.getLinkId()).getLength();
				EnrichedTripListenerItem item = ongoing.get(id);

				if (item != null) {
					item.routedDistance += linkDistance;
					item.vehicleDistance += linkDistance;
					item.lastAddedLinkDistance = linkDistance;
				}
			});
		}
	}

	@Override
	public void handleEvent(TeleportationArrivalEvent event) {
		if (personFilter.analyzePerson(event.getPersonId())) {
			EnrichedTripListenerItem item = ongoing.get(event.getPersonId());
			if (item != null) {
				item.routedDistance += event.getDistance();
			}
		}
	}

	@Override
	public void handleEvent(GenericEvent event) {
		if (event instanceof PublicTransitEvent) {
			PublicTransitEvent transitEvent = (PublicTransitEvent) event;

			if (personFilter.analyzePerson(transitEvent.getPersonId())) {
				EnrichedTripListenerItem item = ongoing.get(transitEvent.getPersonId());
				if (item != null) {
					item.vehicleDistance += transitEvent.getTravelDistance();
				}
			}
		}
	}

	private double getCurrentWalletBalance(Id<Person> personId) {
		// Check if we already have a running balance for this person in this iteration
		if (currentIterationWallets.containsKey(personId)) {
			return currentIterationWallets.get(personId);
		}
		
		// First trip of the day - get initial wallet value
		double initialWallet = getInitialPersonWallet(personId);
		currentIterationWallets.put(personId, initialWallet);
		return initialWallet;
	}
	
	private double getInitialPersonWallet(Id<Person> personId) {
		if (market.isPresent()) {
			Person person = population.getPersons().get(personId);
			if (person != null && person.getAttributes().getAttribute(MobilityCoinsMarket.WALLET_ATTRIBUTE) != null) {
				return (Double) person.getAttributes().getAttribute(MobilityCoinsMarket.WALLET_ATTRIBUTE);
			}
		}
		return 2.8; // Default wallet value from MobilityCoinsParameters
	}

	private double calculateCoinChargeForTrip(EnrichedTripListenerItem trip) {
		if (calculator.isPresent()) {
			try {
				// Convert distance from meters to kilometers
				double distance_km = trip.routedDistance * 1e-3;
				
				// Create trip distance object based on mode
				MobilityCoinsDistances distances;
				switch (trip.mode) {
					case "car":
						distances = new MobilityCoinsDistances(distance_km, 0.0, 0.0, 0.0, 0.0);
						break;
					case "car_passenger":
						distances = new MobilityCoinsDistances(0.0, distance_km, 0.0, 0.0, 0.0);
						break;
					case "pt":
						distances = new MobilityCoinsDistances(0.0, 0.0, distance_km, 0.0, 0.0);
						break;
					case "bicycle":
					case "bike":
						distances = new MobilityCoinsDistances(0.0, 0.0, 0.0, distance_km, 0.0);
						break;
					case "walk":
						distances = new MobilityCoinsDistances(0.0, 0.0, 0.0, 0.0, distance_km);
						break;
					default:
						// For other modes, treat as car distance for simplicity
						distances = new MobilityCoinsDistances(distance_km, 0.0, 0.0, 0.0, 0.0);
						break;
				}
				
				return calculator.get().calculateCoinDelta(distances);
			} catch (Exception e) {
				// If calculation fails, return 0
				return 0.0;
			}
		}
		return 0.0; // Default if no calculator available
	}
} 