package cash.xcl.server;

import cash.xcl.api.AllMessages;
import cash.xcl.api.dto.*;
import cash.xcl.api.tcp.WritingAllMessages;
import cash.xcl.api.tcp.XCLClient;
import cash.xcl.api.tcp.XCLServer;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.salt.Ed25519;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/*
-XX:+UnlockCommercialFeatures
-XX:+FlightRecorder
-XX:+UnlockDiagnosticVMOptions
-XX:+DebugNonSafepoints
-XX:StartFlightRecording=name=test,filename=test.jfr,dumponexit=true,settings=profile
 */
public class RegionalServerBenchmarkMain {

    private XCLServer server;
    private Gateway gateway;
    private int serverAddress = 10001;

    private Bytes publicKey = Bytes.allocateDirect(Ed25519.PUBLIC_KEY_LENGTH);
    private Bytes secretKey = Bytes.allocateDirect(Ed25519.SECRET_KEY_LENGTH);


    public RegionalServerBenchmarkMain(int mainBlockPeriodMS,
                                       int localBlockPeriodMS,
                                       int iterations,
                                       int clientThreads) throws IOException {

        Ed25519.generatePublicAndSecretKey(publicKey, secretKey);


        long[] clusterAddresses = {serverAddress};
        this.gateway = VanillaGateway.newGateway(serverAddress, "gb1dn", clusterAddresses, mainBlockPeriodMS, localBlockPeriodMS);
        this.server = new XCLServer("one", serverAddress, serverAddress, secretKey, gateway);
        gateway.start();
        // register the address - otherwise, verify will fail
        gateway.createNewAddressEvent(new CreateNewAddressEvent(serverAddress, 0, 0, 0, serverAddress, publicKey));
        // register all the addresses involved in the transfers
        // -source and destination accounts- in the Account Service with a opening balance of $1,000,000,000
        for (int iterationNumber = 0; iterationNumber < iterations; iterationNumber++) {
            for (int s = 0; s < clientThreads; s++) {
                final int sourceAddress = (iterationNumber * 100) + s + 1;
                final int destinationAddress = sourceAddress + 1000000;
                gateway.createNewAddressEvent(new CreateNewAddressEvent(0, 0, 0, 0,
                        sourceAddress, publicKey));

                gateway.createNewAddressEvent(new CreateNewAddressEvent(0, 0, 0, 0,
                        destinationAddress, publicKey));


                AtomicInteger count = new AtomicInteger();
                XCLClient client = new XCLClient("client", "localhost", serverAddress, sourceAddress, secretKey,
                        new MyWritingAllMessages(count));
                sendOpenningBalance(client, sourceAddress, sourceAddress);
                sendOpenningBalance(client, sourceAddress, destinationAddress);
                // how do we know if the openingBalanceEvent msg was a success or a failure?
            }
        }
    }

    static void sendOpenningBalance(XCLClient client, int sourceAddress, int destinationAddress) {
        final OpeningBalanceEvent obe1 = new OpeningBalanceEvent(sourceAddress,
                1,
                destinationAddress,
                "USD",
                1000);
        client.openingBalanceEvent(obe1);
    }

    // Not using JUnit at the moment because
    // on Windows, using JUnit and the native encryption library will crash the JVM.
    public static void main(String[] args) throws Exception {
        RegionalServerBenchmarkMain benchmarkMain = null;
        try {
            int iterations = 3;
            int transfersPerThread = 1_000_000;
            int total = iterations * transfersPerThread;
            benchmarkMain = new RegionalServerBenchmarkMain(1000, 5, 10, 4);
            int oneThread = benchmarkMain.benchmark(iterations, 1, transfersPerThread);
            int twoThreads = benchmarkMain.benchmark(iterations, 2, transfersPerThread);
            int threeThreads = benchmarkMain.benchmark(iterations, 3, transfersPerThread);
            int fourThreads = benchmarkMain.benchmark(iterations, 4, transfersPerThread);
            System.out.println("Total number of messages per benchmark = " + total);
            System.out.println("benchmark - oneThread = " + oneThread + " messages per second");
            System.out.println("benchmark - twoThreads = " + twoThreads + " messages per second");
            System.out.println("benchmark - threeThread = " + threeThreads + " messages per second");
            System.out.println("benchmark - fourThreads = " + fourThreads + " messages per second");
        } catch (Throwable t) {
            t.printStackTrace();

        } finally {
            //Jvm.pause(1000);
            //benchmarkMain.close();
            System.exit(0);
        }
    }

