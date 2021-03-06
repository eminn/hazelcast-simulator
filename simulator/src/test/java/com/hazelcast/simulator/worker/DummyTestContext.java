package com.hazelcast.simulator.worker;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.simulator.test.TestContext;

class DummyTestContext implements TestContext {

    volatile boolean isStopped;

    @Override
    public HazelcastInstance getTargetInstance() {
        return null;
    }

    @Override
    public String getTestId() {
        return "";
    }

    @Override
    public boolean isStopped() {
        return isStopped;
    }

    @Override
    public void stop() {
        isStopped = true;
    }
}
