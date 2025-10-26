package com.td.uploader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.converter.crypto.CryptoDataFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

@Component
public class UploaderRoute extends RouteBuilder {

	@Value("${DB_URL}")
	String dbUrl;
	@Value("${DB_USERNAME:}")
	String dbUser;
	@Value("${DB_PASSWORD:}")
	String dbPass;

	@Value("${CRYPTO_ALGO:AES/CBC/PKCS5Padding}")
	String cryptoAlgo;
	@Value("${CRYPTO_KEY_HEX}")
	String keyHex;
	@Value("${CRYPTO_IV_HEX}")
	String ivHex;

	@Override
	public void configure() {

		final SecretKeySpec secretKey = new SecretKeySpec(hexToBytes(keyHex), "AES");
		final byte[] ivBytes = hexToBytes(ivHex);

		final CryptoDataFormat crypto = new CryptoDataFormat(cryptoAlgo, secretKey);
		crypto.setInitializationVector(ivBytes);
		crypto.setShouldAppendHMAC(false);

		from("spring-redis://{{REDIS_HOST}}:{{REDIS_PORT}}?command=SUBSCRIBE&channels={{REDIS_CHANNEL}},{{REDIS_DLQ_CHANNEL}}")
				.routeId("save-and-upload")
				.process(e -> {
					String json = e.getMessage().getBody(String.class);
					ObjectMapper m = new ObjectMapper();
					JsonNode root = m.readTree(json);

					String kind = root.path("type").asText("RAW");
					String extr = root.path("extractor").asText("unknown");
					String path = root.path("path").asText();

					e.setProperty("kind", kind);
					e.setProperty("extractor", extr);
					e.setProperty("filePath", path);
					e.setProperty("fileName", Paths.get(path).getFileName().toString());
				})
				// Save to SQLite
				.process(e -> {
					try (Connection c = getConnection()) {
						try (PreparedStatement ps = c.prepareStatement(
								"INSERT INTO files(extractor, kind, filename, filepath, inserted_at) " +
										"VALUES(?,?,?,?,CURRENT_TIMESTAMP)")) {
							ps.setString(1, (String) e.getProperty("extractor"));
							ps.setString(2, (String) e.getProperty("kind"));
							ps.setString(3, (String) e.getProperty("fileName"));
							ps.setString(4, (String) e.getProperty("filePath"));
							ps.executeUpdate();
						}
					}
				})

				.setHeader("CamelFileName", simple("${exchangeProperty.fileName}.enc"))
				.process(e -> {
					byte[] data = Files.readAllBytes(Paths.get((String) e.getProperty("filePath")));
					e.getMessage().setBody(data);
				})

				.marshal(crypto) // <<< ESTE ES EL CAMBIO CLAVE

				.toD("sftp://{{SFTP_HOST}}:{{SFTP_PORT}}{{SFTP_REMOTE_DIR}}"
						+ "?username={{SFTP_USER}}"
						+ "&privateKeyUri=file:{{SFTP_KEY_PATH}}"
						+ "&privateKeyPassphrase={{SFTP_KEY_PASSPHRASE}}"
						+ "&strictHostKeyChecking={{SFTP_STRICT_HOST_KEY_CHECKING}}"
						+ "&knownHostsFile={{SFTP_KNOWN_HOSTS}}"
						+ "&throwExceptionOnConnectFailed=true")
				.log("Uploaded ${header.CamelFileName} for ${exchangeProperty.extractor} (${exchangeProperty.kind})");
	}

	private Connection getConnection() throws Exception {
		if (dbUser == null || dbUser.isBlank()) return DriverManager.getConnection(dbUrl);
		return DriverManager.getConnection(dbUrl, dbUser, dbPass);
	}

	private static byte[] hexToBytes(String hex) {
		if (hex == null || hex.isBlank()) return new byte[0];
		int len = hex.length();
		byte[] out = new byte[len / 2];
		for (int i = 0; i < len; i += 2)
			out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
		return out;
	}

}
