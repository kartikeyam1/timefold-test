package com.example.slot.config;

import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicPhaseConfig;
import ai.timefold.solver.core.config.constructionheuristic.ConstructionHeuristicType;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchType;
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
    private static final int SOLVING_TIME_SECONDS = 30; // Shorter for progress demo, change to 120+ for production

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
                .withPhases(
                        // Construction phase - quickly builds initial solution
                        new ConstructionHeuristicPhaseConfig()
                                .withConstructionHeuristicType(ConstructionHeuristicType.FIRST_FIT),
                        // Local search phase - improves the solution
                        new LocalSearchPhaseConfig()
                                .withLocalSearchType(LocalSearchType.HILL_CLIMBING)
                                .withTerminationConfig(new TerminationConfig()
                                        .withSpentLimit(Duration.ofSeconds(SOLVING_TIME_SECONDS))
                                        .withUnimprovedSpentLimit(Duration.ofSeconds(SOLVING_TIME_SECONDS / 4)))
                );
    }
    
    /**
     * Creates a progress-optimized solver configuration for demos.
     * Finds solutions more frequently for better progress visualization.
     */
    public static SolverConfig createProgressConfig() {
        return new SolverConfig()
                .withSolutionClass(SlotSchedule.class)
                .withEntityClasses(Order.class)
                .withConstraintProviderClass(SlotConstraintProvider.class)
                .withEnvironmentMode(EnvironmentMode.NON_REPRODUCIBLE)
                .withPhases(
                        // Construction phase - builds initial solution quickly
                        new ConstructionHeuristicPhaseConfig()
                                .withConstructionHeuristicType(ConstructionHeuristicType.FIRST_FIT),
                        // Multiple short local search phases for frequent updates
                        new LocalSearchPhaseConfig()
                                .withLocalSearchType(LocalSearchType.HILL_CLIMBING)
                                .withTerminationConfig(new TerminationConfig()
                                        .withSpentLimit(Duration.ofSeconds(8))), // Short bursts
                        new LocalSearchPhaseConfig()
                                .withLocalSearchType(LocalSearchType.TABU_SEARCH)
                                .withTerminationConfig(new TerminationConfig()
                                        .withSpentLimit(Duration.ofSeconds(8))),
                        new LocalSearchPhaseConfig()
                                .withLocalSearchType(LocalSearchType.HILL_CLIMBING)
                                .withTerminationConfig(new TerminationConfig()
                                        .withSpentLimit(Duration.ofSeconds(8)))
                );
    }
}
