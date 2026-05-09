package com.example.backend.assignment.configuration;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

public final class RedisKeys {

    private RedisKeys() {}

    public static String viralityScore(String postId) {
        return "post:" + postId + ":virality_score";
    }

    public static String botCount(String postId) {
        return "post:" + postId + ":bot_count";
    }

    public static String cooldown(String botId, String userId) {
        return "cooldown:bot_" + botId + ":human_" + userId;
    }

    public static String notifCooldown(String userId) {
        return "notif_cooldown:user_" + userId;
    }

    public static String pendingNotifs(String userId) {
        return "user:" + userId + ":pending_notifs";
    }




    public static final int BOT_HORIZONTAL_CAP = 100;
    public static final long COOLDOWN_TTL_SECS = 600;
    public static final long NOTIF_COOLDOWN_SECS = 900;
    public static final RedisScript<Long> botCountScript = new DefaultRedisScript<>(
            """
            local current = redis.call('INCR', KEYS[1])
            if current > tonumber(ARGV[1]) then
                redis.call('DECR', KEYS[1])
                return -1
            end
            return current
            """,
            Long.class
    );

}
