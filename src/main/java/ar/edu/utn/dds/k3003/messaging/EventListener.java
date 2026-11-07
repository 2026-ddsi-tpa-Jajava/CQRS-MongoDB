package ar.edu.utn.dds.k3003.messaging;

import ar.edu.utn.dds.k3003.events.IntegrationEvent;
import ar.edu.utn.dds.k3003.service.ReadModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

import static ar.edu.utn.dds.k3003.config.RabbitConfig.READ_MODEL_QUEUE;

@Component
public class EventListener {
    private static final Logger log = LoggerFactory.getLogger(EventListener.class);

    private final ReadModelService readModelService;
    private final ObjectMapper objectMapper;

    public EventListener(ReadModelService readModelService, ObjectMapper objectMapper) {
        this.readModelService = readModelService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = READ_MODEL_QUEUE)
    public void receive(Map<String, Object> message) {
        JsonNode node = objectMapper.valueToTree(message);
        log.info("Mensaje recibido de {}: {}", READ_MODEL_QUEUE, node);

        IntegrationEvent event;
        try {
            event = toEvent(node);
        } catch (IllegalArgumentException e) {
            // Un mensaje mal formado no se arregla reintentando: se descarta para no ciclar en la cola.
            log.error("Mensaje descartado de {}: {}. Mensaje: {}", READ_MODEL_QUEUE, e.getMessage(), node);
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }

        try {
            readModelService.apply(event);
        } catch (RuntimeException e) {
            log.error("Error aplicando evento id={} tipo={} desde {}; se reintentara",
                    event.id(), event.tipo(), READ_MODEL_QUEUE, e);
            throw e;
        }
    }

    private IntegrationEvent toEvent(JsonNode node) {
        String id = text(node, "id", null);
        if (id == null) {
            throw new IllegalArgumentException("El evento de RabbitMQ no contiene id");
        }
        String type = text(node, "tipo", null);
        if (type == null) {
            throw new IllegalArgumentException("El evento de RabbitMQ no contiene tipo");
        }
        JsonNode versionNode = node.get("version");
        if (versionNode == null || !versionNode.isIntegralNumber()) {
            throw new IllegalArgumentException("El evento de RabbitMQ no contiene una version valida");
        }
        Long version = versionNode.longValue();
        JsonNode occurredAtNode = node.get("ocurridoEn");
        if (occurredAtNode == null || !occurredAtNode.isTextual()) {
            throw new IllegalArgumentException("El evento de RabbitMQ no contiene ocurridoEn valido");
        }
        Instant occurredAt;
        try {
            occurredAt = objectMapper.convertValue(occurredAtNode, Instant.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El evento de RabbitMQ no contiene ocurridoEn valido", e);
        }
        JsonNode payload = node.get("payload");
        if (payload == null || payload.isNull()) {
            throw new IllegalArgumentException("El evento de RabbitMQ no contiene payload");
        }
        return new IntegrationEvent(id, type, version, occurredAt, payload);
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }
}
