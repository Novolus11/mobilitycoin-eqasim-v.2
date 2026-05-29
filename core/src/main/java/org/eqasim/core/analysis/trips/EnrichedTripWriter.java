package org.eqasim.core.analysis.trips;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Collection;

import org.eqasim.core.analysis.DistanceUnit;

public class EnrichedTripWriter {
	final private Collection<EnrichedTripItem> trips;
	final private String delimiter;

	final private DistanceUnit inputUnit;
	final private DistanceUnit outputUnit;

	public EnrichedTripWriter(Collection<EnrichedTripItem> trips, DistanceUnit inputUnit, DistanceUnit outputUnit) {
		this(trips, inputUnit, outputUnit, ";");
	}

	public EnrichedTripWriter(Collection<EnrichedTripItem> trips, DistanceUnit inputUnit, DistanceUnit outputUnit, String delimiter) {
		this.trips = trips;
		this.delimiter = delimiter;
		this.inputUnit = inputUnit;
		this.outputUnit = outputUnit;
	}

	public void write(String outputPath) throws IOException {
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputPath)));

		writer.write(formatHeader() + "\n");
		writer.flush();

		for (EnrichedTripItem trip : trips) {
			writer.write(formatTrip(trip) + "\n");
			writer.flush();
		}

		writer.flush();
		writer.close();
	}

	private String normalizeActivityType(String activityType) {
		return activityType.replaceAll("_[0-9]+$", "");
	}

	private String formatHeader() {
		return String.join(delimiter, new String[] { //
				"person_id", //
				"person_trip_id", //
				"origin_x", //
				"origin_y", //
				"destination_x", //
				"destination_y", "departure_time", //
				"travel_time", //
				"vehicle_distance", //
				"routed_distance", //
				"mode", //
				"preceding_purpose", //
				"following_purpose", //
				"returning", //
				"euclidean_distance", //
				"origin_link_id", //
				"destination_link_id", //
				"person_wallet_before", //
				"person_wallet_after", //
				"coin_charge_for_trip", //
				"current_market_price" //
		});
	}

	/**
	 * Gets a factor to convert any other unit to meters
	 */
	private double getUnitFactor(DistanceUnit unit) {
		double factor = Double.NaN;

		switch (inputUnit) {
		case foot:
			factor = 0.3048;
			break;
		case kilometer:
			factor = 1e3;
			break;
		case meter:
			factor = 1.0;
			break;
		case mile:
			factor = 1609.344;
			break;
		default:
			throw new IllegalStateException("Unknown input unit");
		}

		return factor;
	}

	private String formatTrip(EnrichedTripItem trip) {
		double inputFactor = getUnitFactor(inputUnit);
		double outputFactor = 1.0 / getUnitFactor(outputUnit);

		return String.join(delimiter, new String[] { //
				trip.personId.toString(), //
				String.valueOf(trip.personTripId), //
				String.valueOf(trip.origin.getX()), //
				String.valueOf(trip.origin.getY()), //
				String.valueOf(trip.destination.getX()), //
				String.valueOf(trip.destination.getY()), //
				String.valueOf(trip.departureTime), //
				String.valueOf(trip.travelTime), //
				String.valueOf(trip.vehicleDistance * inputFactor * outputFactor), //
				String.valueOf(trip.routedDistance * inputFactor * outputFactor), //
				String.valueOf(trip.mode), //
				normalizeActivityType(String.valueOf(trip.precedingPurpose)), //
				normalizeActivityType(String.valueOf(trip.followingPurpose)), //
				String.valueOf(trip.returning), //
				String.valueOf(trip.euclideanDistance * inputFactor * outputFactor), //
				String.valueOf(trip.originLinkId), //
				String.valueOf(trip.destinationLinkId), //
				String.valueOf(trip.personWalletBefore), //
				String.valueOf(trip.personWalletAfter), //
				String.valueOf(trip.coinChargeForTrip), //
				String.valueOf(trip.currentMarketPrice) //
		});
	}
} 