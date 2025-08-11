package com.example.slot.domain;

import java.util.ArrayList;
import java.util.List;

import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;

@PlanningSolution
public class SlotSchedule {

    @PlanningEntityCollectionProperty
    private List<Order> orderList;

    @ProblemFactCollectionProperty
    @ValueRangeProvider(id = "shiftRange")
    private List<ShiftBucket> shiftList;

    @PlanningScore
    private HardSoftScore score;

    public SlotSchedule() {
        this.orderList = new ArrayList<>();
        this.shiftList = new ArrayList<>();
    }

    public SlotSchedule(List<Order> orderList, List<ShiftBucket> shiftList) {
        this.orderList = orderList;
        this.shiftList = shiftList;
    }

    public List<Order> getOrderList() {
        return orderList;
    }

    public void setOrderList(List<Order> orderList) {
        this.orderList = orderList;
    }

    public List<ShiftBucket> getShiftList() {
        return shiftList;
    }

    public void setShiftList(List<ShiftBucket> shiftList) {
        this.shiftList = shiftList;
    }

    public HardSoftScore getScore() {
        return score;
    }

    public void setScore(HardSoftScore score) {
        this.score = score;
    }
}
