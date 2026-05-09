package com.example.backend.assignment.service;

import com.example.backend.assignment.configuration.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Set;


@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedisTemplate<String, String> redisTemplate;

    public void incrementViralityScore(String postId, int points) {
        redisTemplate.opsForValue().increment(RedisKeys.viralityScore(postId), points);
    }

    public Long getViralityScore(String postId) {
        String val = redisTemplate.opsForValue().get(RedisKeys.viralityScore(postId));
        return val == null ? 0L : Long.parseLong(val);
    }

    public void checkAndIncrementBotCount(String postId) {
        String key = RedisKeys.botCount(postId);

        Long result = redisTemplate.execute(
                RedisKeys.botCountScript,
                List.of(key),
                String.valueOf(RedisKeys.BOT_HORIZONTAL_CAP)
        );

        if (result != null && result == -1L) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Post " + postId + " has reached the limit of " + RedisKeys.BOT_HORIZONTAL_CAP + " bot replies."
            );
        }
    }

    public Long getBotCount(String postId) {
        String val = redisTemplate.opsForValue().get(RedisKeys.botCount(postId));
        return val == null ? 0L : Long.parseLong(val);
    }


    public void checkAndSetCooldown(String botId, String userId) {
        String key = RedisKeys.cooldown(botId, userId);
        Boolean wasAbsent = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", Duration.ofSeconds(RedisKeys.COOLDOWN_TTL_SECS));

        if (Boolean.FALSE.equals(wasAbsent)) {
            Long ttl = redisTemplate.getExpire(key);
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Bot " + botId + " is on cooldown for user " + userId + ". Retry in " + ttl + " seconds."
            );
        }
    }

    public void runBotGuardrails(String postId, String botId, String targetUserId) {

        checkAndSetCooldown(botId, targetUserId);
        checkAndIncrementBotCount(postId);
    }

    public void handleNotification(String userId, String botName, String postId) {
        String cooldownKey = RedisKeys.notifCooldown(userId);
        String pendingKey = RedisKeys.pendingNotifs(userId);

        Boolean onCooldown = redisTemplate.hasKey(cooldownKey);

        if (Boolean.TRUE.equals(onCooldown)) {
            redisTemplate.opsForList().rightPush(pendingKey, botName + " replied to your post #" + postId);
        } else {
            log.info("Push Notification Sent to User {}: {} replied to post #{}", userId, botName, postId);
            redisTemplate.opsForValue().set(
                cooldownKey, "1", Duration.ofSeconds(RedisKeys.NOTIF_COOLDOWN_SECS)
            );
        }
    }

    @Scheduled(fixedRate = 300_000)
    public void sweepPendingNotifications() {
        log.info("CRON SWEEPER: Starting sweep");

        Set<String> keys = redisTemplate.keys("user:*:pending_notifs");
        if (keys == null || keys.isEmpty()) {
            log.info("CRON SWEEPER: NotHing to flush.");
            return;
        }

        for (String key : keys) {
            List<String> messages = redisTemplate.opsForList().range(key, 0, -1);
            if (messages == null || messages.isEmpty()) continue;

            String userId = key.split(":")[1];
            String firstName = messages.get(0).split(" replied")[0];
            int total = messages.size();

            if (total == 1) {
                log.info("Summarized Push Notification for user {}: {}", userId, messages.get(0));
            } else {
                log.info("Summarized Push Notification for user {}: {} and {} others interacted with your Posts.", userId, firstName, total - 1);
            }

            redisTemplate.delete(key);
        }

        log.info("CRON SWEEPER: Done. Processed {} users.", keys.size());
    }

}
