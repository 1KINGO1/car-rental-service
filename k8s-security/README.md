# Захищене розгортання

Інтеграційні перевірки виконано в k3s v1.33.6+k3s1: 34 TCP-перевірки, 4 HTTP-маршрути, захист усіх 9 Deployment і створення бронювання пройдено. Протоколи: `docs/evidence/security`. Для Traefik у k3s використати `-f ./k8s-security/values.k3s.yaml`; pod template контролера повинен мати `app: ingress-controller`. Для HTTP-тесту виконати `kubectl -n kube-system port-forward svc/traefik 18080:80` та вказати `-IngressUrl http://127.0.0.1:18080`. Перед командами вибрати kubeconfig саме тестового кластера.

Chart використовує маніфести app, database, configmap та helpers із попередньої роботи. Старий каталог k8s збережено для попередніх лабораторних. Для цієї роботи розгортати **лише k8s-security**, в окремий namespace car-rental-security. Додаткові компоненти monitoring, tracing і Kafka до цього розгортання не входять; messaging вимкнено за початковою конфігурацією застосунків.

Потрібні Helm 3, kubectl, образи чотирьох сервісів car-rental/*-service:1.0.1, StorageClass для PVC, Ingress-контролер та CNI з підтримкою NetworkPolicy (наприклад, Calico або Cilium).

1. Зібрати образи командою `./k8s/scripts/build-images.ps1 -Tag 1.0.1` і завантажити їх до registry або локального кластера. Образи мають бути доступні всім вузлам кластера.
2. У pod template наявного Ingress-контролера встановити додаткову мітку `app: ingress-controller`. Namespace за замовчуванням `ingress-nginx`. Мітка потрібна саме подам, не лише Service або Deployment metadata. Зіставити ці значення з ingress.controllerNamespace та ingress.controllerApp у values.yaml. Контролер має працювати у звичайній pod-мережі, без hostNetwork.
3. Створити секрети, застосувати chart і дочекатися готовності:

```powershell
./k8s/scripts/create-secrets.ps1 -Namespace car-rental-security -Generate
helm lint ./k8s-security
helm upgrade --install car-rental-security ./k8s-security -n car-rental-security --wait --timeout 10m
kubectl -n car-rental-security get pods,svc,ingress,networkpolicy
```

Секрети створювати один раз. Повторна генерація паролів при наявному PVC не змінює пароль у вже ініціалізованому PostgreSQL.

4. Направити car-rental.local на адресу Ingress або передати заголовок Host. Запустити перевірки з кореня проєкту:

```powershell
./k8s-security/tests/runtime.ps1 -IngressUrl http://127.0.0.1
```

IngressUrl має відповідати реальній адресі контролера. Скрипт створює тимчасові тестові поди без секретів, видаляє їх у finally та записує протокол. Для перевірок потрібні права create/delete pods та pods/exec у тестовому namespace.

Маршрут: клієнт → Ingress → gateway → бізнес-сервіс → власна БД. Booking звертається до customer і fleet через gateway. Default Deny охоплює вхідний трафік всіх подів namespace; вихідний трафік не обмежується. Дозволи діють лише на вказаних TCP-портах. Політики додаються одна до одної, тому інші allow-all політики в namespace неприпустимі.

PostgreSQL alpine працює з UID/GID 70; дані записуються в PVC, сокети — в emptyDir /var/run/postgresql, тимчасові файли — у /tmp. Коренева файлова система залишається read-only. Для старих томів слід перевірити власника даних і виконати контрольовану міграцію; root init-контейнер не використовується.

NetworkPolicy не є автентифікацією: право створювати поди з довільними app-мітками дозволяє імітувати дозволене джерело. Обмежуйте це право RBAC. Вбудовані перевірки kubelet мають спеціальну семантику і не замінюють тест із стороннього пода.
