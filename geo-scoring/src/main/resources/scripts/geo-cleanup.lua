-- Atomic cleanup of expired geo-index members.
-- Removes members whose insertion timestamp is at or before the cutoff
-- from the GEO sorted set, the TTL tracking sorted set and the counts hash.
-- Batch-limited to avoid blocking Redis for extended periods.
--
-- KEYS[1]  = geo-index key (e.g. "geo:{DE}")
-- KEYS[2]  = TTL tracking sorted set key (e.g. "geo:{DE}:ttl")
-- KEYS[3]  = counts hash key (e.g. "geo:{DE}:counts"); field = enrollment ID
--           All three share the {countryCode} hash tag → one Redis Cluster slot (no CROSSSLOT).
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
redis.call('HDEL', KEYS[3], unpack(expired))

return #expired
