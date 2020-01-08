# similarity-service

Calculates recommendations on the basis of tags created by CorpusTagProvider, TrioTagProvider etc.


*Setup für PROD/INT*:

- Repo klonen
-  `cd similarity-service`
- *JE NACH ENV:* `cp chart/env/*ENV*/application.yml application.yml`, z.B. `cp chart/TEST/application.yml application.yml`
- `docker build -t registry.exozet.com/zdf/similarity-service:latest`
- `docker-compose up -d`


*Setup für DEV:*

- Repo klonen
- `cd similarity-service`
- `cp chart/env/TEST/application.yml application.yml`
- In der `application.yml`: consumerGroup ändern auf einen neuen, beliebigen Wert.
- In der `application.yml`: `redis.host` muss `localhost` heißen.
- `mvn -s maven-settings.xml package`
- `mvn clean spring-boot:run`
- If you have used a new consumer group, please delete the table on https://eu-central-1.console.aws.amazon.com/dynamodb/home?region=eu-central-1#tables: . 
    Otherwise, there will be maintenance costs!

