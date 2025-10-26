package com.td.processor;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class ProcessorRoute extends RouteBuilder {
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	@Override
	public void configure() {

		from("file:/data/raw_users?recursive=true&include=.*records_.*\\.jsonl&move=.done")
				.routeId("processor-validate")
				.convertBodyTo(String.class)
				.process(e -> {
					Path parent = Paths.get((String) e.getMessage().getHeader("CamelFileParent"));
					String extractor = parent.getFileName().toString();
					e.setProperty("extractor", extractor);

					String content = e.getMessage().getBody(String.class);
					String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());

					Path okOut  = Paths.get("/data/processed_users/" + extractor + "/etl_" + ts + ".jsonl");
					Path dlqOut = Paths.get("/data/dlq/" + extractor + "/invalid_users_" + ts + ".jsonl");
					Files.createDirectories(okOut.getParent());
					Files.createDirectories(dlqOut.getParent());

					// departments.csv -> Map<dep, code>
					Map<String,String> deptMap = new HashMap<>();
					try (BufferedReader br = Files.newBufferedReader(Paths.get("/data/departments.csv"))) {
						String line; boolean header = true;
						while ((line = br.readLine()) != null) {
							if (header) { header = false; continue; }
							String[] p = line.split(",");
							if (p.length >= 2) deptMap.put(p[0].trim(), p[1].trim());
						}
					}

					ObjectMapper mapper = new ObjectMapper();
					try (BufferedReader in = new BufferedReader(new StringReader(content));
					     BufferedWriter ok = Files.newBufferedWriter(okOut,  StandardOpenOption.CREATE, StandardOpenOption.APPEND);
					     BufferedWriter dlq = Files.newBufferedWriter(dlqOut, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

						String line;
						while ((line = in.readLine()) != null) {
							if (line.isBlank()) continue;
							JsonNode u = mapper.readTree(line);
							List<String> errs = new ArrayList<>();

							// Fields Validation
							if (!u.has("id") || !u.get("id").canConvertToInt()) errs.add("Id must be integer");
							if (!u.has("firstName") || u.get("firstName").asText().isBlank()) errs.add("FirstName empty");
							if (!u.has("email") || !EMAIL.matcher(u.get("email").asText()).matches()) errs.add("Invalid Email");
							if (!u.has("age") || u.get("age").asInt() < 18 || u.get("age").asInt() > 65) errs.add("Age out of range (18-65)");
							if (!u.has("company") || !u.get("company").has("department")
									|| u.get("company").get("department").asText().isBlank())
								errs.add("Company.department empty");

							if (!errs.isEmpty()) {
								((ObjectNode) u).put("error_reason", String.join("; ", errs));
								dlq.write(u.toString()); dlq.newLine();
							} else {
								String dep = u.get("company").get("department").asText();
								String code = deptMap.getOrDefault(dep, "UNK");
								((ObjectNode) u).put("department_code", code);
								ok.write(u.toString()); ok.newLine();
							}
						}
					}

					e.setProperty("okPath", okOut.toString());
					e.setProperty("dlqPath", dlqOut.toString());
				})
				// Send to queue
				.process(e -> {
					ObjectMapper m = new ObjectMapper();
					String extractor = (String) e.getProperty("extractor");
					String okPath  = (String) e.getProperty("okPath");
					String dlqPath = (String) e.getProperty("dlqPath");

					if (okPath != null) {
						String ev = m.createObjectNode().put("type","PROCESSED").put("extractor", extractor).put("path", okPath).toString();
						e.getContext().createProducerTemplate().sendBody(
								"spring-redis://{{REDIS_HOST}}:{{REDIS_PORT}}?command=PUBLISH&channel={{REDIS_CHANNEL}}", ev);
					}
					if (dlqPath != null) {
						String ev = m.createObjectNode().put("type","DLQ").put("extractor", extractor).put("path", dlqPath).toString();
						e.getContext().createProducerTemplate().sendBody(
								"spring-redis://{{REDIS_HOST}}:{{REDIS_PORT}}?command=PUBLISH&channel={{REDIS_CHANNEL}}", ev);
					}
				})
				.log("Processor emitted events for ${exchangeProperty.extractor}");
	}
}
