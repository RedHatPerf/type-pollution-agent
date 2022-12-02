package io.type.pollution.example;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

interface I1 {
    void do1();
}

interface I2 {

    void do2();
}

interface I3 extends I1, I2 {
}

class B implements I3 {

    @Override
    public void do2() {

    }

    @Override
    public void do1() {

    }
}

class C implements I3 {

    @Override
    public void do2() {

    }

    @Override
    public void do1() {

    }
}

public class Main {

    public static void main(String[] args) {
        int numThreads = 2;
        int loopCount = 1_000_000_000;
        ExecutorService es = Executors.newFixedThreadPool(numThreads);
        I3 b = new B();
        I3 c = new C();
        for (int i = 0; i != numThreads; i++) {
            es.submit(() -> {
                for (int j = 0; j != loopCount; j++) {
                    foo(b);
                    goo(b);
                    consumeAsI2(I2::do2, b);
                    castToI1(c);
                    castToI2(c);
                    consumeAsI2(I2::do2, c);
                }
            });
        }
        es.shutdown();
    }

    public static boolean foo(I3 i) {
        return I1.class.isInstance(i);
    }

    public static boolean goo(I3 i) {
        // same line of code!!
        if (i instanceof I2 && I3.class.isAssignableFrom(i.getClass())) {
            return true;
        }
        return false;
    }

    public static void consumeAsI2(Consumer<I2> innocentConsumer, I2 i) {
        innocentConsumer.accept(i);
    }

    public static void castToI1(Object o) {
        I1.class.cast(o).do1();
    }

    public static void castToI2(Object o) {
        ((I2) o).do2();
    }

}