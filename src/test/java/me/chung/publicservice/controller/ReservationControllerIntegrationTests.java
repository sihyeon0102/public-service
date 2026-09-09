package me.chung.publicservice.controller;

import java.util.ArrayList;
import java.util.List;
import me.chung.publicservice.domain.Facility;
import me.chung.publicservice.domain.Program;
import me.chung.publicservice.repository.FacilityRepository;
import me.chung.publicservice.repository.ProgramRepository;
import me.chung.publicservice.repository.ReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.cache.type=none",
        "spring.jpa.show-sql=false"
})
class ReservationControllerIntegrationTests {

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private FacilityRepository facilityRepository;

    @Autowired
    private ProgramRepository programRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> programIds = new ArrayList<>();
    private MockMvc mockMvc;
    private Facility facility;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        facility = facilityRepository.findAll(
                PageRequest.of(0, 1, Sort.by("id"))).getContent().getFirst();
    }

    @AfterEach
    void cleanUp() {
        for (Long programId : programIds) {
            jdbcTemplate.update("DELETE FROM reservation WHERE program_id = ?", programId);
            jdbcTemplate.update("DELETE FROM program WHERE id = ?", programId);
        }
    }

    @Test
    void reservationIncreasesCountAndStoresReservation() throws Exception {
        Program program = createProgram(2);

        mockMvc.perform(post("/api/programs/{programId}/reservations", program.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantId\":\"participant-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.programId").value(program.getId()))
                .andExpect(jsonPath("$.participantId").value("participant-1"))
                .andExpect(jsonPath("$.reservedCount").value(1))
                .andExpect(jsonPath("$.capacity").value(2))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        assertThat(programRepository.findById(program.getId()).orElseThrow().getReservedCount())
                .isEqualTo(1);
        assertThat(reservationRepository.countByProgramId(program.getId())).isEqualTo(1);
    }

    @Test
    void fullProgramReturnsConflict() throws Exception {
        Program program = createProgram(1);
        reserve(program.getId(), "participant-1", 201);

        reserve(program.getId(), "participant-2", 409);

        assertThat(programRepository.findById(program.getId()).orElseThrow().getReservedCount())
                .isEqualTo(1);
        assertThat(reservationRepository.countByProgramId(program.getId())).isEqualTo(1);
    }

    @Test
    void duplicateParticipantReturnsConflict() throws Exception {
        Program program = createProgram(2);
        reserve(program.getId(), "same-participant", 201);

        reserve(program.getId(), "same-participant", 409);

        assertThat(programRepository.findById(program.getId()).orElseThrow().getReservedCount())
                .isEqualTo(1);
        assertThat(reservationRepository.countByProgramId(program.getId())).isEqualTo(1);
    }

    @Test
    void missingProgramReturnsNotFound() throws Exception {
        reserve(-1L, "participant-1", 404);
    }

    private Program createProgram(int capacity) {
        Program program = programRepository.saveAndFlush(
                new Program(facility, "Reservation Test Program", capacity));
        programIds.add(program.getId());
        return program;
    }

    private void reserve(Long programId, String participantId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/programs/{programId}/reservations", programId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"participantId\":\"" + participantId + "\"}"))
                .andExpect(status().is(expectedStatus));
    }
}