    private int benchmark(int iterations, int clientThreads, int transfersPerThread) throws IOException, InterruptedException, ExecutionException {

        Thread.sleep(1000);

        System.out.println(" STARTING BENCHMARK TEST **********************************************");
        // number of messages = msgs * number of iterations
        // number of messages = 10000 * 10 = 100,000
        double allIterationsTotalTime = 0;
        for (int iterationNumber = 0; iterationNumber < iterations; iterationNumber++) {
            long start = System.nanoTime();
            ExecutorService service = Executors.newFixedThreadPool(clientThreads);
            List<Future> futures = new ArrayList<>();
            for (int s = 0; s < clientThreads; s++) {
                final int sourceAddress = (iterationNumber * 100) + s + 1;
                final int destinationAddress = sourceAddress + 1000000;
                futures.add(service.submit((Callable<Void>) () -> {
                    try {
                        AtomicInteger count = new AtomicInteger();
                        AllMessages queuing = new MyWritingAllMessages(count);
                        XCLClient client = new XCLClient("client", "localhost", this.serverAddress, sourceAddress, secretKey, queuing);
                        sendOpenningBalance(client, sourceAddress, sourceAddress);
                        sendOpenningBalance(client, sourceAddress, destinationAddress);
                        client.subscriptionQuery(new SubscriptionQuery(sourceAddress, 0));
                        TransferValueCommand tvc1 = new TransferValueCommand(sourceAddress, 0, destinationAddress, 1e-9, "USD", "");
                        int x = 0;
                        for (int i = 0; i < transfersPerThread; i += clientThreads) {
                            client.transferValueCommand(tvc1);
                            if (++x > 100000 && x % 5000 == 0)
                                Jvm.pause(1);
                        }
                        long last = System.currentTimeMillis() + 1000;
                        for (int i = 0; i < transfersPerThread; i += clientThreads) {
                            while (count.get() <= 0) {
                                if (System.currentTimeMillis() > last + 1000) {
                                    System.out.println("pause " + i);
                                    last = System.currentTimeMillis();
                                }
                                Jvm.pause(10);
                            }
                            count.decrementAndGet();
                        }
                        //client.close();
                        Closeable.closeQuietly(client);
                        // +2 for the opening balances.
                        assertEquals(1, count.get(), 1);
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                    return null;
                }));
            }
            for (Future future : futures) {
                future.get();
            }
            long time = System.nanoTime() - start;

            double timeInSeconds = (time / 1e9);
            System.out.printf("Iteration %d - Throughput: %,d messages per sec%n", iterationNumber, (int) (transfersPerThread / timeInSeconds));
            allIterationsTotalTime += timeInSeconds;
            service.shutdown();
        }

        int average = (int) (transfersPerThread / (allIterationsTotalTime / iterations));

        System.out.printf("Average Throughput after sending %d messages (%d messages * %d times) using %d client threads = %,d / sec%n",
                transfersPerThread * iterations,
                transfersPerThread,
                iterations,
                clientThreads,
                average);

        ((VanillaGateway) gateway).printBalances();

        return average;
    }

    public void close() {
        Closeable.closeQuietly(server);
    }

    private static class MyWritingAllMessages extends WritingAllMessages {
        private final AtomicInteger count;

        public MyWritingAllMessages(AtomicInteger count) {
            this.count = count;
        }

        @Override
        public WritingAllMessages to(long addressOrRegion) {
            return this;
        }

        @Override
        public void write(SignedMessage message) {
            count.incrementAndGet();
        }

        @Override
        public void close() {

        }
    }
}
