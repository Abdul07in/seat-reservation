package com.paytm.assignment.dto.response;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ShowStateResponse(String id, String name, int totalSeats, SeatCounts counts, List<SeatStateResponse> seats) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SeatCounts(int available, int held, int confirmed) {
    }
}
