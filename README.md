# similarity-service

Calculates recommendations on the basis of tags created by CorpusTagProvider, TrioTagProvider etc.

*ToDos Stand 06.02.2020*:

- (Aussparung in der Logik) In Redis Hashmaps mit den aktuellen Tags jedes Docs vorhalten, um entfernte Tags wieder aus ihren Hashmaps zu löschen und die Indicator-Scores dann dementsprechend zu verringern. (Notwendig für korrekte Scores!) ;)

- Unit Tests

- Deployment per Gitlab CI (auf zdf-kpi-dashboard.exozet.com)
- ...

*Setup für PROD/INT ohne Docker*:

- Repo klonen
- `cd similarity-service`
- *JE NACH ENV:* `cp chart/env/*ENV*/application.yml application.yml`, z.B. `cp chart/TEST/application.yml application.yml`
- In der application.yml `redis.host: localhost` setzen
- `docker-compose up -d redis`
- `mvn clean spring-boot:run` (mit Socks-Proxy-Parametern, falls nötig)
- If you have used a new consumer group, please delete the table on https://eu-central-1.console.aws.amazon.com/dynamodb/home?region=eu-central-1#tables: . 
    Otherwise, there will be maintenance costs!
