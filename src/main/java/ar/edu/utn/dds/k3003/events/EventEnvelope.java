package ar.edu.utn.dds.k3003.events;

import java.time.Instant;

public record EventEnvelope(
        String id,
        String tipo,
        Long version,
        Instant ocurridoEn,
        Object payload) {
}
