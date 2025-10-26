package com.td.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

@Component
public class ExtractorRoute extends RouteBuilder {

	private final Environment env;
	public ExtractorRoute(Environment env) { this.env = env; }

	@Override
	public void configure() {

		// Retries con backoff 5s -> 15s -> 45s
		errorHandler(defaultErrorHandler()
				.maximumRedeliveries(3) // Number of attempts
				.redeliveryDelay(5000) //5seconds
				.useExponentialBackOff()
				.backOffMultiplier(3.0)); // 1*5 = 5s , 3*5 = 15s, 6*5 = 30s

		// When all retries fail, store the message in the Dead Letter Queue.
		onException(Exception.class)
				.handled(true)
				.process(e -> {
					ObjectMapper m = new ObjectMapper();
					String extractor = (String) e.getProperty("extractor"); //This will be set on extraction
					String payload = m.createObjectNode()
							.put("type", "DLQ")
							.put("extractor", extractor == null ? "unknown" : extractor)
							.put("error", String.valueOf(e.getProperty(Exchange.EXCEPTION_CAUGHT)))
							.put("skip", e.getProperty("skip") == null ? 0 : (Integer) e.getProperty("skip"))
							.toString();
					e.getMessage().setBody(payload);
				})
				.toD("spring-redis://{{REDIS_HOST}}:{{REDIS_PORT}}?command=PUBLISH&channel={{REDIS_DLQ_CHANNEL}}")
				.stop();

		// EXTRACTORS=users,products (CSV Format)
		String csv = env.getProperty("EXTRACTORS", "users");
		//Main execution
		Arrays.stream(csv.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.forEach(this::buildRouteFromEnv);
	}

	private void buildRouteFromEnv(String name) {
		String KEY = name.toUpperCase().replaceAll("[^A-Z0-9]", "_");
		String url  = must(KEY + "_URL");
		String arr  = defaultValue(KEY + "_ARRAY_FIELD", "items"); // {users:[{},{}]}
		int    amountLimit  = Integer.parseInt(defaultValue(KEY + "_LIMIT", "100"));
		String cron = defaultValue(KEY + "_CRON", "0+0+2+*+*+?"); // Quartz cron

		//Save an Integer with last number of records extracted
		final Path STATE_PATH = Paths.get("/data/state/" + name + "-last-skip.state");

		fromF("quartz://extractor-%s?cron=%s", name, cron)
				.routeId("extractor-" + name)
				.process(e -> {
					e.setProperty("extractor", name);
					String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
					e.setProperty("timestamp", ts);
					e.setProperty("rawFile", "/data/raw_users/" + name + "/records_" + ts + ".jsonl");

					int skip = 0;
					if (Files.exists(STATE_PATH)) {
						String s = Files.readString(STATE_PATH).trim();
						if (!s.isBlank()) skip = Integer.parseInt(s);
					}
					e.setProperty("skip", skip);
					e.setProperty("hasMore", true);
				})
				//Repeat until all the records were read.
				.loopDoWhile(exchangeProperty("hasMore"))
				.process(e -> {
					int s = (int) e.getProperty("skip");
					e.getMessage().setHeader(Exchange.HTTP_QUERY, "limit=" + amountLimit + "&skip=" + s);
				})
				.toD(url + "?throwExceptionOnFailure=true")
				.process(e -> {
					String body = e.getMessage().getBody(String.class);
					ObjectMapper mapper = new ObjectMapper();
					JsonNode root = mapper.readTree(body);

					int total = root.get("total").asInt();
					int skip  = root.get("skip").asInt();
					ArrayNode records = (ArrayNode) root.get(arr);

					Path out = Paths.get((String) e.getProperty("rawFile"));
					Files.createDirectories(out.getParent());
					try (BufferedWriter w = Files.newBufferedWriter(out, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
						for (JsonNode u : records) { w.write(u.toString()); w.newLine(); }
					}

					int fetched = skip + records.size();
					Files.createDirectories(STATE_PATH.getParent());
					Files.writeString(STATE_PATH, String.valueOf(fetched),
							StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

					boolean hasMore = fetched < total;
					e.setProperty("hasMore", hasMore);
					e.setProperty("skip", fetched);
				})
				.end()
				//After read all records, teh skip state place to zero
				.process(e -> Files.writeString(STATE_PATH, "0",
						StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
				.process(e -> {
					ObjectMapper m = new ObjectMapper();
					String payload = m.createObjectNode()
							.put("type", "RAW")
							.put("extractor", name)
							.put("path", (String) e.getProperty("rawFile"))
							.toString();
					e.getMessage().setBody(payload);
				})
				.toD("spring-redis://{{REDIS_HOST}}:{{REDIS_PORT}}?command=PUBLISH&channel={{REDIS_CHANNEL}}")
				.log("Published: ${body}");
	}

	private String defaultValue(String key, String dft) {
		String v = env.getProperty(key);
		return (v == null || v.isBlank()) ? dft : v;
	}
	private String must(String key) {
		String v = env.getProperty(key);
		if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing env: " + key);
		return v;
	}
}