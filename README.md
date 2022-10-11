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
The output, with a default agent configuration, is:
```bash
--------------------------
Type Pollution Statistics:
--------------------------
1:      io.type.pollution.example.B
Count:  5347728
Types:
        io.type.pollution.example.I1
        io.type.pollution.example.I3
        io.type.pollution.example.I2
Traces:
        io.type.pollution.example.Main.goo(Main.java:71)
        io.type.pollution.example.Main.foo(Main.java:66)
--------------------------
2:      io.type.pollution.example.C
Count:  3737797
Types:
        io.type.pollution.example.I1
        io.type.pollution.example.I2
Traces:
        io.type.pollution.example.Main.castToI2(Main.java:83)
        io.type.pollution.example.Main.castToI1(Main.java:79)
--------------------------
```
- **Count**: is the number of observed successful `checkcast`/`instanceof`/`Class::isAssignableFrom`/`Class::cast`/`Class::isInstance` 
against a different (interface) type from the last seen
- **Types**: is the list of different (interface) types observed
- **Traces**: is a list of top method stack traces compatible with
  [Idea IntelliJ Stack Trace Viewer](https://www.jetbrains.com/help/idea/analyzing-external-stacktraces.html) 

The report is an ordered list which types are ordered by increasing `Count` ie
`io.type.pollution.example.B` is the top type based on it and more likely the one with the highest chance
to cause scalability issues.

### But what about OpenJDK JIT optimizations that could save any check to be performed?


The agent can use an (imprecise/naive/eager/experimental) heuristic that mimic some JIT
optimizations and save false-positive statistics; it can be enabled by adding
```
-Dio.type.pollution.cleanup=true
```
to the list of JVM argument, turning the previous output into:
```bash
 # THIS IS EMPTY ON PURPOSE!!
```
An empty statistics?

Yes, because each call-site always observe a single concrete type; meaning
that OpenJDK C2 "should" (TM) able to constant fold all the checks (precomputed)
and add a guard + uncommon trap check instead.

### Full stack traces are available?

Yes!
To enable full-stack trace sampling 
(now defaulted at 10 ms sampling for each concrete type) just add
```
-Dio.type.pollution.full.traces=true
```
And, the output will become:
```bash
--------------------------
Type Pollution Statistics:
--------------------------
1:      io.type.pollution.example.B
Count:  5461879
Types:
        io.type.pollution.example.I3
        io.type.pollution.example.I1
        io.type.pollution.example.I2
Traces:
        io.type.pollution.example.Main.goo(Main.java:71)
        io.type.pollution.example.Main.foo(Main.java:66)
Full Traces:
        --------------------------
        io.type.pollution.example.Main.goo(Main.java:71)
        io.type.pollution.example.Main.lambda$main$0(Main.java:56)
        java.base/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:515)
        java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)
        java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
        java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:628)
        java.base/java.lang.Thread.run(Thread.java:829)
        --------------------------
        io.type.pollution.example.Main.foo(Main.java:66)
        io.type.pollution.example.Main.lambda$main$0(Main.java:55)
        java.base/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:515)
        java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)
        java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
        java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:628)
        java.base/java.lang.Thread.run(Thread.java:829)
        
# Rest omitted for brevity....
```


