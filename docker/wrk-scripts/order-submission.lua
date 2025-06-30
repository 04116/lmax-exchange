-- wrk Lua script for LMAX Exchange order submission testing
-- Usage: wrk -t4 -c100 -d30s -s order-submission.lua http://lmax-exchange:8080/api/v1/orders

-- Request counter for generating unique orders
local counter = 0
local thread_id = 0

-- Initialize the random seed
math.randomseed(os.time())

-- Order templates
local order_types = {"LIMIT", "MARKET"}
local order_sides = {"BUY", "SELL"}
local symbols = {"BTCUSD", "ETHUSD"}

-- Price ranges for different symbols
local price_ranges = {
    BTCUSD = {min = 40000, max = 50000},
    ETHUSD = {min = 2500, max = 3500}
}

function setup(thread)
    thread_id = thread_id + 1
    thread:set("id", thread_id)
end

function init(args)
    -- Set request headers
    wrk.headers["Content-Type"] = "application/json"
    wrk.headers["Accept"] = "application/json"
    
    -- Initialize counters
    counter = 0
    
    print("Initialized order submission testing")
end

function request()
    counter = counter + 1
    
    -- Generate random order parameters
    local symbol = symbols[math.random(#symbols)]
    local order_type = order_types[math.random(#order_types)]
    local side = order_sides[math.random(#order_sides)]
    local qty = math.random(1, 100)
    
    -- Generate realistic prices for LIMIT orders
    local price = nil
    if order_type == "LIMIT" then
        local range = price_ranges[symbol]
        price = math.random(range.min, range.max) + math.random()
    end
    
    -- Create unique user ID
    local user_id = string.format("trader_%d_%d", thread_id, counter)
    
    -- Build request body
    local body = {
        user = user_id,
        symbol = symbol,
        type = order_type,
        side = side,
        qty = qty
    }
    
    -- Add price for LIMIT orders
    if price then
        body.price = string.format("%.2f", price)
    end
    
    -- Convert to JSON
    local json_body = json_encode(body)
    
    return wrk.format("POST", nil, nil, json_body)
end

function response(status, headers, body)
    -- Track response statistics
    if status ~= 202 and status ~= 200 then
        print(string.format("Error response: %d - %s", status, body))
    end
end

function done(summary, latency, requests)
    print("=====================================")
    print("LMAX Exchange Order Submission Test Results")
    print("=====================================")
    print(string.format("Requests:      %d", summary.requests))
    print(string.format("Duration:      %.2fs", summary.duration / 1000000))
    print(string.format("TPS:           %.2f requests/sec", summary.requests / (summary.duration / 1000000)))
    print(string.format("Avg Latency:   %.2fms", latency.mean / 1000))
    print(string.format("Max Latency:   %.2fms", latency.max / 1000))
    print(string.format("90th Percentile: %.2fms", latency:percentile(90) / 1000))
    print(string.format("99th Percentile: %.2fms", latency:percentile(99) / 1000))
    print(string.format("Errors:        %d", summary.errors.connect + summary.errors.read + summary.errors.write + summary.errors.status + summary.errors.timeout))
    print("=====================================")
end

-- Simple JSON encoder
function json_encode(obj)
    local json_str = "{"
    local first = true
    
    for key, value in pairs(obj) do
        if not first then
            json_str = json_str .. ","
        end
        first = false
        
        json_str = json_str .. string.format('"%s":', key)
        
        if type(value) == "string" then
            json_str = json_str .. string.format('"%s"', value)
        else
            json_str = json_str .. tostring(value)
        end
    end
    
    json_str = json_str .. "}"
    return json_str
end 