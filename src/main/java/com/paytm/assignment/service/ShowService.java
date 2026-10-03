package com.paytm.assignment.service;

import com.paytm.assignment.dto.request.CreateShowRequest;
import com.paytm.assignment.dto.response.CreateShowResponse;
import com.paytm.assignment.dto.response.ShowStateResponse;

import java.util.UUID;

public interface ShowService {
    CreateShowResponse create(CreateShowRequest request);
    ShowStateResponse getState(UUID showId);
}
