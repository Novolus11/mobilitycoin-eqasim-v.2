package org.eqasim.core.analysis.activities;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.facilities.ActivityFacility;

public class EnrichedActivityItem extends ActivityItem {
	public String mode;
	public double personWalletBefore;
	public double personWalletAfter;
	public double mobilityCoinsCharged;
	public double currentMarketPrice;

	public EnrichedActivityItem(Id<Person> personId, int activityIndex, String purpose, double startTime, double endTime,
			double x, double y, Id<ActivityFacility> facilityId, Id<Link> linkId, String mode,
			double personWalletBefore, double personWalletAfter, double mobilityCoinsCharged, double currentMarketPrice) {
		super(personId, activityIndex, purpose, startTime, endTime, x, y, facilityId, linkId);
		this.mode = mode;
		this.personWalletBefore = personWalletBefore;
		this.personWalletAfter = personWalletAfter;
		this.mobilityCoinsCharged = mobilityCoinsCharged;
		this.currentMarketPrice = currentMarketPrice;
	}
} 