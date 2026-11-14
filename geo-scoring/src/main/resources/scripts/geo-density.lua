-- Atomic, idempotent density check + index operation.
-- Eliminates the concurrent GEOSEARCH/GEOADD race (F-3) by executing
-- all searches and the GEOADD in a single atomic Lua script.
-- A repeated request for the same enrollment ID and the same radii returns the counts
-- of its first run and writes nothing.
--
-- KEYS[1]  = geo-index key (e.g. "geo:{DE}")
-- KEYS[2]  = TTL tracking sorted set key (e.g. "geo:{DE}:ttl")
-- KEYS[3]  = counts hash key (e.g. "geo:{DE}:counts"); field = enrollment ID,
--           value = JSON {"radii":[100,250,500],"counts":[0,1,2]} of its first run
--           All three share the {countryCode} hash tag, so they occupy one Redis Cluster
--           slot — required for this multi-key script (avoids CROSSSLOT).
-- ARGV[1]  = longitude
-- ARGV[2]  = latitude
-- ARGV[3]  = enrollment ID (member to index; a repeated request reuses it)
-- ARGV[4]  = search limit (COUNT cap, e.g. 200)
-- ARGV[5]  = insertion epoch seconds (for TTL tracking)
-- ARGV[6..N] = radii in metres (e.g. 100, 250, 500)
--
-- Returns: array of neighbor counts, one per radius (in the same order as input radii).

local key       = KEYS[1]
local ttlKey    = KEYS[2]
local countsKey = KEYS[3]
local lon       = ARGV[1]
local lat       = ARGV[2]
local member    = ARGV[3]
local limit     = tonumber(ARGV[4])
local epoch     = ARGV[5]

local radii = {}
for i = 6, #ARGV do
    radii[#radii + 1] = tonumber(ARGV[i])
end

-- Lua compares tables by identity, so the radii are compared as joined strings.
local stored = redis.call('HGET', countsKey, member)
if stored then
    local entry = cjson.decode(stored)
    if table.concat(entry.radii, ',') == table.concat(radii, ',') then
        return entry.counts
    end
end

local counts = {}
for i = 1, #radii do
    local neighbors = redis.call(
        'GEOSEARCH', key,
        'FROMLONLAT', lon, lat,
        'BYRADIUS', radii[i], 'm',
        'ASC',
        'COUNT', limit
    )
    counts[i] = #neighbors
end

redis.call('GEOADD', key, lon, lat, member)
-- NX keeps the first insertion time, so a later write cannot extend the member's lifetime.
redis.call('ZADD', ttlKey, 'NX', epoch, member)
redis.call('HSET', countsKey, member, cjson.encode({ radii = radii, counts = counts }))

return counts
