package ar.edu.utn.dds.k3003.controller;

import ar.edu.utn.dds.k3003.service.ReadModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reset")
public class ResetController {
    private static final Logger log = LoggerFactory.getLogger(ResetController.class);

    private final ReadModelService readModelService;

    public ResetController(ReadModelService readModelService) {
        this.readModelService = readModelService;
    }

    @DeleteMapping
    public ResponseEntity<Void> reset() {
        log.warn("DELETE /reset: se solicito limpiar el modelo de lectura");
        readModelService.reset();
        return ResponseEntity.noContent().build();
    }
}
