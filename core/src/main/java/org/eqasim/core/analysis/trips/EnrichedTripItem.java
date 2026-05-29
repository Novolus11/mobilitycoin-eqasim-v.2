package org.eqasim.core.analysis.trips;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;

public class EnrichedTripItem extends TripItem {
	public double personWalletBefore;
	public double personWalletAfter;
	public double coinChargeForTrip;
	public double currentMarketPrice;

	public EnrichedTripItem(Id<Person> personId, int personTripId, Coord origin, Coord destination, double startTime,
			double travelTime, double vehicleDistance, double routedDistance, String mode, String precedingPurpose,
			String followingPurpose, boolean returning, double euclideanDistance, Id<Link> originLinkId, Id<Link> destinationLinkId,
			double personWalletBefore, double personWalletAfter, double coinChargeForTrip, double currentMarketPrice) {
		super(personId, personTripId, origin, destination, startTime, travelTime, vehicleDistance, routedDistance,
				mode, precedingPurpose, followingPurpose, returning, euclideanDistance, originLinkId, destinationLinkId);
		this.personWalletBefore = personWalletBefore;
		this.personWalletAfter = personWalletAfter;
		this.coinChargeForTrip = coinChargeForTrip;
		this.currentMarketPrice = currentMarketPrice;
	}
} 