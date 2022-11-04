package io.type.pollution.benchmarks;

class NonDuplicatedContext implements InternalContext {
    @Override
    public boolean isDuplicated() {
        return false;
    }
}
