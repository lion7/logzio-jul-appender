package io.logz.jul;

import io.logz.test.MockLogzioBulkListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.logz.test.MockLogzioBulkListener.LogRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class LogzioHandlerTest {

	private final static Logger logger = Logger.getLogger(LogzioHandlerTest.class.getName());
	protected MockLogzioBulkListener mockListener;

	@Before
	public void startMockListener() throws Exception {
		mockListener = new io.logz.test.MockLogzioBulkListener();
		mockListener.start();
	}

	@After
	public void stopMockListener() {
		mockListener.stop();
	}

	@Test
	public void simpleAppending() throws Exception {
		String token = "aBcDeFgHiJkLmNoPqRsT";
		String type = "awesomeType";
		String loggerName = "simpleAppending";
		int drainTimeout = 1;
		String message1 = "Testing.." + random(5);
		String message2 = "Warning test.." + random(5);

		Logger testLogger = createLogger(token, type, loggerName, drainTimeout, false, null);
		testLogger.info(message1);
		testLogger.warning(message2);

		sleepSeconds(drainTimeout * 2);
		mockListener.assertNumberOfReceivedMsgs(2);
		mockListener.assertLogReceivedIs(message1, token, type, loggerName, Level.INFO.getName());
		mockListener.assertLogReceivedIs(message2, token, type, loggerName, Level.WARNING.getName());
	}

	@Test
	public void validateAdditionalFields() throws Exception {
		String token = "validatingAdditionalFields";
		String type = "willTryWithOrWithoutEnvironmentVariables";
		String loggerName = "additionalLogger";
		int drainTimeout = 1;
		String message1 = "Just a log - " + random(5);
		Map<String, String> additionalFields = new HashMap<>();
		String additionalFieldsString = "java_home=$JAVA_HOME;testing=yes;message=override";
		additionalFields.put("java_home", System.getenv("JAVA_HOME"));
		additionalFields.put("testing", "yes");

		Logger testLogger = createLogger(token, type, loggerName, drainTimeout, false, additionalFieldsString);
		testLogger.info(message1);

		sleepSeconds(2 * drainTimeout);

		mockListener.assertNumberOfReceivedMsgs(1);
		LogRequest logRequest = mockListener.assertLogReceivedByMessage(message1);
		mockListener.assertLogReceivedIs(logRequest, token, type, loggerName, Level.INFO.getName());
		assertAdditionalFields(logRequest, additionalFields);
	}

	@Test
	public void existingHostname() throws Exception {
		String token = "checkingHostname";
		String type = "withOrWithoutHostnamr";
		String loggerName = "runningOutOfIdeasHere";
		int drainTimeout = 1;
		String message1 = "Hostname log - " + random(5);

		Logger testLogger = createLogger(token, type, loggerName, drainTimeout, true, null);
		testLogger.info(message1);

		// Sleep double time the drain timeout
		sleepSeconds(2 * drainTimeout);

		mockListener.assertNumberOfReceivedMsgs(1);
		LogRequest logRequest = mockListener.assertLogReceivedByMessage(message1);
		mockListener.assertLogReceivedIs(logRequest, token, type, loggerName, Level.INFO.getName());

		String hostname = InetAddress.getLocalHost().getHostName();
		assertThat(logRequest.getHost()).isEqualTo(hostname);
	}

	@SuppressWarnings("ConstantConditions")
	@Test
	public void sendException() throws Exception {
		String token = "checkingExceptions";
		String type = "badType";
		String loggerName = "exceptionProducer";
		int drainTimeout = 1;
		Throwable exception = null;
		String message1 = "This is not an int..";

		Logger testLogger = createLogger(token, type, loggerName, drainTimeout, false, null);
		try {
			Integer.parseInt(message1);
		} catch (Exception e) {
			exception = e;
			testLogger.log(Level.INFO, message1, e);
		}
		assertThat(exception).isNotNull();
		sleepSeconds(2 * drainTimeout);

		mockListener.assertNumberOfReceivedMsgs(1);
		LogRequest logRequest = mockListener.assertLogReceivedByMessage(message1);
		mockListener.assertLogReceivedIs(logRequest, token, type, loggerName, Level.INFO.getName());

		String exceptionField = logRequest.getStringFieldOrNull("exception");
		if (exceptionField == null)
			fail("Exception field does not exists");
		assertThat(exceptionField.replace("\\", "")).contains(exception.getMessage());
	}

	@Test
	public void testTokenAndLogzioUrlFromSystemEnvironment() {
		String token = System.getenv("JAVA_HOME");
		String type = "testType";
		String loggerName = "testLogger";
		int drainTimeout = 1;
		String message1 = "Just a log - " + random(5);

		Logger testLogger = createLogger("$JAVA_HOME", type, loggerName, drainTimeout, false, null);
		testLogger.info(message1);

		sleepSeconds(2 * drainTimeout);

		mockListener.assertNumberOfReceivedMsgs(1);
		LogRequest logRequest = mockListener.assertLogReceivedByMessage(message1);
		mockListener.assertLogReceivedIs(logRequest, token, type, loggerName, Level.INFO.getName());
	}

	private Logger createLogger(String token, String type, String loggerName, Integer drainTimeout,
			boolean addHostname, String additionalFields) {
		logger.log(Level.INFO, "Creating logger {}. token={}, type={}, drainTimeout={}, addHostname={}, additionalFields={}",
				new Object[] { loggerName, token, type, drainTimeout, addHostname, additionalFields });
		Logger logger = Logger.getLogger(loggerName);
		String url = "http://" + mockListener.getHost() + ":" + mockListener.getPort();
		LogzioHandler logzioHandler = new LogzioHandler(url, token, type, drainTimeout, null, null, null, null, addHostname, additionalFields, true, null);
		logzioHandler.start();
		logger.addHandler(logzioHandler);
		return logger;
	}

	private void assertAdditionalFields(LogRequest logRequest, Map<String, String> additionalFields) {
		additionalFields.forEach((field, value) -> {
			String fieldValueInLog = logRequest.getStringFieldOrNull(field);
			assertThat(fieldValueInLog)
					.describedAs("Field '{}' in Log [{}]", field, logRequest.getJsonObject().toString())
					.isNotNull()
					.isEqualTo(value);
		});
	}

	private void sleepSeconds(int seconds) {
		logger.info("Sleeping " + seconds + " [sec]...");
		try {
			Thread.sleep(seconds * 1000);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private String random(int numberOfChars) {
		return UUID.randomUUID().toString().substring(0, numberOfChars - 1);
	}
}