package org.eqasim.core.analysis.trips;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;

public class EnrichedTripListenerItem extends TripListenerItem {
	public double personWalletBefore = Double.NaN;
	public double personWalletAfter = Double.NaN;
	public double coinChargeForTrip = Double.NaN;
	public double currentMarketPrice = Double.NaN;

	public EnrichedTripListenerItem(Id<Person> personId, int personTripId, Coord origin, double startTime,
			String startPurpose, Id<Link> originLinkId) {
		super(personId, personTripId, origin, startTime, startPurpose, originLinkId);
	}
} 