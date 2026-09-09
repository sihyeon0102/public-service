package me.chung.publicservice.service;

import lombok.RequiredArgsConstructor;
import me.chung.publicservice.domain.Program;
import me.chung.publicservice.domain.Reservation;
import me.chung.publicservice.dto.ReservationResponse;
import me.chung.publicservice.repository.ProgramRepository;
import me.chung.publicservice.repository.ReservationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ProgramRepository programRepository;
    private final ReservationRepository reservationRepository;

    @Transactional
    public ReservationResponse reserve(Long programId, String participantId) {
        if (participantId == null || participantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "participantId is required");
        }

        Program program = programRepository.findByIdForUpdate(programId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Program not found: " + programId));

        if (program.isFull()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Program is full");
        }

        program.reserve();
        try {
            Reservation reservation = reservationRepository.saveAndFlush(
                    new Reservation(program, participantId));
            return ReservationResponse.from(reservation, program);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Participant already reserved this program", exception);
        }
    }
}
