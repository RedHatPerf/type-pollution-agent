# Java agent to diagnose [JDK-8180450](https://bugs.openjdk.org/browse/JDK-8180450) 

## What is JDK-8180450?

See [the issue](https://bugs.openjdk.org/browse/JDK-8180450) for full details... 
But briefly, the problem is that the OpenJDK JVM caches the supertype of a class/interface on the JRE's `Klass` structure when evaluating things like `instanceof` or checking type casts and while that usually results in a performance win (because a slower traversal of the whole hierarchy can be avoided), in certain circumstances involving multiple inheritance of interfaces the cache is worse than useless.
CPU will be expended updating the cache, but the cached value won't help avoiding the slow path.
It's a problem because the surprising and significant difference in performance between the ideal and worst case behaviours means programmers can easily write poorly performing code without realising the costs (_usually_ `instanceof` is very cheap, for example).

## What is this?

It's a Java agent which can be used to identify code which may be suffering from this problem.
It attempts to count the number of times an a particular concrete class is used in `instanceof` (and similar) expressions with a different test type.
For example at one point in the code you might have `foo instanceof I1` and somewhere else `bar instanceof I2` (where `foo` and `bar` have the same concrete type).
The agent will estimate the number of times the cached supertype (`I1` and `I2`) is changing, i.e. how often you're hitting the slower path.

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
that OpenJDK C2 compiler "should"™ be able to constant fold all the checks (precomputed)
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


