package com.hazelcast.simulator.agent;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.simulator.agent.workerjvm.WorkerJvmSettings;
import com.hazelcast.simulator.common.AgentAddress;
import com.hazelcast.simulator.common.AgentsFile;
import com.hazelcast.simulator.coordinator.AgentMemberLayout;
import com.hazelcast.simulator.coordinator.remoting.AgentsClient;
import com.hazelcast.simulator.test.Failure;
import com.hazelcast.simulator.test.TestCase;
import com.hazelcast.simulator.test.TestPhase;
import com.hazelcast.simulator.test.TestSuite;
import com.hazelcast.simulator.tests.FailingTest;
import com.hazelcast.simulator.tests.SuccessTest;
import com.hazelcast.simulator.worker.commands.GenericCommand;
import com.hazelcast.simulator.worker.commands.InitCommand;
import com.hazelcast.simulator.worker.commands.RunCommand;
import com.hazelcast.simulator.worker.commands.StopCommand;
import org.apache.log4j.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

import static com.hazelcast.simulator.utils.CommonUtils.sleepSeconds;
import static com.hazelcast.simulator.utils.FileUtils.deleteQuiet;
import static com.hazelcast.simulator.utils.FileUtils.fileAsText;
import static com.hazelcast.simulator.utils.FileUtils.writeText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AgentSmokeTest {

    private static final String AGENT_IP_ADDRESS = System.getProperty("agentBindAddress", "127.0.0.1");
    private static final int TEST_RUNTIME_SECONDS = Integer.parseInt(System.getProperty("testRuntimeSeconds", "10"));

    private static final Logger LOGGER = Logger.getLogger(AgentSmokeTest.class);

    private static String userDir;
    private static AgentStarter agentStarter;
    private static AgentsClient agentsClient;

    @BeforeClass
    public static void setUp() throws Exception {
        userDir = System.getProperty("user.dir");

        System.setProperty("worker.testmethod.timeout", "5");
        System.setProperty("user.dir", "./dist/src/main/dist");

        LOGGER.info("Agent bind address for smoke test: " + AGENT_IP_ADDRESS);
        LOGGER.info("Test runtime for smoke test: " + TEST_RUNTIME_SECONDS + " seconds");

        agentStarter = new AgentStarter();
        agentStarter.start();

        agentsClient = getAgentsClient();
        agentsClient.start();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        try {
            agentsClient.stop();

            agentStarter.stop();
        } finally {
            Hazelcast.shutdownAll();

            System.setProperty("user.dir", userDir);
            deleteQuiet(new File("./dist/src/main/dist/workers"));
        }
    }

    @Test
    public void testSuccess() throws Exception {
        TestCase testCase = new TestCase("testSuccess");
        testCase.setProperty("class", SuccessTest.class.getName());
        executeTestCase(testCase);
    }

    @Test
    public void testThrowingFailures() throws Exception {
        TestCase testCase = new TestCase("testThrowingFailures");
        testCase.setProperty("class", FailingTest.class.getName());
        executeTestCase(testCase);

        cooldown();

        List<Failure> failures = agentsClient.getFailures();
        assertEquals("Expected 2 failures!", 2, failures.size());

        Failure failure = failures.get(0);
        assertEquals("Expected started test to fail", testCase.getId(), failure.testId);
        assertTrue("Expected started test to fail", failure.cause.contains("This test should fail"));
    }

    private void cooldown() {
        LOGGER.info("Cooldown...");
        sleepSeconds(3);
        LOGGER.info("Finished cooldown");
    }

    public void executeTestCase(TestCase testCase) throws Exception {
        TestSuite testSuite = new TestSuite();
        agentsClient.initTestSuite(testSuite);

        LOGGER.info("Spawning workers...");
        spawnWorkers(agentsClient);

        InitCommand initTestCommand = new InitCommand(testCase);
        LOGGER.info("InitTest phase...");
        agentsClient.executeOnAllWorkers(initTestCommand);

        runPhase(testCase, TestPhase.SETUP);

        runPhase(testCase, TestPhase.LOCAL_WARMUP);
        runPhase(testCase, TestPhase.GLOBAL_WARMUP);

        LOGGER.info("Starting run phase...");
        RunCommand runCommand = new RunCommand(testCase.getId());
        runCommand.clientOnly = false;
        agentsClient.executeOnAllWorkers(runCommand);

        LOGGER.info("Running for " + TEST_RUNTIME_SECONDS + " seconds");
        sleepSeconds(TEST_RUNTIME_SECONDS);
        LOGGER.info("Finished running");

        LOGGER.info("Stopping test...");
        agentsClient.executeOnAllWorkers(new StopCommand(testCase.getId()));
        agentsClient.waitForPhaseCompletion("", testCase.getId(), TestPhase.RUN);

        runPhase(testCase, TestPhase.GLOBAL_VERIFY);
        runPhase(testCase, TestPhase.LOCAL_VERIFY);

        runPhase(testCase, TestPhase.GLOBAL_TEARDOWN);
        runPhase(testCase, TestPhase.LOCAL_TEARDOWN);

        LOGGER.info("Terminating workers...");
        agentsClient.terminateWorkers();

        LOGGER.info("Testcase done!");
    }

    private void spawnWorkers(AgentsClient client) throws TimeoutException {
        WorkerJvmSettings workerJvmSettings = new WorkerJvmSettings();
        workerJvmSettings.profiler = "";
        workerJvmSettings.vmOptions = "";
        workerJvmSettings.workerStartupTimeout = 60000;
        workerJvmSettings.clientHzConfig = fileAsText("./simulator/src/test/resources/client-hazelcast.xml");
        workerJvmSettings.hzConfig = fileAsText("./simulator/src/test/resources/hazelcast.xml");
        workerJvmSettings.log4jConfig = fileAsText("./simulator/src/test/resources/log4j.xml");

        AgentMemberLayout agentLayout = new AgentMemberLayout(workerJvmSettings);
        agentLayout.memberSettings.memberWorkerCount = 1;
        agentLayout.publicIp = AGENT_IP_ADDRESS;

        client.spawnWorkers(Collections.singletonList(agentLayout), true);
    }

    private void runPhase(TestCase testCase, TestPhase testPhase) throws TimeoutException {
        LOGGER.info("Starting " + testPhase.name + " phase...");
        agentsClient.executeOnAllWorkers(new GenericCommand(testCase.getId(), testPhase));
        agentsClient.waitForPhaseCompletion("", testCase.getId(), testPhase);
    }

    private static AgentsClient getAgentsClient() throws IOException {
        File agentFile = File.createTempFile("agents", "txt");
        agentFile.deleteOnExit();
        writeText(AGENT_IP_ADDRESS, agentFile);
        List<AgentAddress> agentAddresses = AgentsFile.load(agentFile);
        return new AgentsClient(agentAddresses);
    }

    private static class AgentStarter {

        private final CountDownLatch latch = new CountDownLatch(1);
        private final AgentThread agentThread = new AgentThread();

        private void start() throws Exception {
            agentThread.start();
            latch.await();
        }

        private void stop() throws Exception {
            agentThread.shutdown();
            agentThread.interrupt();
            agentThread.join();
        }

        private class AgentThread extends Thread {

            private Agent agent;

            @Override
            public void run() {
                String[] args = new String[]{
                        "--bindAddress", AGENT_IP_ADDRESS,
                };
                agent = Agent.createAgent(args);
                agent.start();
                latch.countDown();
            }

            private void shutdown() {
                agent.stop();
            }
        }
    }
}
