# Взаємодія сервісів через gRPC

Груповий звіт до командного проєкту Car Rental Service

**Команда:** Лис Тарас, Тарасенко Тимофій

Spring Boot 4.1.1 · Java 25 · 9 листопада 2026 року

> Звіт зроблено частково з допомогою ШІ.

У проєкті реалізовано всі чотири моделі gRPC між booking-service та fleet-service. Налаштовано повтори для UNAVAILABLE та перевірено їхню поведінку при тимчасових помилках і недоступному TCP-порті. Повна перевірка завершилася успішно: 71 тест, 0 помилок, 0 пропусків; із них 23 нові gRPC-перевірки.

## Мета і структура рішення

Мета роботи — додати контрактну взаємодію між наявними сервісами оренди автомобілів, реалізувати Unary, Server streaming, Client streaming і Bidirectional streaming та зафіксувати результати перевірки retries.

```text
HTTP client
    |
booking-service + FleetGrpcClient
    |  gRPC / HTTP2 / Protobuf
    v
fleet-service + FleetGrpcService
    |
VehicleRepository -> PostgreSQL / H2 in tests
```

grpc-contracts містить fleet.proto і згенеровані Java-класи. grpc-client містить спільний клієнт, канал та JSON-політику повторів. FleetGrpcService працює з наявним VehicleRepository. Профіль grpc перемикає CatalogClient у booking-service на новий транспорт; штатне створення бронювання також використовує gRPC-каталог.

| Компонент | Фактичне середовище перевірки |
| --- | --- |
| Spring Boot / Java | 4.1.1 / Eclipse Temurin 25.0.5 |
| Maven / gRPC Java | 3.9.16 / 1.83.1 |
| Protobuf / Spring gRPC | 4.35.1 / 1.1.1 |
| Транспорт / база | TCP HTTP/2 / H2 у режимі PostgreSQL |
| Демонстрація | Два окремі JVM-процеси на Windows |

## Контракт і чотири моделі викликів

Контракт збережено у `grpc-contracts/src/main/proto/fleet.proto`. Maven генерує Java-класи через protobuf-maven-plugin, конфігурацію та версії якого надає Spring Boot parent.

```text
syntax = "proto3";
package carrental.fleet.v1;

service FleetService {
  rpc GetVehicle (VehicleRequest)
      returns (VehicleReply);
  rpc ListVehicles (ListVehiclesRequest)
      returns (stream VehicleReply);
  rpc CalculateQuote (stream QuoteItem)
      returns (QuoteSummary);
  rpc WatchQuotes (stream QuoteItem)
      returns (stream QuoteLine);
}
```

| Метод і модель | Реалізована поведінка |
| --- | --- |
| GetVehicle; Unary | Один UUID автомобіля → одна відповідь з маркою, моделлю, номером і тарифом. |
| ListVehicles; Server streaming | Один запит → окремі повідомлення автомобілів. До 100 результатів, без дублікатів UUID. |
| CalculateQuote; Client streaming | Потік позицій оренди → одна відповідь із кількістю позицій, загальною сумою і валютою. |
| WatchQuotes; Bidirectional streaming | Кожна вхідна позиція одразу породжує відповідь з request_id і сумою. Сервер не чекає закриття вхідного потоку. |

### Перевірка даних і завершення потоків

UUID має канонічний формат; кількість днів — від 1 до 365; request_id — від 1 до 64 символів. Потік містить не більше 100 позицій. Суми розраховуються через BigDecimal і передаються в Protobuf десятковими рядками у гривнях. Це виключає похибки двійкових дробів.

Неправильні дані повертають INVALID_ARGUMENT, відсутній автомобіль у Unary — NOT_FOUND, перевищення 100 позицій — RESOURCE_EXHAUSTED. Порожній client stream повертає нульовий підсумок; порожній bidi stream завершується без відповідей. Сервер враховує скасування виклику.

## Конфігурація повторів і межі відновлення

Єдине джерело політики — `grpc-client/src/main/resources/grpc/fleet-service-config.json`. Вона поширюється на всі методи FleetService.

