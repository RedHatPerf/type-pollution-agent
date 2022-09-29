# Java agent to diagnose [JDK-8180450](https://bugs.openjdk.org/browse/JDK-8180450) 

## Build

```
$ # From the root dir
$ mvn package
```

## Run

```
$ # From the root dir
$ $ java -javaagent:agent/target/type-pollution-agent-0.1-SNAPSHOT.jar -XX:-Inline -XX:-TieredCompilation -jar example/target/type-pollution-example-0.1-SNAPSHOT.jar 
```
