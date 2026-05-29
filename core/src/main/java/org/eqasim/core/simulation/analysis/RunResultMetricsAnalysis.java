package org.eqasim.core.simulation.analysis;

import java.io.File;
import java.io.IOException;

import org.eqasim.core.simulation.termination.TerminationState;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;

/**
 * Standalone tool to generate resultMetrics.csv from existing simulation output files
 * without re-running the full simulation.
 * 
 * This tool processes existing events files and generates the same comprehensive
 * metrics as the ResultMetricsListener, but as a post-processing step.
 */
public class RunResultMetricsAnalysis {
    
    public static void main(String[] args) throws CommandLine.ConfigurationException, IOException {
        CommandLine cmd = new CommandLine.Builder(args)
                .requireOptions("events-path", "network-path", "output-path")
                .allowOptions("config-path")
                .build();
        
        String eventsPath = cmd.getOptionStrict("events-path");
        String networkPath = cmd.getOptionStrict("network-path");
        String outputPath = cmd.getOptionStrict("output-path");
        
        System.out.println("=== Result Metrics Analysis ===");
        System.out.println("Events file: " + eventsPath);
        System.out.println("Network file: " + networkPath);
        System.out.println("Output path: " + outputPath);
        
        // Load network
        System.out.println("Loading network...");
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(networkPath);
        System.out.println("Network loaded with " + network.getLinks().size() + " links");
        
        // Create output directory hierarchy (needed by ResultMetricsListener)
        File outputDir = new File(outputPath).getParentFile();
        if (outputDir == null) {
            outputDir = new File(".");
        }
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        
        // Create a minimal output directory hierarchy
        OutputDirectoryHierarchy outputDirectoryHierarchy = new OutputDirectoryHierarchy(
            outputDir.getAbsolutePath(), 
            null,
            OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles, 
            false,
            ControllerConfigGroup.CompressionType.gzip
        );
        
        // Create the metrics listener
        // For standalone analysis, create a TerminationState (not used during analysis)
        System.out.println("Initializing metrics listener...");
        TerminationState terminationState = new TerminationState();
        ResultMetricsListener metricsListener = new ResultMetricsListener(outputDirectoryHierarchy, network, terminationState);
        
        // Set up events manager
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(metricsListener);
        
        // Reset data structures and process events
        System.out.println("Processing events...");
        metricsListener.resetDataStructures();
        
        // Process events
        eventsManager.initProcessing();
        new MatsimEventsReader(eventsManager).readFile(eventsPath);
        eventsManager.finishProcessing();
        
        // Generate the report
        System.out.println("Generating result metrics report...");
        metricsListener.writeReport(outputPath);
        
        System.out.println("Result metrics analysis completed successfully!");
        System.out.println("Output file: " + outputPath);
    }
}
