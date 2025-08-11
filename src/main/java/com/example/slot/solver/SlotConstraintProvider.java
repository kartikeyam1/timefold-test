package com.example.slot.solver;

import static java.time.temporal.ChronoUnit.DAYS;

import java.time.LocalTime;
import java.util.List;

import com.example.slot.domain.Order;
import com.example.slot.domain.ShiftBucket;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.Joiners;

public class SlotConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                // Hard constraints
                orderMustBeOnAllowedDay(factory),
                capacityMustNotBeExceeded(factory),
                riderMustHaveRequiredSkills(factory),
                vehicleWeightMustNotBeExceeded(factory),
                vehicleVolumeMustNotBeExceeded(factory),
                bufferCapacityMustBeRespected(factory),
                
                // Soft constraints
                preferEarliestDay(factory),
                balanceMovableVsNonMovable(factory),
                minimizeDepotToOrderDistance(factory),
                workloadBalancePerRiderAcrossDays(factory),
                clusteringBonus(factory),
                customerTimeWindowPreference(factory)
        };
    }

    private Constraint orderMustBeOnAllowedDay(ConstraintFactory factory) {
        return factory.forEach(Order.class)
                .filter(order -> order.getAssignedShift() != null
                        && (order.getAllowedDays() == null
                        || !order.getAllowedDays().contains(order.getAssignedShift().getDate())))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Order must be assigned to an allowed day");
    }

    private Constraint capacityMustNotBeExceeded(ConstraintFactory factory) {
        return factory.forEach(Order.class)
                .filter(o -> o.getAssignedShift() != null)
                .groupBy(Order::getAssignedShift, ConstraintCollectors.count())
                .filter((shift, count) -> count > shift.getEffectiveCapacity())
                .penalize(HardSoftScore.ONE_HARD,
                        (shift, count) -> count - shift.getEffectiveCapacity())
                .asConstraint("Shift effective capacity must not be exceeded");
    }

    private Constraint preferEarliestDay(ConstraintFactory factory) {
        return factory.forEach(Order.class)
                .filter(order -> order.getAssignedShift() != null && order.getEarliestAllowedDay() != null)
                .penalize(HardSoftScore.ONE_SOFT, order -> {
                    var earliest = order.getEarliestAllowedDay();
                    var assigned = order.getAssignedShift().getDate();
                    long diff = DAYS.between(earliest, assigned);
                    return (int) Math.max(0, diff);
                })
                .asConstraint("Prefer earliest allowed day");
    }

    private Constraint balanceMovableVsNonMovable(ConstraintFactory factory) {
        return factory.forEach(Order.class)
                .filter(order -> order.getAssignedShift() != null && order.isMovable())
                .groupBy(Order::getAssignedShift, ConstraintCollectors.count())
                .filter((shift, movableCount) -> {
                    int limit = (int) Math.floor(shift.getMovableOccupationRatioThreshold() * shift.getCapacity());
                    return movableCount > limit;
                })
                .penalize(HardSoftScore.ONE_SOFT, (shift, movableCount) -> {
                    int limit = (int) Math.floor(shift.getMovableOccupationRatioThreshold() * shift.getCapacity());
                    return movableCount - limit;
                })
                .asConstraint("Balance movable and non-movable (ratio threshold)");
    }

    private Constraint minimizeDepotToOrderDistance(ConstraintFactory factory) {
        return factory.forEach(Order.class)
                .filter(order -> order.getAssignedShift() != null)
                .penalize(HardSoftScore.ONE_SOFT, order -> {
                    ShiftBucket shift = order.getAssignedShift();
                    double lat1 = shift.getStartLatitude();
                    double lon1 = shift.getStartLongitude();
                    double lat2 = order.getLatitude();
                    double lon2 = order.getLongitude();
                    // Use simple haversine-like proxy scaled to an integer to avoid zeroing
                    double d = haversineKm(lat1, lon1, lat2, lon2);
                    return (int) Math.round(d);
                })
                .asConstraint("Minimize depot-to-order distance");
    }

    // New hard constraints
    private Constraint riderMustHaveRequiredSkills(ConstraintFactory factory) {
        return factory.forEach(Order.class)
                .filter(order -> order.getAssignedShift() != null && !order.getRequiredSkills().isEmpty())
                .filter(order -> !order.getAssignedShift().getRiderSkills().containsAll(order.getRequiredSkills()))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Rider must have required skills");
    }

    private Constraint vehicleWeightMustNotBeExceeded(ConstraintFactory factory) {
        return factory.forEach(Order.class)
                .filter(o -> o.getAssignedShift() != null)
                .groupBy(Order::getAssignedShift, ConstraintCollectors.sum(o -> (int) Math.round(o.getWeight())))
                .filter((shift, totalWeight) -> totalWeight > shift.getMaxWeight())
                .penalize(HardSoftScore.ONE_HARD,
                        (shift, totalWeight) -> Math.max(0, (int) Math.ceil(totalWeight - shift.getMaxWeight())))
                .asConstraint("Vehicle weight capacity must not be exceeded");
    }

    private Constraint vehicleVolumeMustNotBeExceeded(ConstraintFactory factory) {
        return factory.forEach(Order.class)
                .filter(o -> o.getAssignedShift() != null)
                .groupBy(Order::getAssignedShift, ConstraintCollectors.sum(o -> (int) Math.round(o.getVolume() * 10)))
                .filter((shift, totalVolumeX10) -> totalVolumeX10 > shift.getMaxVolume() * 10)
                .penalize(HardSoftScore.ONE_HARD,
                        (shift, totalVolumeX10) -> Math.max(0, (int) Math.ceil(totalVolumeX10 / 10.0 - shift.getMaxVolume())))
                .asConstraint("Vehicle volume capacity must not be exceeded");
    }

    private Constraint bufferCapacityMustBeRespected(ConstraintFactory factory) {
        return factory.forEach(Order.class)
                .filter(o -> o.getAssignedShift() != null)
                .groupBy(Order::getAssignedShift, ConstraintCollectors.count())
                .filter((shift, count) -> count > shift.getEffectiveCapacity())
                .penalize(HardSoftScore.ONE_HARD,
                        (shift, count) -> count - shift.getEffectiveCapacity())
                .asConstraint("Buffer capacity must be respected");
    }

    // New soft constraints  
    private Constraint workloadBalancePerRiderAcrossDays(ConstraintFactory factory) {
        // Simplified: penalize riders with very high or very low total assignments
        return factory.forEach(Order.class)
                .filter(o -> o.getAssignedShift() != null)
                .groupBy(o -> o.getAssignedShift().getRiderId(), ConstraintCollectors.count())
                .penalize(HardSoftScore.ONE_SOFT, (riderId, count) -> {
                    int target = 12; // target orders per rider across all days
                    return Math.abs(count - target);
                })
                .asConstraint("Balance workload per rider across week");
    }

    private Constraint clusteringBonus(ConstraintFactory factory) {
        return factory.forEach(Order.class)
                .filter(o -> o.getAssignedShift() != null)
                .join(Order.class,
                        Joiners.equal(Order::getAssignedShift),
                        Joiners.lessThan(Order::getId))
                .reward(HardSoftScore.ONE_SOFT, (order1, order2) -> {
                    double distance = haversineKm(order1.getLatitude(), order1.getLongitude(),
                                                  order2.getLatitude(), order2.getLongitude());
                    return distance < 2.0 ? 10 : 0; // Reward if within 2km
                })
                .asConstraint("Clustering bonus for nearby orders");
    }

    private Constraint customerTimeWindowPreference(ConstraintFactory factory) {
        return factory.forEach(Order.class)
                .filter(order -> order.getAssignedShift() != null 
                        && order.getPreferredStartTime() != null 
                        && order.getPreferredEndTime() != null)
                .penalize(HardSoftScore.ONE_SOFT, order -> {
                    // Assume shift starts at 9 AM for simplicity
                    LocalTime shiftStart = LocalTime.of(9, 0);
                    LocalTime orderPrefStart = order.getPreferredStartTime();
                    LocalTime orderPrefEnd = order.getPreferredEndTime();
                    
                    // Penalty if shift start is outside customer's preferred window
                    if (shiftStart.isBefore(orderPrefStart)) {
                        return (int) java.time.Duration.between(shiftStart, orderPrefStart).toMinutes() / 30;
                    } else if (shiftStart.isAfter(orderPrefEnd)) {
                        return (int) java.time.Duration.between(orderPrefEnd, shiftStart).toMinutes() / 30;
                    }
                    return 0;
                })
                .asConstraint("Customer time window preference");
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0; // km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}
