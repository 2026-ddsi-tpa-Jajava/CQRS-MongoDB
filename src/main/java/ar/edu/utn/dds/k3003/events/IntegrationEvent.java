package ar.edu.utn.dds.k3003.events;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record IntegrationEvent(
        @NotBlank String id,
        @NotBlank String tipo,
        Long version,
        Instant ocurridoEn,
        @NotNull JsonNode payload) {
}
