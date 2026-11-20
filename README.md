# Car Rental Service

Командний проєкт із взаємодією gRPC на Spring Boot 4.1.1 та Java 25.

Контрактні та навантажувальні перевірки: [запуск і сценарії](README_CONTRACT_LOAD.md), [звіт із результатами](docs/report/Contract_Load_Report.md).

Почніть із [інструкції gRPC](README_GRPC.md): контракт, чотири типи викликів, retry policy, запуск, тести та демонстрація.

- [Звіт](docs/report/Grpc_Report.md)
- [Логи та результати](docs/evidence/grpc/)
- [Контракт Protobuf](grpc-contracts/src/main/proto/fleet.proto)

```powershell
.\mvnw.cmd -B -Pgrpc-demo verify
.\scripts\demo-grpc.ps1 -SkipBuild
```

Потрібен JDK 25 у `JAVA_HOME`. Локальний demo-профіль використовує H2 і не потребує Docker або PostgreSQL.
