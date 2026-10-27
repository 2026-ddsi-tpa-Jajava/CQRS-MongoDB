package ar.edu.utn.dds.k3003.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("processed_events")
public class ProcessedEvent {
    @Id
    private String id;
    private String type;
    private long version;
    private Instant occurredAt;
    private Instant processedAt;

    public ProcessedEvent() {
    }

    public ProcessedEvent(String id, String type, long version, Instant occurredAt, Instant processedAt) {
        this.id = id;
        this.type = type;
        this.version = version;
        this.occurredAt = occurredAt;
        this.processedAt = processedAt;
    }

    public String getId() {
        return id;
    }
}
