package com.example.slot.config;

import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import com.example.slot.domain.Order;
import com.example.slot.domain.SlotSchedule;
import com.example.slot.solver.SlotConstraintProvider;

import java.time.Duration;

/**
 * Simple solver configuration factory.
 * Change SOLVING_TIME_SECONDS for different run durations.
 */
public class SolverConfigFactory {

    // Change this value for different solving times
    private static final int SOLVING_TIME_SECONDS = 120; // 2 minutes for local testing, change to 1800 for 30min prod
    
    // Private constructor to prevent instantiation
    private SolverConfigFactory() {
    }

    /**
     * Creates the solver configuration.
     * Change SOLVING_TIME_SECONDS above for different run durations.
     */
    public static SolverConfig createConfig() {
        return new SolverConfig()
                .withSolutionClass(SlotSchedule.class)
                .withEntityClasses(Order.class)
                .withConstraintProviderClass(SlotConstraintProvider.class)
                .withEnvironmentMode(EnvironmentMode.NON_REPRODUCIBLE)
                .withTerminationConfig(new TerminationConfig()
                        .withSpentLimit(Duration.ofSeconds(SOLVING_TIME_SECONDS))
                        .withUnimprovedSpentLimit(Duration.ofSeconds(SOLVING_TIME_SECONDS / 4)) // Stop if no improvement for 25% of total time
                        .withBestScoreLimit("0hard/-10000soft")); // Stop if excellent score achieved
    }
}
