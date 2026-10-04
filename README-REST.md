# Лабораторна робота 4 — REST міжсервісна взаємодія

Обраний мікросервіс: **booking-service** системи прокату автомобілів.
Java **25**, Spring Boot **4.1.1**, Maven **3.9+**.

## Реалізація

- `CatalogClient` отримує автомобіль за UUID через `@HttpExchange` / `@GetExchange`.
- `CustomerClient` декларативно перевіряє клієнта. Обидва клієнти використовує наявний `RentalCatalog` у процесі бронювання.
- `RentalCatalog.Vehicle` та `RentalCatalog.Eligibility` — Java records з `@JsonIgnoreProperties(ignoreUnknown = true)`.
- `CatalogClientConfiguration`: JDK HttpClient з connect timeout **2 с**, `JdkClientHttpRequestFactory` з read timeout **3 с**, `RestClient`, `RestClientAdapter`, `HttpServiceProxyFactory`. RestTemplate не використовується.
- `CorrelationIdFilter` приймає `X-Correlation-Id` (1–128 символів: літери, цифри, крапка, дефіс, підкреслення), або генерує UUID. Зберігає його в MDC, додає до відповіді та очищує контекст у `finally`.
- `CorrelationIdInterceptor` автоматично додає цей ID до вихідного HTTP-запиту. Для виклику поза вхідним HTTP-запитом генерує UUID. Обробка синхронна; перенесення MDC до дочірніх асинхронних задач тут не потрібне.
- Профіль `http-demo` вмикає демонстраційний та mock-контролери, вимикає компоненти бронювання з БД і автоконфігурацію DataSource. Звичайний запуск зберігає роботу з PostgreSQL та зовнішніми сервісами через `FLEET_URL` / `CUSTOMER_URL`.

## Збірка та запуск без PostgreSQL / Docker

Встановіть JDK 25, задайте `JAVA_HOME`, додайте Maven до `PATH`. Із кореня проєкту:

```shell
mvn -B -ntp -pl booking-service -am clean package
java -jar booking-service/target/app.jar --spring.profiles.active=http-demo
```

Порт демонстрації — `8090`. Для іншого порту додайте `--server.port=8095`; адреса локального mock автоматично використовує цей порт.

У другому терміналі Windows:

```powershell
curl.exe -i -H "X-Correlation-Id: lab4-success-001" http://localhost:8090/demo/vehicles/00000000-0000-0000-0000-000000000001
curl.exe -i -H "X-Correlation-Id: lab4-timeout-001" http://localhost:8090/demo/vehicles/00000000-0000-0000-0000-000000000005
```

Перший запит повертає HTTP 200 та типізований DTO. Mock додає `futureField`, якого немає в DTO: десеріалізація успішна, зайве поле не потрапляє до відповіді контролера.

Для UUID із закінченням `000005` mock очікує **5000 мс**. Клієнт перериває очікування приблизно через **3000 мс** із `ResourceAccessException`, причиною якого є `HttpTimeoutException`. Демонстраційний контролер записує стек винятку та повертає HTTP 504. Обробник показує транспортні помилки як 504; доведення саме таймауту забезпечують причина винятку та автоматизований тест. Серверний `sleep` може завершитися пізніше: таймаут обмежує очікування клієнта.

## Перевірки та докази

`CatalogClientTest` запускає справжній Spring HTTP-сервер на випадковому порту та окрему HTTP-заглушку JDK, без БД:

1. Успішний типізований результат і передавання correlation ID.
2. Tolerant Reader для обох DTO із суворим Jackson mapper.
3. Генерація ID поза вхідним запитом без забруднення MDC.
4. Передавання ID від вхідного контролера до залежного сервісу; новий ID у наступному запиті без заголовка.
5. `ResourceAccessException` із кореневою причиною `HttpTimeoutException` через 2.5–4.5 с при затримці 5 с.

`docs/rest-evidence/` містить повний `application.log`, HTTP-відповіді, лог успішної збірки, звіт **5 tests / 0 failures / 0 errors**, а також `success.png` і `timeout.png`. PNG — браузерні скріншоти HTML-перегляду дослівних уривків реальних логів і відповідей; вихідні HTML додані для прозорості.

Конфігурація відповідає індивідуальному завданню на слайдах 56–57 лекції. Додаткові теми лекції (Circuit Breaker, Retry, Bulkhead) не входять до цього завдання.

Офіційні джерела: [HTTP interfaces](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html), [JdkClientHttpRequestFactory](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/client/JdkClientHttpRequestFactory.html).
