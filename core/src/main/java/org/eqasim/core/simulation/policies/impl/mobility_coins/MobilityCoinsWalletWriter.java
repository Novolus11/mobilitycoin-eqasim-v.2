package org.eqasim.core.simulation.policies.impl.mobility_coins;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.utils.io.IOUtils;

/**
 * Writer for MobilityCoin wallet data with gzip compression.
 * 
 * Outputs a simplified CSV with only essential columns:
 * - person_id, age, sex, ptSubscription, carAvailability, hasLicense
 * - initial_wallet (allocated value before any trip is made)
 * - wallet_after_iteration (final balance after all trips in the iteration)
 */
public class MobilityCoinsWalletWriter {
    private final File outputPath;
    private final List<String> sociodemographicAttributes = List.of(
        "age", "sex", "ptSubscription", "carAvailability", "hasLicense"
    );

    public MobilityCoinsWalletWriter(File outputPath) {
        this.outputPath = outputPath;
    }

    /**
     * Write wallet data for the current iteration.
     * 
     * @param initialWallets Map of person IDs to initial wallet values (before any trips)
     * @param finalWallets Map of person IDs to final wallet values (after all trips)
     * @param persons Map of person IDs to Person objects (for attributes)
     * @param iteration Current iteration number
     */
    public void writeWallets(Map<Id<Person>, Double> initialWallets, Map<Id<Person>, Double> finalWallets, 
                            Map<Id<Person>, Person> persons, int iteration) {
        try {
            // Use gzip compression by adding .gz extension
            String outputPathWithGz = outputPath.toString();
            if (!outputPathWithGz.endsWith(".gz")) {
                outputPathWithGz += ".gz";
            }
            
            BufferedWriter writer = IOUtils.getBufferedWriter(outputPathWithGz);

            // Write header
            writer.write(String.join(";", 
                "person_id",
                "age",
                "sex",
                "ptSubscription",
                "carAvailability",
                "hasLicense",
                "initial_wallet",
                "wallet_after_iteration"
            ) + "\n");

            // Write data for each person
            for (Map.Entry<Id<Person>, Person> entry : persons.entrySet()) {
                Id<Person> personId = entry.getKey();
                Person person = entry.getValue();
                
                // Get wallet values
                Double initialWallet = initialWallets.get(personId);
                Double finalWallet = finalWallets.get(personId);
                
                // Skip if no wallet data
                if (initialWallet == null || finalWallet == null) {
                    continue;
                }
                
                // Build row
                StringBuilder row = new StringBuilder();
                row.append(personId.toString()).append(";");
                
                // Add sociodemographic attributes
                for (int i = 0; i < sociodemographicAttributes.size(); i++) {
                    String attribute = sociodemographicAttributes.get(i);
                    Object value = person.getAttributes().getAttribute(attribute);
                    row.append(value != null ? value.toString() : "NA");
                    if (i < sociodemographicAttributes.size() - 1) {
                        row.append(";");
                    }
                }
                
                row.append(";");
                
                // Add wallet values
                row.append(String.format("%.2f", initialWallet)).append(";");
                row.append(String.format("%.2f", finalWallet));
                
                writer.write(row.toString() + "\n");
            }

            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
