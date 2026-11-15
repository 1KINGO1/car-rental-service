# gRPC у Car Rental Service

Spring Boot **4.1.1**, Java **25**, Maven **3.9.16**. Зв'язок `booking-service → fleet-service` використовує Protobuf та HTTP/2. Дані автомобілів надходять із наявного `VehicleRepository`.

## Швидкий запуск без Docker

Установіть JDK 25 і задайте `JAVA_HOME`. Maven Wrapper завантажує Maven та залежності під час першого запуску.

```powershell
.\mvnw.cmd -B -Pgrpc-demo verify
.\scripts\demo-grpc.ps1 -SkipBuild
```

Скрипт запускає **два окремі Spring Boot процеси** з H2, створює два автомобілі, викликає всі чотири gRPC-методи через HTTP-фасад booking-service, перевіряє результати й завершує тільки запущені ним процеси. Порти за замовчуванням: HTTP fleet `18082`, HTTP booking `18083`, gRPC `19090`. Їх можна змінити параметрами `-FleetPort`, `-BookingPort`, `-GrpcPort`. Повторний запуск використовує нові бази в пам'яті.

```powershell
# Лише нові gRPC-тести
.\scripts\verify-grpc.ps1
# Усі тести командного проєкту
.\scripts\verify-grpc.ps1 -AllTests
```

Linux/macOS:

```bash
sh mvnw -B -Pgrpc-demo verify
```

`grpc-demo` є Maven-профілем, який додає H2 до runtime-залежностей fleet і booking. Spring-профіль `grpc` перемикає клієнт каталогу booking-service з HTTP на gRPC. Це різні профілі з різним призначенням.

## Контракт

Джерело: `grpc-contracts/src/main/proto/fleet.proto`. Java-класи генеруються під час Maven build, вручну їх редагувати не потрібно.

| RPC | Модель | Призначення |
| --- | --- | --- |
| GetVehicle | Unary | Один автомобіль за UUID |
| ListVehicles | Server streaming | Окреме повідомлення на автомобіль, до 100 |
| CalculateQuote | Client streaming | До 100 позицій від клієнта, одна підсумкова сума |
| WatchQuotes | Bidirectional streaming | Відповідь на кожну позицію до завершення вхідного потоку |

Суми передаються десятковими рядками та розраховуються через `BigDecimal`; валюта UAH. Оренда: 1..365 днів. `request_id` у потокових запитах: 1..64 символи. Усі RPC виконують лише читання; `CalculateQuote` не створює бронювання. Порожній client stream повертає count=0, total=0. Порожній bidi stream коректно завершується без повідомлень. Порожній список UUID у ListVehicles означає першу сторінку до 100 автомобілів. Відсутні UUID у списку пропускаються, дублікати об'єднуються.

## Інтеграція з бронюванням

За активного Spring-профілю `grpc` реалізація `CatalogClient` використовує `FleetGrpcClient`. Тому штатний `POST /api/bookings` теж отримує автомобіль через gRPC. Перевірка клієнта залишається наявним HTTP-викликом customer-service. Kafka/outbox та інші модулі збережено.

HTTP-фасад для демонстрації:

| Запит до booking-service | RPC до fleet-service |
| --- | --- |
| GET /api/grpc/vehicles/{uuid} | GetVehicle |
| GET /api/grpc/vehicles | ListVehicles |
| POST /api/grpc/quotes/client-stream | CalculateQuote |
| POST /api/grpc/quotes/bidi | WatchQuotes |

Тіло обох POST:

```json
[
  {"requestId":"a","vehicleId":"UUID автомобіля","days":2},
  {"requestId":"b","vehicleId":"UUID іншого автомобіля","days":3}
]
```

HTTP-фасад збирає результат у JSON. Це не потоковий HTTP/SSE API. Справжня двостороння взаємодія перевіряється прямим gRPC-тестом: клієнт чекає першу відповідь, надсилає наступний запит і лише потім закриває свій потік.

## Повтори

Єдине джерело політики: `grpc-client/src/main/resources/grpc/fleet-service-config.json`.

