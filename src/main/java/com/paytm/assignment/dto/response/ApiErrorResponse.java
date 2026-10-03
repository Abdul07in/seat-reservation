package com.paytm.assignment.dto.response;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String requestId,
        Map<String, String> details) {
}
