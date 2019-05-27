package io.dropwizard.actors.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;
import io.dropwizard.actors.connectivity.RMQConnection;
import io.dropwizard.actors.utils.NamingUtils;
import java.io.IOException;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UnmanagedPublishActor<Message> {

  private final String name;
  private final ActorConfig config;
  private final RMQConnection connection;
  private final ObjectMapper mapper;
  private final String queueName;

  private Channel publishChannel;

  public UnmanagedPublishActor(
      String name,
      ActorConfig config,
      RMQConnection connection,
      ObjectMapper mapper) {
    this.name = name;
    this.config = config;
    this.connection = connection;
    this.mapper = mapper;
    this.queueName = NamingUtils.queueName(config.getPrefix(), name);
  }

  public final void publishWithDelay(Message message, long delayMilliseconds) throws Exception {
    log.info("Publishing message to exchange with delay: {}", delayMilliseconds);
    if (!config.isDelayed()) {
      log.warn("Publishing delayed message to non-delayed queue queue:{}", queueName);
    }

    if (config.getDelayType() == DelayType.TTL) {
      publishChannel.basicPublish(ttlExchange(config),
          queueName,
          new AMQP.BasicProperties.Builder()
              .expiration(String.valueOf(delayMilliseconds))
              .deliveryMode(2)
              .build(),
          mapper().writeValueAsBytes(message));
    } else {
      publish(message, new AMQP.BasicProperties.Builder()
          .headers(Collections.singletonMap("x-delay", delayMilliseconds))
          .deliveryMode(2)
          .build());
    }
  }

  public final void publish(Message message) throws Exception {
    publish(message, MessageProperties.MINIMAL_PERSISTENT_BASIC);
  }

  public final void publish(Message message, AMQP.BasicProperties properties) throws Exception {
    publishChannel.basicPublish(config.getExchange(), queueName, properties, mapper().writeValueAsBytes(message));
  }

  public void start() throws Exception {
    final String exchange = config.getExchange();
    final String dlx = config.getExchange() + "_SIDELINE";
    if (config.isDelayed()) {
      ensureDelayedExchange(exchange);
    } else {
      ensureExchange(exchange);
    }
    ensureExchange(dlx);

    this.publishChannel = connection.newChannel();
    connection.ensure(queueName + "_SIDELINE", queueName, dlx);
    connection.ensure(queueName, config.getExchange(), connection.rmqOpts(dlx));
    if (config.getDelayType() == DelayType.TTL) {
      connection.ensure(ttlQueue(queueName), queueName, ttlExchange(config), connection.rmqOpts(exchange));
    }
  }

  private void ensureExchange(String exchange) throws IOException {
    connection.channel().exchangeDeclare(
        exchange,
        "direct",
        true,
        false,
        ImmutableMap.<String, Object>builder()
            .put("x-ha-policy", "all")
            .put("ha-mode", "all")
            .build());
  }

  private void ensureDelayedExchange(String exchange) throws IOException {
    if (config.getDelayType() == DelayType.TTL) {
      ensureExchange(ttlExchange(config));
    } else {
      connection.channel().exchangeDeclare(
          exchange,
          "x-delayed-message",
          true,
          false,
          ImmutableMap.<String, Object>builder()
              .put("x-ha-policy", "all")
              .put("ha-mode", "all")
              .put("x-delayed-type", "direct")
              .build());
    }
  }

  private String ttlExchange(ActorConfig actorConfig) {
    return String.format("%s_TTL", actorConfig.getExchange());
  }

  private String ttlQueue(String queueName) {
    return String.format("%s_TTL", queueName);
  }

  public void stop() throws Exception {
    try {
      publishChannel.close();
    } catch (Exception e) {
      log.error(String.format("Error closing publisher:%s", name), e);
      throw e;
    }
  }

  protected final RMQConnection connection() throws Exception {
    return connection;
  }

  protected final ObjectMapper mapper() {
    return mapper;
  }
}