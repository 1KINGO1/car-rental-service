# Звіт: розгортання Car Rental Service у Kubernetes
*Частково написано за допомогою ШІ*

**Команда:** Лис Тарас Олександрович (ІПЗ-4), Тарасенко Тимофій Миколайович (ІПЗ-4)  
**Стек:** Spring Boot 4.1.1, Java 25, PostgreSQL 16, Kubernetes  
**Репозиторій:** https://github.com/1KINGO1/car-rental-service

---

## 1. Архітектура в кластері

Система перенесена з Docker Compose у namespace `car-rental`. Кожен bounded context зберігає власну БД; міжсервісна взаємодія — лише через ClusterIP DNS-імена.

```text
                    ┌─────────────────────────────────────────┐
                    │           namespace: car-rental          │
                    │                                         │
  ConfigMaps ──────►│  customer-config / fleet-config /       │
  (відкриті         │  booking-config / payment-config        │
   параметри)       │                                         │
                    │  Secrets (лише в кластері):             │
                    │  *-db-secret → POSTGRES_PASSWORD,       │
                    │                DB_PASSWORD              │
                    │                                         │
                    │  ┌──────────────┐   ClusterIP :8080     │
                    │  │ customer-svc │◄──────────────────┐   │
                    │  │ replicas: 2  │                   │   │
                    │  └──────┬───────┘                   │   │
                    │         │ JDBC                      │   │
                    │  ┌──────▼───────┐            ┌──────┴───────┐
                    │  │ customer-db  │            │ booking-svc  │
                    │  │ Postgres     │            │ replicas: 2  │
                    │  └──────────────┘            └──────┬───────┘
                    │                                     │
                    │  ┌──────────────┐   ClusterIP       │
                    │  │ fleet-svc    │◄──────────────────┘
                    │  │ replicas: 2  │
                    │  └──────┬───────┘
                    │         │
                    │  ┌──────▼───────┐   ┌──────────────┐
                    │  │ fleet-db     │   │ payment-svc  │
                    │  └──────────────┘   │ replicas: 2  │
                    │                     └──────┬───────┘
                    │                            │
                    │                     ┌──────▼───────┐
                    │                     │ payment-db   │
                    │                     └──────────────┘
                    └─────────────────────────────────────────┘
```

### Відповідність вимогам

| Вимога | Реалізація |
| --- | --- |
| Deployment ≥ 2 репліки | `customer/fleet/booking/payment-service`: `replicas: 2` |
| Liveness probes | HTTP `/actuator/health` (сервіси); `pg_isready` (БД) |
| ClusterIP + DNS | Service `type: ClusterIP` з іменами як у Compose |
| ConfigMap | `01-configmaps.yaml` — URL, user, DB name, порти |
| Secret | створюються скриптом; у Git лише `02-secrets.example.yaml` |
| requests / limits | CPU + memory на кожному контейнері |
| dry-run | `k8s/scripts/validate.ps1` / `validate.sh` |

### Ресурсна дисципліна

| Компонент | requests | limits |
| --- | --- | --- |
| App services | cpu 250m, memory 256Mi | cpu 1000m, memory 512Mi |
| PostgreSQL | cpu 100m, memory 128Mi | cpu 500m, memory 256Mi |

### Секрети без відкритих паролів у репозиторії

1. У Git зберігається лише шаблон `k8s/02-secrets.example.yaml` з плейсхолдерами.
2. Реальні Secret створюються командою:
   ```powershell
   .\k8s\scripts\create-secrets.ps1 -Generate
   ```
   або з змінних середовища `*_DB_PASSWORD`.
3. Файли `k8s/02-secrets.yaml` та `*.secret.yaml` додані до `.gitignore`.

---

## 2. Порядок розгортання

```powershell
# 1. Збірка образів (Spring Boot 4.1 + Java 25)
docker build -t car-rental/customer-service:1.0.0 --build-arg SERVICE=customer-service .
docker build -t car-rental/fleet-service:1.0.0 --build-arg SERVICE=fleet-service .
docker build -t car-rental/booking-service:1.0.0 --build-arg SERVICE=booking-service .
docker build -t car-rental/payment-service:1.0.0 --build-arg SERVICE=payment-service .

# 2. Валідація маніфестів
.\k8s\scripts\validate.ps1

# 3. Секрети
.\k8s\scripts\create-secrets.ps1 -Generate

# 4. Deploy
.\k8s\scripts\deploy.ps1

# 5. Перевірка
kubectl get pods,svc,deploy -n car-rental -o wide
kubectl port-forward -n car-rental svc/booking-service 8083:8080
```

Міжсервісні URL у ConfigMap:

- `CUSTOMER_URL=http://customer-service:8080`
- `FLEET_URL=http://fleet-service:8080`
- JDBC: `jdbc:postgresql://{service}-db:5432/{dbname}`

---

## 3. Перевірка працездатності

Підтверджено на локальному кластері (Docker Desktop / context `docker-desktop`, node `desktop-control-plane`).

Повний текстовий знімок стану: [`docs/verification/cluster-status.txt`](verification/cluster-status.txt).  

### 3.1. Dry-run (клієнт + сервер)

```powershell
.\k8s\scripts\validate.ps1
```

Результат: **`OK: client + server dry-run passed`** для всіх маніфестів.

> **Скріншот:** `docs/screenshots/01-dry-run.png`

### 3.2. Pods / Deployments / Services

Фактичний стан після `deploy`:

- 4× PostgreSQL: `1/1 Running`
- 4× app-сервіси: по **2/2 Ready** репліки
- 8× Service типу **ClusterIP** (DNS-імена як у Compose)

> **Скріншот:** `docs/screenshots/02-pods-services.png`

### 3.3. Liveness / ресурси

На app-сервісах: HTTP liveness/readiness на `/actuator/health`;  
requests `250m/256Mi`, limits `1000m/512Mi`.  
На БД: `pg_isready`; requests `100m/128Mi`, limits `500m/256Mi`.

> **Скріншот:** `docs/screenshots/03-probes-resources.png`

### 3.4. Secrets

У кластері: `customer-db-secret`, `fleet-db-secret`, `booking-db-secret`, `payment-db-secret` (Opaque).  
У Git — лише `k8s/02-secrets.example.yaml` + скрипт `create-secrets.ps1`; реальні паролі не комітяться.

> **Скріншот:** `docs/screenshots/04-secrets.png`

### 3.5. Health між сервісами (DNS ClusterIP)

Перевірка з тимчасового poda `curlimages/curl` у namespace `car-rental`:

```text
customer-service: {"status":"UP"}
fleet-service:    {"status":"UP"}
booking-service:  {"status":"UP"}
payment-service:  {"status":"UP"}
```

> **Скріншот:** `docs/screenshots/05-interservice-health.png`

---

## 4. Висновок

Мікросервісна система Car Rental перенесена з Docker Compose у Kubernetes зі збереженням ізоляції даних за bounded contexts. Конфігурація винесена в ConfigMap, паролі — у Secret поза Git, app-сервіси мають по 2 репліки, liveness-проби та обов’язкові CPU/memory requests і limits. Маніфести валідуються через client/server dry-run; повне оцінювання завершується успішним розгортанням у кластері, скріншотами з розділу 3 та персональними комітами обох учасників.
