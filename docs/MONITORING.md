# Лабораторна робота 6: моніторинг і сповіщення

Проєкт містить чотири Spring Boot мікросервіси: `customer-service`, `fleet-service`, `booking-service`, `payment-service`. Спільний модуль `observability` додає всі п'ять типів Micrometer-метрик у кожний сервіс. PostgreSQL і сервіси розгортаються чинним Helm chart; моніторинг підключається файлом `k8s/values.monitoring.yaml`.

## Запуск у Kubernetes

Потрібні Docker, Kubernetes із робочим `kubectl` context, Helm 3, PowerShell. Для Docker Desktop слід увімкнути Kubernetes і використовувати Linux containers. У кластері має бути default StorageClass для PVC чотирьох баз даних. Перевірте поточний кластер:

```powershell
kubectl config current-context
kubectl get nodes
kubectl get storageclass
```

Побудова образів і розгортання в окремому namespace:

```powershell
./k8s/scripts/deploy-monitoring.ps1 -BuildImages
```

Скрипт створює namespace `car-rental-lab06`, генерує відсутні DB Secrets у пам'яті, зберігає вже наявні паролі та виконує Helm upgrade/install з очікуванням готовності. Паролі не записуються у файли проєкту чи звіт. Якщо образи вже зібрані з тегом `lab06`, пропустіть `-BuildImages`. За потреби вкажіть шлях до Helm через `-Helm 'C:/tools/helm.exe'`.

Локальний Docker Desktop використовує локальні образи. Для kind потрібно виконати `kind load docker-image car-rental/customer-service:lab06 car-rental/fleet-service:lab06 car-rental/booking-service:lab06 car-rental/payment-service:lab06 --name <cluster>`; для віддаленого кластера опублікуйте образи у своєму registry та змініть `app.imageRegistry` і `app.imageTag`. Kafka-код лабораторної 5 збережений; у базовому профілі цієї роботи `MESSAGING_ENABLED=false`, тому доступність Kafka не потрібна для перевірки моніторингу.

Використані зафіксовані теги: `prom/prometheus:v3.5.0`, `prom/alertmanager:v0.28.1`, `python:3.13-alpine`, PostgreSQL `16-alpine`. Теги образів визначено в Helm values.

## Автоматичне виявлення сервісів

Prometheus використовує `kubernetes_sd_configs` з `role: pod`: отримує IP кожної репліки через Kubernetes API та читає анотації `prometheus.io/scrape`, `prometheus.io/path`, `prometheus.io/port`. У цільові метрики додаються `namespace`, `pod`, `app`; код додає `application`. Скрейпінг і обчислення правил виконуються кожні 5 с, timeout скрейпінгу — 3 с. Не потрібні фіксовані IP чи ручне доповнення списку targets при заміні pod.

Виявлення обмежене namespace і label конкретного Helm release. ServiceAccount має лише namespace Role `get/list/watch` для pods. На кожному pod вибирається порт з іменем `http`, тому дублювання targets через інші порти виключене. Метрики читаються безпосередньо з усіх реплік, без балансування запитів через один Service ClusterIP.

У лекції також показаний `ServiceMonitor`. Це CRD Prometheus Operator. У цій роботі використано вбудоване Kubernetes service discovery Prometheus, що виконує ті самі вимоги завдання без додаткової залежності від Operator.

Для перегляду відкрийте окремі термінали:

```powershell
kubectl -n car-rental-lab06 port-forward svc/prometheus 9090:9090 --address=127.0.0.1
kubectl -n car-rental-lab06 port-forward svc/alertmanager 9093:9093 --address=127.0.0.1
kubectl -n car-rental-lab06 port-forward svc/customer-service 8081:8080 --address=127.0.0.1
```

