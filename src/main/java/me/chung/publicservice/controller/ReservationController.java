package me.chung.publicservice.controller;

import lombok.RequiredArgsConstructor;
import me.chung.publicservice.dto.ReservationRequest;
import me.chung.publicservice.dto.ReservationResponse;
import me.chung.publicservice.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/programs/{programId}/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(
            @PathVariable("programId") Long programId,
            @RequestBody ReservationRequest request
    ) {
        return reservationService.reserve(programId, request.participantId());
    }
}
