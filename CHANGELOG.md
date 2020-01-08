# CorpusTagProvider CHANGELOG

## 0.59

- KZNREA-16472: Wenn zdf.processOnlyDocIdsPrefixedWithSCMS = true, werden alle Events bzgl DocIds ohne SCMS-Prefix ignoriert.
- waitForRedis()

## 0.54

- CorpusTagProvider kopiert, wenn zdf.passthroughDeleteEvents = true, Delete-Elevents aus dem DocDataStream in den TagDataStream
- Bugfix: CorpusTagProvider schreibt immer Json-Strings

## 0.53

- CorpusTagProvider merkt sich mithilfe einer Redis-Instanz die (aktuellen) Texte der Dokumente, um keine Duplikate zu schreiben.

## 0.49

- Deployment mit Kubernetes / Helm / Gitlab-CI in der Google Cloud

## 0.46

- CorpusTagProvider liest den documentDataStream (Kinesis), lässt den MTR3-TaggingService (mit CID Corpus) aus den darin 
enthaltenen Docs Tag-JSONs erstellen, und schreibt diese Tag-JSONs in den tagDataStream (Kinesis).
