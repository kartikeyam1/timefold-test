package com.example.slot.domain;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import ai.timefold.solver.core.api.domain.lookup.PlanningId;

public class ShiftBucket {

    @PlanningId
    private String id;

    private String riderId;

    private LocalDate date;

    private int capacity;

    private double movableOccupationRatioThreshold;

    private double startLatitude;

    private double startLongitude;

    // Rider capabilities
    private Set<String> riderSkills = new HashSet<>();

    // Vehicle constraints
    private double maxWeight; // kg
    private double maxVolume; // cubic meters

    // Buffer capacity for future orders
    private double bufferRatio; // 0.0-1.0, portion of capacity to reserve

    public ShiftBucket() {
    }

    public ShiftBucket(String id, LocalDate date, int capacity, double movableOccupationRatioThreshold) {
        this.id = id;
        this.date = date;
        this.capacity = capacity;
        this.movableOccupationRatioThreshold = movableOccupationRatioThreshold;
    }

    public ShiftBucket(String id, String riderId, LocalDate date, int capacity, double movableOccupationRatioThreshold,
                       double startLatitude, double startLongitude) {
        this.id = id;
        this.riderId = riderId;
        this.date = date;
        this.capacity = capacity;
        this.movableOccupationRatioThreshold = movableOccupationRatioThreshold;
        this.startLatitude = startLatitude;
        this.startLongitude = startLongitude;
    }

    public ShiftBucket(String id, String riderId, LocalDate date, int capacity, double movableOccupationRatioThreshold,
                       double startLatitude, double startLongitude, Set<String> riderSkills,
                       double maxWeight, double maxVolume, double bufferRatio) {
        this(id, riderId, date, capacity, movableOccupationRatioThreshold, startLatitude, startLongitude);
        this.riderSkills = new HashSet<>(riderSkills);
        this.maxWeight = maxWeight;
        this.maxVolume = maxVolume;
        this.bufferRatio = bufferRatio;
    }


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRiderId() {
        return riderId;
    }

    public void setRiderId(String riderId) {
        this.riderId = riderId;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public double getMovableOccupationRatioThreshold() {
        return movableOccupationRatioThreshold;
    }

    public void setMovableOccupationRatioThreshold(double movableOccupationRatioThreshold) {
        this.movableOccupationRatioThreshold = movableOccupationRatioThreshold;
    }

    public double getStartLatitude() {
        return startLatitude;
    }

    public void setStartLatitude(double startLatitude) {
        this.startLatitude = startLatitude;
    }

    public double getStartLongitude() {
        return startLongitude;
    }

    public void setStartLongitude(double startLongitude) {
        this.startLongitude = startLongitude;
    }

    public Set<String> getRiderSkills() {
        return Collections.unmodifiableSet(riderSkills);
    }

    public void setRiderSkills(Set<String> riderSkills) {
        this.riderSkills = new HashSet<>(riderSkills);
    }

    public double getMaxWeight() {
        return maxWeight;
    }

    public void setMaxWeight(double maxWeight) {
        this.maxWeight = maxWeight;
    }

    public double getMaxVolume() {
        return maxVolume;
    }

    public void setMaxVolume(double maxVolume) {
        this.maxVolume = maxVolume;
    }

    public double getBufferRatio() {
        return bufferRatio;
    }

    public void setBufferRatio(double bufferRatio) {
        this.bufferRatio = bufferRatio;
    }

    public int getEffectiveCapacity() {
        return (int) Math.floor(capacity * (1.0 - bufferRatio));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ShiftBucket that = (ShiftBucket) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ShiftBucket{" +
                "id='" + id + '\'' +
                ", riderId='" + riderId + '\'' +
                ", date=" + date +
                ", capacity=" + capacity +
                ", movableThreshold=" + movableOccupationRatioThreshold +
                ", startLatLng=" + startLatitude + "," + startLongitude +
                '}';
    }
}
