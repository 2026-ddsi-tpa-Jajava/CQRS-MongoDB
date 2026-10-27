# Servicio de Consultas - DonaTrack

Servicio de lectura centralizado para la Entrega 6. Mantiene un modelo de lectura
en MongoDB, actualizado asincrónicamente a partir de eventos publicados por los
cuatro módulos del sistema.

## Ejecutar localmente

Requisitos: Java 21, Maven y MongoDB. RabbitMQ es opcional; se utiliza para
consumir eventos desde `donaciones.queue`.

```powershell
./mvnw.cmd spring-boot:run
```

Variables principales:

| Variable | Valor por defecto | Uso |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/donatrack_read` | Conexión a MongoDB |
| `RABBITMQ_HOST` | `localhost` | Host de RabbitMQ |
| `RABBITMQ_PORT` | `5672` | Puerto de RabbitMQ |
| `RABBITMQ_USERNAME` | `guest` | Usuario de RabbitMQ |
| `RABBITMQ_PASSWORD` | `guest` | Contraseña de RabbitMQ |
| `PORT` | `8080` | Puerto HTTP |

## API

- `POST /eventos`: recibe un evento de integración y actualiza el modelo de lectura.
- `GET /consultas/donadores/{id}`: ficha integral de un donador.
- `GET /consultas/necesidades-urgentes?urgenciaMinima=1&tipo=EXTRAORDINARIA&categoria=ALIMENTOS&subcategoriaId=...`
- `GET /consultas/depositos?desde=2026-01-01T00:00:00&hasta=2026-12-31T23:59:59`
- `GET /consultas/calidad`
- `DELETE /reset`: limpia el modelo de lectura (solo para desarrollo/demo).

Los eventos deben tener la forma:

```json
{
  "id": "evento-123",
  "tipo": "DONACION_REGISTRADA",
  "version": 1,
  "ocurridoEn": "2026-10-26T20:00:00Z",
  "payload": {
    "id": "12",
    "donadorID": "4",
    "productoID": "7",
    "cantidad": 30
  }
}
```

`id` es obligatorio e idempotente. `version` o `ocurridoEn` se usan para
ignorar eventos repetidos o más viejos que el estado ya proyectado.