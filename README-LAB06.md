# Лабораторна 6 - Car Rental

Звіт команди: [PDF](docs/report/Lab06_Report.pdf), [Markdown](docs/report/Lab06_Report.md).
Інструкція запуску та PromQL: [docs/MONITORING.md](docs/MONITORING.md).

Перевірено 18.10.2026 у Kubernetes Docker Desktop: 4/4 мікросервіси UP,
5 типів Micrometer у кожному сервісі, реальний Pending → Firing → Resolved
і доставлені webhook-сповіщення. Maven: 46 тестів, 0 помилок.

```powershell
mvn -B -ntp verify
./k8s/scripts/deploy-monitoring.ps1 -BuildImages
./k8s/scripts/validate-monitoring.ps1
python scripts/demo_metrics.py
./k8s/scripts/demo-alert.ps1
```

Потрібні Java 25, Maven, Docker, Kubernetes, Helm 3, PowerShell і Python 3.
Код засновано на наданому архіві п’ятої лабораторної.
Kafka-логіку збережено; вона вимкнена у цьому навчальному deployment.
Паролі БД генеруються скриптом розгортання і не включені в архів.

Докази виконання (один основний набір):

- `docs/evidence/metrics/` — `metrics-summary.json` і зразки scrape (`customer-active.prom`, `customer-after.prom`)
- `docs/evidence/alert/` — Pending → Firing → Resolved, webhook payloads
- `docs/evidence/screenshots/` — скріншоти Prometheus / Alertmanager
- `docs/evidence/validation.log` — Helm, promtool, amtool
- `docs/verification/lab06/maven-tests.log` — успішний Maven-прогін

Звіт: `docs/report/`.
