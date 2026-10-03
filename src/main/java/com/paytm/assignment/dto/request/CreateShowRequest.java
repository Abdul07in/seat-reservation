package com.paytm.assignment.dto.request;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateShowRequest(
        @NotBlank @Size(max = 150) String name,
        @NotEmpty @UniqueElements List<@NotBlank @Size(max = 20) String> seats,
        @NotNull @Positive Long pricePaise,
        @Positive Integer perUserLimit) {
}
