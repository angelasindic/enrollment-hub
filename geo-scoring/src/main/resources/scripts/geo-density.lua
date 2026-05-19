-- Atomic density check + index operation.
-- Eliminates the concurrent GEOSEARCH/GEOADD race (F-3) by executing
-- all searches and the GEOADD in a single atomic Lua script.
--
-- KEYS[1]  = geo-index key (e.g. "geo:DE")
-- KEYS[2]  = TTL tracking sorted set key (e.g. "geo:DE:ttl")
-- ARGV[1]  = longitude
-- ARGV[2]  = latitude
-- ARGV[3]  = account ID (member to index)
-- ARGV[4]  = search limit (COUNT cap, e.g. 200)
-- ARGV[5]  = insertion epoch seconds (for TTL tracking)
-- ARGV[6..N] = radii in metres (e.g. 100, 250, 500)
--
-- Returns: array of neighbor counts, one per radius (in the same order as input radii).

local key    = KEYS[1]
local ttlKey = KEYS[2]
local lon    = ARGV[1]
local lat    = ARGV[2]
local member = ARGV[3]
local limit  = tonumber(ARGV[4])
local epoch  = ARGV[5]

local counts = {}
for i = 6, #ARGV do
    local neighbors = redis.call(
        'GEOSEARCH', key,
        'FROMLONLAT', lon, lat,
        'BYRADIUS', ARGV[i], 'm',
        'ASC',
        'COUNT', limit
    )
    counts[#counts + 1] = #neighbors
end

redis.call('GEOADD', key, lon, lat, member)
redis.call('ZADD', ttlKey, epoch, member)

return counts
