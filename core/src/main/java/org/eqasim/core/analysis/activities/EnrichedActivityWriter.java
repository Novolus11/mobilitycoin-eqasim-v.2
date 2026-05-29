package org.eqasim.core.analysis.activities;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Collection;
import java.util.zip.GZIPOutputStream;

public class EnrichedActivityWriter {
	final private Collection<EnrichedActivityItem> activities;
	final private String delimiter;

	public EnrichedActivityWriter(Collection<EnrichedActivityItem> activities) {
		this(activities, ";");
	}

	public EnrichedActivityWriter(Collection<EnrichedActivityItem> activities, String delimiter) {
		this.activities = activities;
		this.delimiter = delimiter;
	}

	public void write(String outputPath) throws IOException {
		OutputStream outputStream = new FileOutputStream(outputPath);
		if (outputPath.endsWith(".gz")) {
			outputStream = new GZIPOutputStream(outputStream);
		}
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));

		writer.write(formatHeader() + "\n");
		writer.flush();

		for (EnrichedActivityItem activity : activities) {
			writer.write(formatActivity(activity) + "\n");
			writer.flush();
		}

		writer.flush();
		writer.close();
	}

	private String formatHeader() {
		return String.join(delimiter, new String[] { //
				"person_id", //
				"activity_index", //
				"purpose", //
				"start_time", //
				"end_time", //
				"x", //
				"y", //
				"facility_id", //
				"link_id", //
				"mode", //
				"person_wallet_before", //
				"person_wallet_after", //
				"mobility_coins_charged", //
				"current_market_price", //
		});
	}

	private String formatActivity(EnrichedActivityItem activity) {
		return String.join(delimiter, new String[] { //
				activity.personId.toString(), //
				String.valueOf(activity.activityIndex), //
				activity.purpose, //
				String.valueOf(activity.startTime), //
				String.valueOf(activity.endTime), //
				String.valueOf(activity.x), //
				String.valueOf(activity.y), //
				activity.facilityId.toString(), //
				activity.linkId.toString(), //
				activity.mode != null ? activity.mode : "walk", //
				String.valueOf(activity.personWalletBefore), //
				String.valueOf(activity.personWalletAfter), //
				String.valueOf(activity.mobilityCoinsCharged), //
				String.valueOf(activity.currentMarketPrice), //
		});
	}
} 