```json
{
  "methodConfig": [{
    "name": [{
      "service": "carrental.fleet.v1.FleetService"
    }],
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

FleetChannels завантажує цей JSON через `defaultServiceConfig` та вмикає `enableRetry()`. DNS service-config не підміняє налаштування. `maxAttempts = 4` означає початкову спробу і максимум три повтори. Номінальні паузи становлять 0.5, 1 і 2 секунди; gRPC додає випадкове відхилення затримки.

Deadline одного логічного виклику за замовчуванням становить 5000 мс та охоплює всі спроби. Буфер історії для повтору обмежено 1 MiB на RPC і 4 MiB на канал. INVALID_ARGUMENT, NOT_FOUND та INTERNAL не входять до `retryableStatusCodes`.

### Коли повтор є безпечним

Усі чотири RPC виконують лише читання та розрахунки. CalculateQuote не створює бронювання, тому повторення запитів до початку відповіді не дублює бізнес-операцій. При тимчасовій помилці доступу до БД сервер повертає UNAVAILABLE.

Після отримання response headers виклик вважається committed, і автоматичні retries припиняються. Тому обрив server stream або bidi stream після першої відповіді передається клієнту. Відновлення з останньої позиції вимагало б окремого протоколу cursor/sequence; цього механізму в цій роботі немає.

У штатному шляху бронювання gRPC-помилки перетворюються на HTTP-статуси без додаткової HTTP retry-політики. Тест підтверджує рівно 4 мережеві спроби після постійного UNAVAILABLE, а не множення спроб двох механізмів.

## Тестування і результати

Команда повної перевірки: `./mvnw.cmd -B -Pgrpc-demo verify`. Вона завершилася BUILD SUCCESS 09.11.2026 о 06:59:35 за київським часом; тривалість Maven — 1 хв 19 с. Зведення сформовано з XML-звітів Surefire.

| Набір | Тести | Помилки / пропуски |
| --- | --- | --- |
| FleetGrpcIntegrationTest | 19 | 0 / 0 |
| GrpcBookingIntegrationTest | 4 | 0 / 0 |
| Наявні тести інших сценаріїв | 48 | 0 / 0 |
| Усього | 71 | 0 / 0 |

| Сценарій | Фактичний результат |
| --- | --- |
| Чотири моделі RPC | Успішно; bidi відповідає до half-close клієнта. |
| Дві помилки UNAVAILABLE до headers | Для кожного з 4 RPC: успіх на третій спробі; previous = 0, 1, 2. |
| Постійний UNAVAILABLE | Рівно 4 спроби, фінальний UNAVAILABLE. |
| Недоступний TCP-порт | Сервер запускається після першої відмови; той самий логічний виклик завершується на 4-й спробі. |
| INVALID_ARGUMENT / NOT_FOUND / INTERNAL | По одній спробі, без автоматичного повтору. |
| Deadline 100 мс | DEADLINE_EXCEEDED до вичерпання 4 спроб. |
| Обрив server stream після відповіді | Одна отримана відповідь, одна спроба, UNAVAILABLE. |
| Зупинка сервера під час bidi | Потік завершується транспортною помилкою, повтору немає. |
| Створення бронювання | Дані отримано через gRPC; сума 2400.00. |
| Вичерпання retries під час бронювання | HTTP 503, рівно 4 gRPC-спроби. |

Основний набір запускає справжній Spring Boot gRPC-сервер і H2. Збої до headers інʼєктує тестовий ServerInterceptor у додатковому TCP-сервері з тією самою реалізацією FleetGrpcService. Інʼєкція недоступна у production-коді. Тести booking використовують контрольовані мережеві fleet/customer fixtures; окремий demo запускає обидва реальні сервіси.

Повторна перевірка gRPC-набору 15.11.2026 також завершилася успішно: 23/23 тести (19 + 4), BUILD SUCCESS.

## Логи спроб і демонстрація роботи

Нижче наведено фактичні рядки успішного запуску. Повний консольний вивід збережено в `docs/evidence/grpc/maven-verify.log`, вибірку — в `attempts.log`.

```text
EVIDENCE method=GetVehicle attempt=1 previous=0
  injectedStatus=UNAVAILABLE
EVIDENCE method=GetVehicle attempt=2 previous=1
  injectedStatus=UNAVAILABLE
EVIDENCE method=GetVehicle attempt=3 previous=2
  injectedStatus=NONE
EVIDENCE retry GetVehicle attempts=3
  previous=[0,1,2] result=OK
