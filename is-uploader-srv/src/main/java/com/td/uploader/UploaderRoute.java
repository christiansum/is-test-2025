package com.td.uploader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.converter.crypto.CryptoDataFormat;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

@Component
public class UploaderRoute extends RouteBuilder {

	private final DataSource dataSource;
	private final Environment env;

	public UploaderRoute(DataSource dataSource, Environment env) {
		this.dataSource = dataSource;
		this.env = env;
	}

	@Override
	public void configure() {

		final String dataDir = defaultValue("DATA_DIR", "/data");

		final int maxRedeliveries = Integer.parseInt(defaultValue("RETRIES", "3"));
		final long redeliveryDelayMs = Long.parseLong(defaultValue("REDELIVERY_DELAY_MS", "5000"));
		final double backoffMultiplier = Double.parseDouble(defaultValue("BACKOFF_MULTIPLIER", "3.0"));

		errorHandler(defaultErrorHandler()
				.maximumRedeliveries(maxRedeliveries)
				.redeliveryDelay(redeliveryDelayMs)
				.useExponentialBackOff()
				.backOffMultiplier(backoffMultiplier));

		// === Redis (SUBSCRIBE) ===
		final String redisHost = defaultValue("REDIS_HOST", "redis");
		final String redisPort = defaultValue("REDIS_PORT", "6379");
		final String redisPwd  = defaultValue("REDIS_PASSWORD", "");
		final String chMain    = defaultValue("REDIS_CHANNEL", "files.events");
		final String chDlq     = defaultValue("REDIS_DLQ_CHANNEL", "files.dlq");

		StringBuilder redisSub = new StringBuilder()
				.append("spring-redis://").append(redisHost).append(":").append(redisPort)
				.append("?command=SUBSCRIBE&channels=").append(chMain).append(",").append(chDlq);
		if (!redisPwd.isBlank()) redisSub.append("&password=").append(redisPwd);

		// === Crypto (AES) ===
		final String cryptoAlgo = defaultValue("CRYPTO_ALGO", "AES/CBC/PKCS5Padding");
		final byte[] keyBytes   = hexToBytes("CRYPTO_KEY_HEX");
		final byte[] ivBytes    = hexToBytes("CRYPTO_IV_HEX");

		final SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
		final CryptoDataFormat crypto = new CryptoDataFormat(cryptoAlgo, secretKey);
		crypto.setInitializationVector(ivBytes);
		crypto.setShouldAppendHMAC(false);

		// === SFTP (SSH key) ===
		final String sftpHost   = defaultValue("SFTP_HOST", "sftp");
		final String sftpPort   = defaultValue("SFTP_PORT", "22");
		final String sftpUser   = defaultValue("SFTP_USER", "camel");
		final String sftpKey    = defaultValue("SFTP_KEY_PATH", "/run/secrets/sftp_key");
		final String sftpPass   = defaultValue("SFTP_KEY_PASSPHRASE", "");
		final String sftpDir    = defaultValue("SFTP_REMOTE_DIR", "/upload");
		final String sftpStrict = defaultValue("SFTP_STRICT_HOST_KEY_CHECKING", "true");
		final String sftpKnown  = defaultValue("SFTP_KNOWN_HOSTS", "/run/secrets/known_hosts");

		StringBuilder sftpUri = new StringBuilder()
				.append("sftp://").append(sftpHost).append(":").append(sftpPort).append(sftpDir)
				.append("?username=").append(sftpUser)
				.append("&privateKeyUri=file:").append(sftpKey)
				.append("&throwExceptionOnConnectFailed=true");
		if (!sftpPass.isBlank()) sftpUri.append("&privateKeyPassphrase=").append(sftpPass);
		if (!sftpStrict.isBlank()) sftpUri.append("&strictHostKeyChecking=").append(sftpStrict);
		if (!sftpKnown.isBlank())  sftpUri.append("&knownHostsFile=").append(sftpKnown);

		//Main Function - ProcessFile
		from(redisSub.toString())
				.routeId("uploader-subscribe")
				.process(e -> {
					String json = e.getMessage().getBody(String.class);
					ObjectMapper m = new ObjectMapper();
					JsonNode root = m.readTree(json);

					String kind = root.path("type").asText("RAW");
					String ext  = root.path("extractor").asText("unknown");
					String path = root.path("path").asText();

					e.setProperty("kind", kind);
					e.setProperty("extractor", ext);
					e.setProperty("filePath", path);
					e.setProperty("fileName", Paths.get(path).getFileName().toString());
				})
				.to("direct:uploadFile");


		final String cron = defaultValue("UPLOADER_CRON", "0+0/5+*+*+*+?");
		fromF("quartz://uploader-sweeper?cron=%s", cron)
				.routeId("uploader-sweeper")
				.process(e -> {
					// Search *.jsonl en raw, processed, dlq
					List<Path> toProcess = new ArrayList<>();
					scanJsonl(Paths.get(dataDir, "processed_users"), toProcess);
					scanJsonl(Paths.get(dataDir, "dlq"), toProcess);
					scanJsonl(Paths.get(dataDir, "raw_users"), toProcess);
					e.setProperty("scanResults", toProcess);
				})
				.split(exchangeProperty("scanResults"))
				.process(e -> {
					Path p = (Path) e.getMessage().getBody();
					String path = p.toString();
					String name = p.getFileName().toString();

					// Avoid duplicates
					Path marker = Paths.get(path + ".enc.uploaded");
					if (Files.exists(marker)) {
						e.setProperty("skipUpload", true);
						return;
					}

					String extractor = p.getParent() != null ? p.getParent().getFileName().toString() : "unknown";
					String kind = deduceKind(path); // RAW | PROCESSED | DLQ

					e.setProperty("skipUpload", false);
					e.setProperty("extractor", extractor);
					e.setProperty("kind", kind);
					e.setProperty("filePath", path);
					e.setProperty("fileName", name);
				})
				.filter(exchangeProperty("skipUpload").isEqualTo(false))
				.to("direct:uploadFile")
				.end();

		// Upload a File
		from("direct:uploadFile")
				.routeId("uploader-core")
				// Insert metadata en SQLite
				.process(e -> {
					String extractor = (String) e.getProperty("extractor");
					String kind      = (String) e.getProperty("kind");
					String fileName  = (String) e.getProperty("fileName");
					String filePath  = (String) e.getProperty("filePath");
					try (Connection c = dataSource.getConnection();
					     PreparedStatement ps = c.prepareStatement(
							     "INSERT INTO files(extractor, kind, filename, filepath, inserted_at) " +
									     "VALUES(?,?,?,?,CURRENT_TIMESTAMP)")) {
						ps.setString(1, extractor);
						ps.setString(2, kind);
						ps.setString(3, fileName);
						ps.setString(4, filePath);
						ps.executeUpdate();
					}
				})
				// Security, cipher, upload
				.setHeader("CamelFileName", simple("${exchangeProperty.fileName}.enc"))
				.process(e -> {
					byte[] data = Files.readAllBytes(Paths.get((String) e.getProperty("filePath")));
					e.getMessage().setBody(data);
				})
				.marshal(crypto)
				.toD(sftpUri.toString())
				.process(e -> {
					String path = (String) e.getProperty("filePath");
					Path marker = Paths.get(path + ".enc.uploaded");
					try { Files.createFile(marker); } catch (IOException ignore) {

					}
				})
				.log("Uploaded ${header.CamelFileName} for ${exchangeProperty.extractor} (${exchangeProperty.kind})");
	}

	private String defaultValue(String key, String dft) {
		String v = env.getProperty(key);
		return (v == null || v.isBlank()) ? dft : v;
	}

	private String must(String key) {
		String v = env.getProperty(key);
		if (v == null || v.isBlank())
			throw new IllegalArgumentException("Missing required env property: " + key);
		return v;
	}

	private static byte[] hexToBytes(String hex){
		if (hex == null || hex.isBlank()) return new byte[0];
		int len = hex.length(); byte[] out = new byte[len/2];
		for(int i=0;i<len;i+=2)
			out[i/2]=(byte)((Character.digit(hex.charAt(i),16)<<4)+Character.digit(hex.charAt(i+1),16));
		return out;
	}

	private static void scanJsonl(Path root, List<Path> out) throws IOException {
		if (!Files.exists(root)) return;
		try (var s = Files.walk(root)) {
			s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jsonl"))
					.forEach(out::add);
		}
	}

	private static String deduceKind(String path) {
		if (path.contains("/processed_users/")) return "PROCESSED";
		if (path.contains("/dlq/"))            return "DLQ";
		return "RAW";
	}
}