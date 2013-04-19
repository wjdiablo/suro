package com.netflix.suro.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.google.inject.Injector;
import com.netflix.governator.guice.BootstrapBinder;
import com.netflix.governator.guice.BootstrapModule;
import com.netflix.governator.guice.LifecycleInjector;
import com.netflix.governator.lifecycle.LifecycleManager;
import com.netflix.suro.message.serde.DefaultSerDeFactory;
import com.netflix.suro.message.serde.SerDeFactory;
import com.netflix.suro.queue.MemoryMessageQueue;
import com.netflix.suro.queue.MessageQueue;
import com.netflix.suro.routing.TestMessageRouter;
import com.netflix.suro.sink.SinkManager;
import com.netflix.suro.thrift.*;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.server.THsHaServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import static org.junit.Assert.assertEquals;

public class TestStatusServer {
    private static class TestServer implements SuroServer.Iface {
        private int count = 0;

        private Result result;
        public TestServer(Result result) {
            this.result = result;
        }

        @Override
        public Result process(TMessageSet messageSet) throws TException {
            result.setMessage(messageSet.getApp());
            result.setResultCode(ResultCode.OK);

            return result;
        }

        @Override
        public long shutdown() throws TException {
            return 0;
        }

        @Override
        public String getName() throws TException {
            return null;
        }

        @Override
        public ServiceStatus getStatus() throws TException {
            return ServiceStatus.ALIVE;
        }

        @Override
        public String getVersion() throws TException {
            return null;
        }
    }

    private static LifecycleManager manager;
    private static Injector injector;

    @BeforeClass
    public static void start() throws Exception {
        injector = LifecycleInjector.builder().withBootstrapModule(new BootstrapModule() {
            @Override
            public void configure(BootstrapBinder binder) {
                binder.bind(MessageQueue.class).to(MemoryMessageQueue.class);
                binder.bind(SerDeFactory.class).to(DefaultSerDeFactory.class);

                ObjectMapper jsonMapper = new ObjectMapper();
                jsonMapper.registerSubtypes(new NamedType(TestMessageRouter.TestSink.class, "TestSink"));
                binder.bind(ObjectMapper.class).toInstance(jsonMapper);
            }
        }).withModules(StatusServer.createJerseyServletModule()).createInjector();

        manager = injector.getInstance(LifecycleManager.class);
        manager.start(); // start status server with lifecycle manager

        String sinkDesc = "{\n" +
                "    \"default\": {\n" +
                "        \"type\": \"TestSink\",\n" +
                "        \"message\": \"default\"\n" +
                "    },\n" +
                "    \"sink1\": {\n" +
                "        \"type\": \"TestSink\",\n" +
                "        \"message\": \"sink1\"\n" +
                "    }\n" +
                "}";
        SinkManager sinkManager = injector.getInstance(SinkManager.class);
        sinkManager.build(sinkDesc);

        StatusServer statusServer = injector.getInstance(StatusServer.class);
        statusServer.start(injector);
    }

    @AfterClass
    public static void shutdown() {
        StatusServer server = injector.getInstance(StatusServer.class);
        server.shutdown();
        manager.close();
    }

    @Test
    public void connectionFailureShouldBeDetected() throws Exception {
        HttpResponse response = runQuery("healthcheck");

        assertEquals(500, response.getStatusLine().getStatusCode());
    }

    private HttpResponse runQuery(String path) throws IOException {
        DefaultHttpClient client = new DefaultHttpClient();
        HttpGet httpget = new HttpGet(String.format("http://localhost:%d/%s", 7103, path));

        try{
            return client.execute(httpget);
        } finally{
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void healthcheckShouldPassForHealthyServer() throws Exception {
        TNonblockingServerSocket transport = new TNonblockingServerSocket(7101);

        Result r = new Result();
        TestServer server = new TestServer(r);

        @SuppressWarnings({ "rawtypes", "unchecked" })
        TProcessor processor =  new SuroServer.Processor(server);

        final THsHaServer main = new THsHaServer(new THsHaServer.Args(transport).processor(processor));
        new Thread(new Runnable(){

            @Override
            public void run() {
                main.serve();
            }
        }).start();

        try{
            // 2 seconds should be enough for a simple server to start up
            Thread.sleep(2000);

            HttpResponse response = runQuery("healthcheck");
            assertEquals(200, response.getStatusLine().getStatusCode());
        } finally{
            main.stop();
        }
    }

    @Test
    public void testSinkStat() throws IOException {
        HttpResponse response = runQuery("sinkstat");
        InputStream data = response.getEntity().getContent();
        BufferedReader br = new BufferedReader(new InputStreamReader(data));
        String line = null;
        StringBuilder sb = new StringBuilder();
        try {
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {}

        assertEquals(sb.toString(), "sink1:sink1 open\ndefault:default open\n");
    }
}
