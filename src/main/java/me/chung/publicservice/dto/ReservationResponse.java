package me.chung.publicservice.dto;

import java.time.LocalDateTime;
import me.chung.publicservice.domain.Program;
import me.chung.publicservice.domain.Reservation;

public record ReservationResponse(
        Long id,
        Long programId,
        String participantId,
        LocalDateTime createdAt,
        int reservedCount,
        int capacity
) {
    public static ReservationResponse from(Reservation reservation, Program program) {
        return new ReservationResponse(
                reservation.getId(),
                program.getId(),
                reservation.getParticipantId(),
                reservation.getCreatedAt(),
                program.getReservedCount(),
                program.getCapacity()
        );
    }
}
