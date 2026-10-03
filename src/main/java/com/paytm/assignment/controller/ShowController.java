package com.paytm.assignment.controller;

import com.paytm.assignment.dto.request.CreateShowRequest;
import com.paytm.assignment.dto.response.CreateShowResponse;
import com.paytm.assignment.dto.response.ShowStateResponse;
import com.paytm.assignment.service.ShowService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    @PostMapping
    public ResponseEntity<CreateShowResponse> create(@Valid @RequestBody CreateShowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(showService.create(request));
    }

    @GetMapping("/{id}")
    public ShowStateResponse getState(@PathVariable UUID id) {
        return showService.getState(id);
    }
}
