package io.type.pollution.benchmarks;

class DuplicatedContext implements InternalContext {
    @Override
    public boolean isDuplicated() {
        return true;
    }
}
