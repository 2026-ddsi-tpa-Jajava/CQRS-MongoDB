package ar.edu.utn.dds.k3003.controller;

import ar.edu.utn.dds.k3003.events.IntegrationEvent;
import ar.edu.utn.dds.k3003.service.ReadModelService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/eventos")
public class EventController {
    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final ReadModelService readModelService;

    public EventController(ReadModelService readModelService) {
        this.readModelService = readModelService;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@Valid @RequestBody IntegrationEvent event) {
        log.info("POST /eventos: evento id={} tipo={} version={} ocurridoEn={} payload={}",
                event.id(), event.tipo(), event.version(), event.ocurridoEn(), event.payload());
        readModelService.apply(event);
        return ResponseEntity.accepted().build();
    }
}
