package com.hedera.mirror.grpc.jmeter.client;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import io.grpc.StatusRuntimeException;
import java.io.PrintWriter;
import java.io.StringWriter;
import lombok.extern.log4j.Log4j2;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;

import com.hedera.mirror.grpc.jmeter.ConnectionHandler;

@Log4j2
public class ConsensusServiceReactiveClient extends AbstractJavaSamplerClient {
    com.hedera.mirror.grpc.jmeter.sampler.ConsensusServiceReactiveSampler csclient = null;
    int futureMessagesCount = 2;
    long topicNum;

    @Override
    public void setupTest(JavaSamplerContext context) {
        String host = context.getParameter("host");
        String port = context.getParameter("port");
        String limit = context.getParameter("limit");
        String consensusStartTimeSeconds = context.getParameter("consensusStartTimeSeconds");
        String consensusEndTimeSeconds = context.getParameter("consensusEndTimeSeconds");
        String topicID = context.getParameter("topicID");
        String realmNum = context.getParameter("realmNum");
        futureMessagesCount = context.getIntParameter("newTopicsMessageCount");
        topicNum = Long.parseLong(topicID);

        ConnectionHandler connHandl = new ConnectionHandler();

        csclient = new com.hedera.mirror.grpc.jmeter.sampler.ConsensusServiceReactiveSampler(
                host,
                Integer.parseInt(port),
                topicNum,
                Long.parseLong(realmNum),
                Long.parseLong(consensusStartTimeSeconds),
                Long.parseLong(consensusEndTimeSeconds),
                Long.parseLong(limit),
                connHandl);

        // to:do - explore a setup that gets the db in the desired state, with a useful historical data and cleared
        // references to future messages from previous runs
        super.setupTest(context);
    }

    @Override
    public Arguments getDefaultParameters() {
        Arguments defaultParameters = new Arguments();
        defaultParameters.addArgument("host", "localhost");
        defaultParameters.addArgument("port", "5600");
        defaultParameters.addArgument("limit", "100");
        defaultParameters.addArgument("consensusStartTimeSeconds", "0");
        defaultParameters.addArgument("consensusEndTimeSeconds", "0");
        defaultParameters.addArgument("topicID", "0");
        defaultParameters.addArgument("realmNum", "0");
        defaultParameters.addArgument("newTopicsMessageCount", "0");
        return defaultParameters;
    }

    @Override
    public SampleResult runTest(JavaSamplerContext context) {
        SampleResult result = new SampleResult();
        boolean success = true;
        String response = "";
        result.sampleStart();

        int threadNum = context.getJMeterContext().getThreadNum();
        try {
            log.info("Thread {} : Kicking off subscribeTopic", threadNum);
            response = csclient.subscribeTopic(futureMessagesCount);

            // To:do - add conditional logic based on response to check success criteria

            result.sampleEnd();
            result.setResponseData(response.getBytes());
            result.setResponseMessage("Successfully performed subscribe topic test");
            result.setResponseCodeOK();
            log.info("Successfully performed subscribe topic test");
        } catch (InterruptedException intEx) {
            log.warn("RCP failed relating to CountDownLatch: {}", intEx);
        } catch (StatusRuntimeException ex) {
            result.sampleEnd();
            success = false;
            result.setResponseMessage("Exception: " + ex);
            log.error("Error subscribing to topic: " + ex);

            StringWriter stringWriter = new StringWriter();
            ex.printStackTrace(new PrintWriter(stringWriter));
            result.setResponseData(stringWriter.toString().getBytes());
            result.setDataType(SampleResult.TEXT);
            result.setResponseCode("500");
        }

        result.setSuccessful(success);

        // shutdown test and avoid notifying waiting for signal - saves run time
//        teardownTest(context);

        return result;
    }

    @Override
    public void teardownTest(JavaSamplerContext context) {
        try {
            csclient.shutdown();
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }

        super.teardownTest(context);
    }
}
