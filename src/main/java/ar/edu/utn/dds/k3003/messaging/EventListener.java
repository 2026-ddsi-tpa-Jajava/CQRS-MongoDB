package ar.edu.utn.dds.k3003.messaging;

import ar.edu.utn.dds.k3003.events.IntegrationEvent;
import ar.edu.utn.dds.k3003.service.ReadModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

import static ar.edu.utn.dds.k3003.config.RabbitConfig.READ_MODEL_QUEUE;

@Component
public class EventListener {
    private final ReadModelService readModelService;
    private final ObjectMapper objectMapper;

    public EventListener(ReadModelService readModelService, ObjectMapper objectMapper) {
        this.readModelService = readModelService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = READ_MODEL_QUEUE)
    public void receive(Map<String, Object> message) {
        JsonNode node = objectMapper.valueToTree(message);
        String id = text(node, "id", null);
        if (id == null) {
            throw new IllegalArgumentException("El evento de RabbitMQ no contiene id");
        }
        String type = text(node, "tipo", null);
        if (type == null) {
            throw new IllegalArgumentException("El evento de RabbitMQ no contiene tipo");
        }
        Long version = node.has("version") ? node.get("version").asLong() : 0L;
        Instant occurredAt = node.has("ocurridoEn")
                ? objectMapper.convertValue(node.get("ocurridoEn"), Instant.class)
                : Instant.now();
        JsonNode payload = node.get("payload");
        if (payload == null || payload.isNull()) {
            throw new IllegalArgumentException("El evento de RabbitMQ no contiene payload");
        }
        readModelService.apply(new IntegrationEvent(id, type, version, occurredAt, payload));
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }
}
