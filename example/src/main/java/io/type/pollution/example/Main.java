package io.type.pollution.example;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

interface I1 {
}

interface I2 {
}

interface I3 extends I1, I2 {
}

class B implements I3 {
}

class C implements I3 {
}

public class Main {

    public static void main(String[] args) {
        int numThreads = 2;
        int loopCount = 1_000_000;
        ExecutorService es = Executors.newFixedThreadPool(numThreads);
        I3 b = new B();
        I3 c = new C();
        for (int i = 0; i != numThreads; i++) {
            es.submit(() -> {
                for (int j = 0; j != loopCount; j++) {
                    foo(b);
                    foo(c);
                    goo(b);
                    goo(c);
                }
            });
        }
        es.shutdown();
    }

    public static boolean foo(I3 i) {
        return i instanceof I1;
    }

    public static boolean goo(I3 i) {
        return i instanceof I2;
    }

}