Адреси: [Prometheus targets](http://localhost:9090/targets), [Prometheus alerts](http://localhost:9090/alerts), [Alertmanager](http://localhost:9093), [сирі метрики customer](http://localhost:8081/actuator/prometheus). Інші сервіси можна так само переадресувати на різні локальні порти. Services мають тип ClusterIP. `health`, `prometheus` відкриті у внутрішній мережі; liveness і readiness перевіряються окремими actuator probes.

## Усі п'ять типів метрик

| Тип Micrometer | Prometheus-серії | Як перевірити |
|---|---|---|
| Counter | `car_rental_http_requests_total` | Кількість завершених HTTP запитів зростає; bounded labels `method`, `outcome`. |
| Gauge | `car_rental_http_in_flight_requests` | Показує кількість активних запитів, після завершення повертається до нуля. |
| Timer | `car_rental_http_request_duration_seconds_count`, `_sum`, `_bucket`, `_max` | Вимірює тривалість завершених запитів у секундах. |
| LongTaskTimer | `car_rental_http_active_requests_seconds_count`, `_sum`, `_max` | Поточні активні запити та їхня тривалість; значення видно ще до завершення. Це gauge-серії, їх не слід обробляти `rate()`. |
| DistributionSummary | `car_rental_http_request_body_bytes_count`, `_sum`, `_bucket` | Розподіл відомих розмірів тіл запитів у байтах; POST із JSON створює ненульові спостереження. |

HTTP filter пропускає actuator-запити, щоб скрейпінг і health probes не забруднювали прикладні метрики. У Prometheus новий результат з'являється після наступного скрейпінгу. В OpenMetrics `# TYPE` може бути `histogram` або `gauge` для кількох різних Micrometer-типів; це очікуване відображення моделей бібліотеки у формат експорту, а не відсутність відповідного типу в коді.

Безпечний демонстраційний endpoint доступний лише у профілі `monitoring-demo`:

```powershell
Invoke-RestMethod 'http://localhost:8081/api/monitoring/demo-work?seconds=30'
```

Поки запит виконується, в іншому вікні відкрийте `/actuator/prometheus` або виконайте PromQL нижче. Обмеження часу та кількості одночасних задач задані в коді. За звичайного профілю endpoint відсутній. Для автоматичного збирання доказів усіх типів метрик виконайте `python scripts/demo_metrics.py`. Скрипт потребує Python 3, використовує лише стандартну бібліотеку, створює тестові бізнес-сутності та зберігає фактичні зрізи до, під час і після навантаження в `docs/evidence/metrics/`. DistributionSummary вимірює оголошений Content-Length; запити з невідомою довжиною не записуються.

Приклади PromQL:

```promql
up{job="spring-boot"}
sum by (app, outcome) (rate(car_rental_http_requests_total[2m]))
sum by (app) (car_rental_http_in_flight_requests)
sum by (app) (rate(car_rental_http_request_duration_seconds_sum[2m]))
  / sum by (app) (rate(car_rental_http_request_duration_seconds_count[2m]))
max by (app) (car_rental_http_active_requests_seconds_max)
sum by (app) (car_rental_http_request_body_bytes_sum)
  / sum by (app) (car_rental_http_request_body_bytes_count)
```

## Правила та доставка повідомлень

| Правило | Умова | Витримка `for` |
|---|---|---|
| `MicroserviceDown` | `up{job="spring-boot"} == 0` для конкретного pod | 30 с |
| `MicroserviceTargetMissing` | жодного виявленого target певного мікросервісу | 2 хв |
| `MicroserviceHighErrorRate` | HTTP 5xx > 5%, щонайменше 20 прикладних запитів за 2 хв | 1 хв |
| `MicroserviceHighLatency` | histogram p95 > 1 с, щонайменше 20 прикладних запитів за 2 хв | 1 хв |
| `MicroserviceLongRunningRequest` | найбільший час активного запиту > 10 с | 10 с |

HTTP error-rate і latency правила виключають `/actuator.*` та `/api/monitoring.*`. Histograms агрегуються за `le`, `namespace`, `app` до розрахунку p95. Перевірка мінімального трафіку зменшує хибні спрацьовування на одному випадковому запиті.

Prometheus надсилає алерти в Alertmanager. Alertmanager групує за `alertname`, `namespace`, `app`, виконує дедуплікацію, маршрутизує critical до `/critical`, warning до `/alerts`, пригнічує вторинні warning при critical недоступності того самого сервісу. `group_wait=5s`, `group_interval=10s`, `repeat_interval=1h`, `send_resolved=true`.

Локальний `alert-webhook` — реальний HTTP receiver сповіщень Alertmanager. Він фіксує час отримання і оригінальний JSON у stdout та зберігає останні 200 подій у `/events`. Для перевірки доставки не потрібні сторонні акаунти або токени. Це демонстрація webhook-сповіщення в кластері, а не відправлення email/Telegram.

## Практичний експеримент Firing → Resolved

```powershell
./k8s/scripts/demo-alert.ps1
```

Скрипт спочатку перевіряє, що всі чотири сервіси мають UP targets. Потім змінює **лише scrape-анотацію одного наявного customer pod** з 8080 на закритий порт 65535. Це симуляція відмови доступу до endpoint метрик: pod залишається у discovery, а справжній HTTP scrape завершується помилкою та створює `up=0`. Бізнес-порт сервісу залишається доступним; у звіті цей експеримент не слід називати падінням JVM або бази даних.

Після реального Pending → Firing скрипт очікує доставку webhook з `status=firing`, відновлює початкову анотацію у `finally`, очікує UP і реальний webhook з `status=resolved`. Усі очікування обмежені за часом; власні port-forward процеси завершуються після перевірки. Якщо експеримент зупинено до доставки firing, скрипт відновлює targets без очікування неіснуючого resolved-повідомлення.

Результати зберігаються у `docs/evidence/alert-<timestamp>/`: targets до/під час/після, JSON Pending і Firing, стан Alertmanager, отримані webhook payloads та журнали. Скрипт використовує локальні порти 19090/19093/19080; їх можна змінити параметрами. Зупинка PowerShell через примусове завершення процесу не гарантує виконання `finally`; у такому випадку відновіть анотацію вручну:

```powershell
kubectl -n car-rental-lab06 annotate pod <customer-pod> prometheus.io/port=8080 --overwrite
```

## Валідація та межі лабораторного стенду

```powershell
./k8s/scripts/validate-monitoring.ps1
```

Скрипт запускає Helm lint, перевірку згенерованої конфігурації скрейпінгу `promtool check config --syntax-only`, `promtool check rules`, сім сценаріїв `promtool test rules` та `amtool check-config`. Тести перевіряють витримку і відновлення down-alert, зникнення цілого сервісу, 5xx, виключення actuator/demo, мінімальний трафік, histogram p95 та gauge LongTaskTimer. Вони доповнюють практичний Kubernetes експеримент, а не замінюють його.

Для компактного навчального стенду Prometheus і Alertmanager мають `emptyDir`: історія метрик, silence/dedup-стан зникають при заміні pod. Receiver тримає останні події у пам'яті. Оригінальні докази експерименту скрипти зберігають локально. У production потрібні PVC/довготривале сховище, резервування, автентифікація доступу до UI/метрик і реальний канал чергування. Швидкі інтервали та короткі пороги тут налаштовані саме для лабораторної перевірки.

Офіційні джерела: [Kubernetes discovery у Prometheus](https://prometheus.io/docs/prometheus/latest/configuration/configuration/#kubernetes_sd_config), [конфігурація Alertmanager](https://prometheus.io/docs/alerting/latest/configuration/), [unit tests правил](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/).

