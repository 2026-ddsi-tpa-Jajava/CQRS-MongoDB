package ar.edu.utn.dds.k3003.service;

import ar.edu.utn.dds.k3003.events.IntegrationEvent;
import ar.edu.utn.dds.k3003.model.ProcessedEvent;
import ar.edu.utn.dds.k3003.model.ReadDocument;
import ar.edu.utn.dds.k3003.repositories.ProcessedEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ReadModelService {
    private static final Set<String> DONOR_TYPES = Set.of("DONADOR_REGISTRADO", "DONADOR_ACTUALIZADO");
    private static final Set<String> NEED_TYPES = Set.of("NECESIDAD_REGISTRADA", "NECESIDAD_ACTUALIZADA", "NECESIDAD_SATISFECHA");
    private static final Set<String> WAREHOUSE_TYPES = Set.of("DEPOSITO_REGISTRADO", "DEPOSITO_ACTUALIZADO");
    private static final Set<String> DONATION_TYPES = Set.of("DONACION_REGISTRADA", "DONACION_ACTUALIZADA", "DONACION_ESTADO_CAMBIADO");
    private static final Set<String> ASSIGNMENT_TYPES = Set.of("ASIGNACION_REGISTRADA", "ASIGNACION_ESTADO_CAMBIADO", "ENTREGA_REGISTRADA");
    private static final Set<String> PRODUCT_TYPES = Set.of("PRODUCTO_REGISTRADO", "PRODUCTO_ACTUALIZADO");
    private static final Set<String> ENTITY_TYPES = Set.of("ENTIDAD_REGISTRADA", "ENTIDAD_ACTUALIZADA");
    private static final Set<String> BADGE_TYPES = Set.of("INSIGNIA_ASIGNADA", "INSIGNIA_ACTUALIZADA");
    private static final Set<String> MISSION_TYPES = Set.of("MISION_ASIGNADA", "MISION_PROCESADA");

    private final MongoTemplate mongoTemplate;
    private final ProcessedEventRepository processedEvents;
    private final ObjectMapper objectMapper;

    public ReadModelService(MongoTemplate mongoTemplate, ProcessedEventRepository processedEvents,
                            ObjectMapper objectMapper) {
        this.mongoTemplate = mongoTemplate;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    public synchronized void apply(IntegrationEvent event) {
        long version = event.version() == null ? 0L : event.version();
        Instant occurredAt = event.ocurridoEn() == null ? Instant.now() : event.ocurridoEn();
        if (processedEvents.existsById(event.id())) {
            return;
        }

        Map<String, Object> payload = objectMapper.convertValue(
                event.payload(), new TypeReference<>() {
                });
        String collection = collectionFor(event.tipo());
        if (collection != null) {
            String aggregateId = aggregateId(payload, event.id());
            ReadDocument current = mongoTemplate.findById(aggregateId, ReadDocument.class, collection);
            if (current == null || isNewer(version, occurredAt, current)) {
                Map<String, Object> fields = current == null ? new HashMap<>() : new HashMap<>(current.getFields());
                fields.putAll(payload);
                fields.put("ultimaActualizacion", occurredAt);
                mongoTemplate.save(new ReadDocument(aggregateId, version, occurredAt, fields), collection);
            }
        }
        try {
            processedEvents.save(new ProcessedEvent(event.id(), event.tipo(), version, occurredAt, Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // A concurrent delivery of the same event has already been applied.
        }
    }

    public void reset() {
        for (String collection : Set.of("donadores", "necesidades", "depositos", "donaciones",
                "asignaciones", "productos", "entidades", "insignias", "misiones", "processed_events")) {
            mongoTemplate.dropCollection(collection);
        }
    }

    private boolean isNewer(long version, Instant occurredAt, ReadDocument current) {
        return version > current.getVersion()
                || version == current.getVersion() && occurredAt.isAfter(current.getUpdatedAt());
    }

    private String aggregateId(Map<String, Object> payload, String fallback) {
        for (String key : new String[]{"id", "_id", "donadorID", "necesidadID", "depositoID", "productoID"}) {
            Object value = payload.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return fallback;
    }

    private String collectionFor(String type) {
        String normalized = type.toUpperCase(Locale.ROOT);
        if (DONOR_TYPES.contains(normalized)) return "donadores";
        if (NEED_TYPES.contains(normalized)) return "necesidades";
        if (WAREHOUSE_TYPES.contains(normalized)) return "depositos";
        if (DONATION_TYPES.contains(normalized)) return "donaciones";
        if (ASSIGNMENT_TYPES.contains(normalized)) return "asignaciones";
        if (PRODUCT_TYPES.contains(normalized)) return "productos";
        if (ENTITY_TYPES.contains(normalized)) return "entidades";
        if (BADGE_TYPES.contains(normalized)) return "insignias";
        if (MISSION_TYPES.contains(normalized)) return "misiones";
        return null;
    }
}