```

Довгі рядки вище перенесено для читабельності. Тест зчитує `grpc-previous-rpc-attempts` із metadata і перевіряє послідовність 0, 1, 2. Це окремі транспортні спроби одного виклику; повторних викликів бізнес-клієнтом немає.

```text
EVIDENCE unavailable-port client-attempt=1 previous=0
EVIDENCE unavailable-port client-attempt=2 previous=1
EVIDENCE unavailable-port client-attempt=3 previous=2
EVIDENCE unavailable-port client-attempt=4 previous=3
EVIDENCE TCP recovery same logical call attempts=4 result=OK
```

### Два окремі процеси Spring Boot

Скрипт `demo-grpc.ps1` запустив fleet-service на HTTP 18082 / gRPC 19090 та booking-service на HTTP 18083. Створено Toyota Corolla з тарифом 1200.00 і Skoda Octavia з тарифом 1500.00. Усі чотири gRPC-виклики завершилися статусом OK.

| Модель | Зафіксований результат |
| --- | --- |
| Unary | Автомобіль GRPC100, тариф 1200.00 |
| Server streaming | Два автомобілі GRPC100 і GRPC200 |
| Client streaming | 2 позиції; 1200 × 2 + 1500 × 3 = 6900.00 UAH |
| Bidirectional streaming | Відповіді a → 2400.00, b → 4500.00 UAH |

JSON-відповіді: `docs/evidence/grpc/demo/results.json`. Логи обох JVM: `demo/fleet.log` і `demo/booking.log`. HTTP-фасад збирає відповіді в JSON; справжню незалежність напрямків bidi додатково підтверджує прямий gRPC-тест із очікуванням першої відповіді до надсилання другої позиції.

## Відтворення і висновки

### Запуск із вихідного коду

1. Розпакувати архів, установити JDK 25 і вказати `JAVA_HOME`. Відкрити PowerShell у корені `car-rental-service`. Під час першої збірки потрібен доступ до Maven Central.

```text
.\mvnw.cmd -B -Pgrpc-demo verify
.\scripts\demo-grpc.ps1 -SkipBuild
```

2. Для окремої перевірки нових сценаріїв виконати `scripts/verify-grpc.ps1`. Параметр `-AllTests` запускає всі тести. Скрипт демонстрації перевіряє відповіді й завершує лише ті процеси, які сам запустив.

3. Maven-профіль `grpc-demo` додає H2 у runtime двох сервісів. Spring-профіль `grpc` активує gRPC-каталог у booking-service. Для PostgreSQL використовуються наявні `DB_URL`, `DB_USER` і `DB_PASSWORD`; у Compose уже задано `FLEET_GRPC_TARGET=fleet-service:9090`.

### Склад матеріалів

| Артефакт | Зміст |
| --- | --- |
| README_GRPC.md | Архітектура, команди, HTTP-фасад, параметри, обмеження |
| grpc-contracts / grpc-client | Контракт, генерація Java-класів, клієнт і retry policy |
| fleet-service / booking-service | Сервер, інтеграція каталогу, демонстраційний API і тести |
| docs/evidence/grpc | Maven-логи, metadata спроб, XML Surefire, JSON-зведення, demo-логи |
| Grpc_Report.md / docs/report/Grpc_Report.md | Текстова версія звіту |

### Межі проведеної перевірки

Перевірено локальні JVM-процеси, TCP/HTTP2 та H2. Docker/PostgreSQL і Kubernetes у цій роботі не запускалися. Старі Helm-чарти збережено; для їх переведення на gRPC потрібні порт Service, профіль клієнта й правила NetworkPolicy. Локальний стенд використовує plaintext; TLS потребує сертифікатів і Spring SSL bundle.

### Висновок

Виконано чотири вимоги завдання: міжсервісна gRPC-взаємодія на заданих версіях, proto-контракт із чотирма моделями, retry policy для UNAVAILABLE та перевірка роботи при збоях. Повтори підтверджено metadata й лічильниками спроб; успішну взаємодію двох реальних сервісів підтверджено збереженими логами та JSON-результатами.

### Офіційні джерела

- Spring Boot gRPC: https://docs.spring.io/spring-boot/reference/io/grpc.html
- gRPC retry і commit: https://grpc.io/docs/guides/retry/
- Service config: https://grpc.io/docs/guides/service-config/
