package com.example.slot.domain;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.lookup.PlanningId;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

@PlanningEntity
public class Order {

    @PlanningId
    private String id;

    private Set<LocalDate> allowedDays = new HashSet<>();

    private double latitude;

    private double longitude;

    // Customer preferences
    private LocalTime preferredStartTime; // e.g., 10:00 AM
    private LocalTime preferredEndTime;   // e.g., 2:00 PM

    // Order characteristics
    private double weight; // kg
    private double volume; // cubic meters
    private Set<String> requiredSkills = new HashSet<>(); // e.g., "ELECTRICAL", "PLUMBING"

    @PlanningVariable(valueRangeProviderRefs = {"shiftRange"})
    private ShiftBucket assignedShift;

    public Order() {
    }

    public Order(String id, Set<LocalDate> allowedDays) {
        this.id = id;
        this.allowedDays = new HashSet<>(allowedDays);
    }

    public Order(String id, Set<LocalDate> allowedDays, double latitude, double longitude) {
        this(id, allowedDays);
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public Order(String id, Set<LocalDate> allowedDays, double latitude, double longitude,
                 LocalTime preferredStartTime, LocalTime preferredEndTime,
                 double weight, double volume, Set<String> requiredSkills) {
        this(id, allowedDays, latitude, longitude);
        this.preferredStartTime = preferredStartTime;
        this.preferredEndTime = preferredEndTime;
        this.weight = weight;
        this.volume = volume;
        this.requiredSkills = new HashSet<>(requiredSkills);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Set<LocalDate> getAllowedDays() {
        return Collections.unmodifiableSet(allowedDays);
    }

    public void setAllowedDays(Set<LocalDate> allowedDays) {
        this.allowedDays = new HashSet<>(allowedDays);
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public LocalTime getPreferredStartTime() {
        return preferredStartTime;
    }

    public void setPreferredStartTime(LocalTime preferredStartTime) {
        this.preferredStartTime = preferredStartTime;
    }

    public LocalTime getPreferredEndTime() {
        return preferredEndTime;
    }

    public void setPreferredEndTime(LocalTime preferredEndTime) {
        this.preferredEndTime = preferredEndTime;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public double getVolume() {
        return volume;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }

    public Set<String> getRequiredSkills() {
        return Collections.unmodifiableSet(requiredSkills);
    }

    public void setRequiredSkills(Set<String> requiredSkills) {
        this.requiredSkills = new HashSet<>(requiredSkills);
    }

    public ShiftBucket getAssignedShift() {
        return assignedShift;
    }

    public void setAssignedShift(ShiftBucket assignedShift) {
        this.assignedShift = assignedShift;
    }

    public boolean isMovable() {
        return allowedDays != null && allowedDays.size() > 1;
    }

    public LocalDate getEarliestAllowedDay() {
        return allowedDays.stream().min(LocalDate::compareTo).orElse(null);
    }

    @Override
    public String toString() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Order order = (Order) o;
        return Objects.equals(id, order.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
