package com.example.slot.solver;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import com.example.slot.domain.SlotSchedule;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks and reports solver progress during optimization.
 * Reports intermediate solutions and performance metrics.
 */
public class ProgressTracker {

    private final AtomicInteger solutionCount = new AtomicInteger(0);
    private final AtomicLong startTime = new AtomicLong();
    private final AtomicLong lastUpdateTime = new AtomicLong();
    private HardSoftScore bestScore = null;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    public void startTracking() {
        long now = System.currentTimeMillis();
        startTime.set(now);
        lastUpdateTime.set(now);
        solutionCount.set(0);
        bestScore = null;
        System.out.println("🚀 Starting optimization - tracking progress...");
        System.out.println("📊 Format: [Time] Solution #X | Score: Y | Elapsed: Z seconds | Improvement: ΔY");
        System.out.println("─".repeat(80));
    }

    public void trackSolution(SlotSchedule solution) {
        long now = System.currentTimeMillis();
        int count = solutionCount.incrementAndGet();
        HardSoftScore currentScore = solution.getScore();

        double elapsedSeconds = (now - startTime.get()) / 1000.0;
        String timeStr = LocalDateTime.now().format(timeFormatter);

        // Calculate improvement
        String improvement;
        if (bestScore != null) {
            HardSoftScore delta = currentScore.subtract(bestScore);
            if (delta.compareTo(HardSoftScore.ZERO) > 0) {
                improvement = " ⬆️ +" + delta;
            } else if (delta.compareTo(HardSoftScore.ZERO) < 0) {
                improvement = " ⬇️ " + delta;
            } else {
                improvement = " ➡️ (no change)";
            }
        } else {
            improvement = " 🎯 (first solution)";
        }

        // Update best score
        if (bestScore == null || currentScore.compareTo(bestScore) > 0) {
            bestScore = currentScore;
        }

        // Print progress report
        System.out.printf("[%s] Solution #%-3d | Score: %-20s | Elapsed: %6.1fs%s%n",
                timeStr, count, currentScore, elapsedSeconds, improvement);

        // Print summary statistics every 10 solutions
        if (count % 10 == 0) {
            printDetailedProgress(solution, elapsedSeconds, count);
        }

        lastUpdateTime.set(now);
    }

    private void printDetailedProgress(SlotSchedule solution, double elapsedSeconds, int solutionCount) {
        long assignedOrders = solution.getOrderList().stream()
                .filter(order -> order.getAssignedShift() != null)
                .count();
        long unassignedOrders = solution.getOrderList().size() - assignedOrders;

        double assignmentRate = (assignedOrders * 100.0) / solution.getOrderList().size();
        double solutionsPerSecond = solutionCount / elapsedSeconds;

        System.out.println("  📈 Progress Summary:");
        System.out.printf("     • Assigned: %d/%d orders (%.1f%%)%n",
                assignedOrders, solution.getOrderList().size(), assignmentRate);
        System.out.printf("     • Unassigned: %d orders%n", unassignedOrders);
        System.out.printf("     • Solutions/sec: %.1f%n", solutionsPerSecond);
        System.out.printf("     • Best score so far: %s%n", bestScore);
        System.out.println("  ─".repeat(40));
    }

}
