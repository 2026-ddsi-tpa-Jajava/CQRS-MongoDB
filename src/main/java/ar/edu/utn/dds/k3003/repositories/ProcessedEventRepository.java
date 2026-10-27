package ar.edu.utn.dds.k3003.repositories;

import ar.edu.utn.dds.k3003.model.ProcessedEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProcessedEventRepository extends MongoRepository<ProcessedEvent, String> {
}
