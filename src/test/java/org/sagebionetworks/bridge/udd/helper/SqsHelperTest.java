package org.sagebionetworks.bridge.udd.helper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;

import java.util.List;

import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageResult;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.config.Config;

public class SqsHelperTest {
    @Test
    public void testPoll() {
        // mock env config
        Config mockEnvConfig = mock(Config.class);
        when(mockEnvConfig.get(SqsHelper.CONFIG_KEY_SQS_QUEUE_URL)).thenReturn("dummy-sqs-queue-url");

        // mock sqs client - first test case returns no messages, then 1 message, then 3 messages
        ReceiveMessageResult firstSqsResult = new ReceiveMessageResult().withMessages();

        Message secondMessage = new Message();
        ReceiveMessageResult secondSqsResult = new ReceiveMessageResult().withMessages(secondMessage);

        AmazonSQSClient mockSqsClient = mock(AmazonSQSClient.class);
        ArgumentCaptor<ReceiveMessageRequest> sqsRecvRequestCaptor = ArgumentCaptor.forClass(
                ReceiveMessageRequest.class);
        when(mockSqsClient.receiveMessage(sqsRecvRequestCaptor.capture())).thenReturn(firstSqsResult, secondSqsResult);

        // set up test helper
        SqsHelper sqsHelper = new SqsHelper();
        sqsHelper.setEnvConfig(mockEnvConfig);
        sqsHelper.setSqsClient(mockSqsClient);

        // test #1 - no message
        Message retVal1 = sqsHelper.poll();
        assertNull(retVal1);

        // test #2 - one message
        Message retVal2 = sqsHelper.poll();
        assertSame(retVal2, secondMessage);

        // validate SQS request args
        List<ReceiveMessageRequest> sqsRecvRequestList = sqsRecvRequestCaptor.getAllValues();
        assertEquals(sqsRecvRequestList.size(), 2);

        ReceiveMessageRequest sqsRecvRequest1 = sqsRecvRequestList.get(0);
        assertEquals(sqsRecvRequest1.getMaxNumberOfMessages().intValue(), 1);
        assertEquals(sqsRecvRequest1.getQueueUrl(), "dummy-sqs-queue-url");
        assertEquals(sqsRecvRequest1.getWaitTimeSeconds().intValue(), 20);

        ReceiveMessageRequest sqsRecvRequest2 = sqsRecvRequestList.get(1);
        assertEquals(sqsRecvRequest2.getMaxNumberOfMessages().intValue(), 1);
        assertEquals(sqsRecvRequest2.getQueueUrl(), "dummy-sqs-queue-url");
        assertEquals(sqsRecvRequest2.getWaitTimeSeconds().intValue(), 20);
    }

    @Test
    public void testDelete() {
        // This is just a pass through. Trivial test to test the receipt handle is passed through

        // mock env config
        Config mockEnvConfig = mock(Config.class);
        when(mockEnvConfig.get(SqsHelper.CONFIG_KEY_SQS_QUEUE_URL)).thenReturn("dummy-sqs-queue-url");

        // mock sqs client
        AmazonSQSClient mockSqsClient = mock(AmazonSQSClient.class);

        // set up test helper
        SqsHelper sqsHelper = new SqsHelper();
        sqsHelper.setEnvConfig(mockEnvConfig);
        sqsHelper.setSqsClient(mockSqsClient);

        // execute and validate
        sqsHelper.deleteMessage("test-receipt-handle");
        verify(mockSqsClient).deleteMessage("dummy-sqs-queue-url", "test-receipt-handle");
    }
}
