-- Atomic cleanup of expired geo-index members.
-- Removes members whose insertion timestamp is at or before the cutoff
-- from both the GEO sorted set and the TTL tracking sorted set.
-- Batch-limited to avoid blocking Redis for extended periods.
--
-- KEYS[1]  = geo-index key (e.g. "geo:DE")
-- KEYS[2]  = TTL tracking sorted set key (e.g. "geo:DE:ttl")
-- ARGV[1]  = cutoff epoch seconds (inclusive — members at or before this time are expired)
-- ARGV[2]  = batch limit (max members to remove per invocation)
--
-- Returns: number of members removed (caller should loop until 0).

local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
if #expired == 0 then
    return 0
end

redis.call('ZREM', KEYS[1], unpack(expired))
redis.call('ZREM', KEYS[2], unpack(expired))

return #expired