```json
{
  "methodConfig": [{
    "name": [{"service": "carrental.fleet.v1.FleetService"}],
    "retryPolicy": {
      "maxAttempts": 4,
      "initialBackoff": "0.5s",
      "maxBackoff": "2s",
      "backoffMultiplier": 2,
      "retryableStatusCodes": ["UNAVAILABLE"]
    }
  }]
}
```

`FleetChannels` завантажує JSON у `defaultServiceConfig`, викликає `enableRetry()` та забороняє DNS service-config підміняти цю політику. `maxAttempts=4` включає початковий виклик: не більше трьох повторів. Номінальні паузи: 0.5, 1, 2 с; gRPC додає jitter. Deadline за замовчуванням 5000 мс охоплює весь логічний виклик, включно з повторами. Буфер повтору: до 1 MiB на RPC і 4 MiB на канал.

Повтори дозволені лише до отримання response headers і в межах retry buffer. Після першої відповіді server/bidi stream автоматично не відновлюється: помилка передається клієнту. Відновлення з позиції потребувало б окремого протоколу cursor/sequence. `INVALID_ARGUMENT`, `NOT_FOUND`, `INTERNAL` не повторюються. Фізичне закриття активного потоку може завершитися `UNAVAILABLE` або `CANCELLED` залежно від транспорту.

У наявний Resilience4j HTTP retry не додається повторна політика gRPC. Інтеграційний тест підтверджує рівно 4 спроби навіть у штатному шляху створення бронювання.

## Конфігурація запуску

| Параметр | За замовчуванням | Значення |
| --- | --- | --- |
| SPRING_PROFILES_ACTIVE (booking) | без grpc | `grpc` вмикає gRPC-каталог |
| FLEET_GRPC_TARGET (booking) | localhost:9090 | Адреса fleet gRPC |
| FLEET_GRPC_PLAINTEXT (booking) | true | Для локального стенда; false вмикає transport TLS |
| FLEET_GRPC_DEADLINE_MS (booking) | 5000 | Deadline одного логічного виклику |
| FLEET_GRPC_PORT (fleet) | 9090 | Порт Netty gRPC сервера |

Для PostgreSQL збережені `DB_URL`, `DB_USER`, `DB_PASSWORD`. У `docker-compose.yml` booking уже має профіль `grpc` та адресу `fleet-service:9090`. Dockerfile включає обидва нові Maven-модулі. Порт 9090 доступний усередині мережі Compose; для локального скрипту Docker не потрібен.

Для TLS на сервері потрібні налаштований Spring SSL bundle та `spring.grpc.server.ssl.*`. У комплекті демонструється локальний plaintext-стенд. Старі Helm-чарти попередніх робіт не перемикаються автоматично на gRPC; для такого розгортання потрібні профіль grpc у booking, порт 9090 у Service fleet та відповідний дозвіл NetworkPolicy.

## Тести та докази

- `FleetGrpcIntegrationTest`: реальна Spring Boot конфігурація сервера, H2, TCP/HTTP2, чотири моделі, помилки валідації, порожні потоки, обмеження розміру, retry для кожної моделі, deadline, вичерпання спроб, відновлення недоступного TCP-порту та відсутність повтору після початку відповіді.
- `GrpcBookingIntegrationTest`: HTTP-запит до booking-service, gRPC-каталог, створення бронювання, правильний HTTP 503 і відсутність вкладених повторів. Зовнішні fleet/customer в цьому наборі є контрольованими мережевими fixtures.
- `scripts/demo-grpc.ps1`: окремі реальні процеси fleet і booking, усі чотири моделі, збережені JSON-результати та логи.

Ін'єкція збоїв існує тільки у `src/test`, production API для вмикання помилок немає. Докази: `docs/evidence/grpc/`; звіт: `docs/report/Grpc_Report.md`. Старі звіти в архіві стосуються попередніх робіт.

## Офіційні джерела

- Spring Boot gRPC: https://docs.spring.io/spring-boot/reference/io/grpc.html
- gRPC retries і момент commit: https://grpc.io/docs/guides/retry/
- gRPC service config: https://grpc.io/docs/guides/service-config/
