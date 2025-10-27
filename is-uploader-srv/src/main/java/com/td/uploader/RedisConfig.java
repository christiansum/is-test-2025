package com.td.uploader;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
public class RedisConfig {

	@Bean(name = "redisConnectionFactory")
	public LettuceConnectionFactory redisConnectionFactory(Environment env) {
		String host = env.getProperty("REDIS_HOST", "redis");
		int port = Integer.parseInt(env.getProperty("REDIS_PORT", "6379"));
		String pwd = env.getProperty("REDIS_PASSWORD", "");
		var conf = new RedisStandaloneConfiguration(host, port);
		if (pwd != null && !pwd.isBlank()) conf.setPassword(pwd);
		return new LettuceConnectionFactory(conf);
	}

	@Bean
	public RedisTemplate<String,String> redisTemplate(LettuceConnectionFactory cf) {
		var t = new RedisTemplate<String,String>();
		t.setConnectionFactory(cf);
		return t;
	}
}