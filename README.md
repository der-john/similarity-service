# similarity-service

Calculates recommendations on the basis of tags created by CorpusTagProvider, TrioTagProvider etc.

*ToDos Stand 11.1.2020*:

- `generateUpdateRequests` erst kurz vor dem Batch Update gebündelt durchführen, und zwar muss man sich dazu statt `docIdsToBeUpdated` eine Liste merken, die Paare aus docId + tagProvider enthält! Dann sollten circa 10 - 100 (!) mal weniger Update Requests geschrieben werden. :)

    - Zu Performance-Zwecken: In Term-Hashmaps nur die 40(?) Docs mit den höchsten Scores vorhalten. (Dazu könnte man LinkedHashMaps verwenden, um in den Term-Hashmaps immer schnell das niedrigst bewertete Doc durch ein höher bewertetes Doc zu ersetzen.)
    
    - (?) Zu Performance-Zwecken: Nur die 20(?) Tags mit den höchsten Scores für Berechnungen verwenden. (Dazu könnte man LinkedHashMaps verwenden, um in den Tags-Hashmaps immer schnell das niedrigst bewertete Tag durch ein höher bewertetes Tag zu ersetzen.)

- In Redis Hashmaps mit den aktuellen Tags jedes Docs vorhalten, um entfernte Tags wieder aus ihren Hashmaps zu löschen und die Indicator-Scores dann dementsprechend zu verringern. (Notwendig für korrekte Scores!) ;)

- ...

*Setup für PROD/INT ohne Docker*:

- Repo klonen
- `cd similarity-service`
- *JE NACH ENV:* `cp chart/env/*ENV*/application.yml application.yml`, z.B. `cp chart/TEST/application.yml application.yml`
- In der application.yml `redis.host: localhost` setzen
- `docker-compose up -d redis`
- `mvn clean spring-boot:run`
- If you have used a new consumer group, please delete the table on https://eu-central-1.console.aws.amazon.com/dynamodb/home?region=eu-central-1#tables: . 
    Otherwise, there will be maintenance costs!
