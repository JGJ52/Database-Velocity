package hu.jgj52.databaseVelocity;

import redis.clients.jedis.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class Redis {
    private final JedisPool jedisPool;
    private final ExecutorService executor;
    private final Gson gson;

    public Redis(String host, int port, String password) {
        this.executor = Executors.newFixedThreadPool(4);
        this.gson = new Gson();

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(10);
        poolConfig.setMaxIdle(5);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestOnReturn(true);
        poolConfig.setTestWhileIdle(true);

        if (password != null && !password.isEmpty()) {
            this.jedisPool = new JedisPool(poolConfig, host, port, 30000, password);
        } else {
            this.jedisPool = new JedisPool(poolConfig, host, port, 30000);
        }
    }

    public Redis(String host, int port) {
        this(host, port, null);
    }

    public QueryBuilder from(String table) {
        return new QueryBuilder(this.jedisPool, this.executor, this.gson, table);
    }

    public CompletableFuture<QueryResult> query(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String value = jedis.get(key);
                if (value == null) {
                    return new QueryResult(new ArrayList<>(), null);
                }

                Map<String, Object> data = gson.fromJson(value,
                        new TypeToken<Map<String, Object>>(){}.getType());
                return new QueryResult(Arrays.asList(data), null);
            } catch (Exception e) {
                return new QueryResult(new ArrayList<>(), e.getMessage());
            }
        }, executor);
    }

    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    public static class QueryBuilder {
        private final JedisPool jedisPool;
        private final ExecutorService executor;
        private final Gson gson;
        private String table;
        private String selected = "*";
        private List<FilterClause> filters = new ArrayList<>();
        private String orderColumn = null;
        private boolean orderAscending = true;

        public QueryBuilder(JedisPool jedisPool, ExecutorService executor, Gson gson, String table) {
            this.jedisPool = jedisPool;
            this.executor = executor;
            this.gson = gson;
            this.table = table;
        }

        private QueryBuilder(JedisPool jedisPool, ExecutorService executor, Gson gson) {
            this.jedisPool = jedisPool;
            this.executor = executor;
            this.gson = gson;
        }

        public QueryBuilder select(String columns) {
            QueryBuilder newBuilder = this.clone();
            newBuilder.selected = columns;
            return newBuilder;
        }

        public QueryBuilder eq(String column, Object value) {
            QueryBuilder newBuilder = this.clone();
            newBuilder.filters.add(new FilterClause(column, value));
            return newBuilder;
        }

        public QueryBuilder order(String column, boolean ascending) {
            QueryBuilder newBuilder = this.clone();
            newBuilder.orderColumn = column;
            newBuilder.orderAscending = ascending;
            return newBuilder;
        }

        public QueryBuilder order(String column) {
            return order(column, true);
        }

        public QueryBuilder clone() {
            QueryBuilder newBuilder = new QueryBuilder(this.jedisPool, this.executor, this.gson);
            newBuilder.table = this.table;
            newBuilder.selected = this.selected;
            newBuilder.filters = new ArrayList<>(this.filters);
            newBuilder.orderColumn = this.orderColumn;
            newBuilder.orderAscending = this.orderAscending;
            return newBuilder;
        }

        private String getKeyPattern() {
            return table + ":*";
        }

        private String generateKey(String id) {
            return table + ":" + id;
        }

        private List<Map<String, Object>> getAllRecords(Jedis jedis) {
            Set<String> keys = jedis.keys(getKeyPattern());
            List<Map<String, Object>> records = new ArrayList<>();

            for (String key : keys) {
                String value = jedis.get(key);
                if (value != null) {
                    Map<String, Object> record = gson.fromJson(value,
                            new TypeToken<Map<String, Object>>(){}.getType());
                    record.put("_key", key);
                    records.add(record);
                }
            }

            return records;
        }

        private List<Map<String, Object>> applyFilters(List<Map<String, Object>> records) {
            if (filters.isEmpty()) {
                return records;
            }

            return records.stream()
                    .filter(record -> {
                        for (FilterClause filter : filters) {
                            Object value = record.get(filter.column);
                            if (value == null || !value.equals(filter.value)) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
        }

        private List<Map<String, Object>> applyOrder(List<Map<String, Object>> records) {
            if (orderColumn == null) {
                return records;
            }

            records.sort((a, b) -> {
                Object valA = a.get(orderColumn);
                Object valB = b.get(orderColumn);

                if (valA == null && valB == null) return 0;
                if (valA == null) return orderAscending ? -1 : 1;
                if (valB == null) return orderAscending ? 1 : -1;

                int comparison;
                if (valA instanceof Comparable) {
                    comparison = ((Comparable) valA).compareTo(valB);
                } else {
                    comparison = valA.toString().compareTo(valB.toString());
                }

                return orderAscending ? comparison : -comparison;
            });

            return records;
        }

        private Map<String, Object> selectColumns(Map<String, Object> record) {
            if ("*".equals(selected)) {
                return record;
            }

            String[] columns = selected.split(",");
            Map<String, Object> filtered = new HashMap<>();

            for (String col : columns) {
                String column = col.trim();
                if (record.containsKey(column)) {
                    filtered.put(column, record.get(column));
                }
            }

            return filtered;
        }

        public CompletableFuture<QueryResult> single() {
            return CompletableFuture.supplyAsync(() -> {
                try (Jedis jedis = jedisPool.getResource()) {
                    List<Map<String, Object>> records = getAllRecords(jedis);
                    records = applyFilters(records);

                    if (records.isEmpty()) {
                        return new QueryResult(new ArrayList<>(), null);
                    }

                    Map<String, Object> result = selectColumns(records.get(0));
                    return new QueryResult(Arrays.asList(result), null);
                } catch (Exception e) {
                    return new QueryResult(new ArrayList<>(), e.getMessage());
                }
            }, executor);
        }

        public CompletableFuture<QueryResult> delete() {
            return CompletableFuture.supplyAsync(() -> {
                try (Jedis jedis = jedisPool.getResource()) {
                    List<Map<String, Object>> records = getAllRecords(jedis);
                    records = applyFilters(records);

                    List<Map<String, Object>> deleted = new ArrayList<>();

                    for (Map<String, Object> record : records) {
                        String key = (String) record.get("_key");
                        if (key != null) {
                            jedis.del(key);
                            record.remove("_key");
                            deleted.add(record);
                        }
                    }

                    return new QueryResult(deleted, null);
                } catch (Exception e) {
                    return new QueryResult(new ArrayList<>(), e.getMessage());
                }
            }, executor);
        }

        public CompletableFuture<QueryResult> insert(Map<String, Object> data) {
            return CompletableFuture.supplyAsync(() -> {
                try (Jedis jedis = jedisPool.getResource()) {
                    String id = data.containsKey("id") ?
                            data.get("id").toString() :
                            UUID.randomUUID().toString();

                    data.put("id", id);
                    String key = generateKey(id);
                    String json = gson.toJson(data);

                    jedis.set(key, json);

                    return new QueryResult(Arrays.asList(data), null);
                } catch (Exception e) {
                    return new QueryResult(new ArrayList<>(), e.getMessage());
                }
            }, executor);
        }

        public CompletableFuture<QueryResult> update(Map<String, Object> data) {
            return CompletableFuture.supplyAsync(() -> {
                if (data == null || data.isEmpty()) {
                    return new QueryResult(new ArrayList<>(), "No update data provided");
                }

                try (Jedis jedis = jedisPool.getResource()) {
                    List<Map<String, Object>> records = getAllRecords(jedis);
                    records = applyFilters(records);

                    List<Map<String, Object>> updated = new ArrayList<>();

                    for (Map<String, Object> record : records) {
                        String key = (String) record.get("_key");
                        if (key != null) {
                            record.remove("_key");
                            record.putAll(data);

                            String json = gson.toJson(record);
                            jedis.set(key, json);

                            updated.add(new HashMap<>(record));
                        }
                    }

                    return new QueryResult(updated, null);
                } catch (Exception e) {
                    return new QueryResult(new ArrayList<>(), e.getMessage());
                }
            }, executor);
        }

        public CompletableFuture<QueryResult> execute() {
            return CompletableFuture.supplyAsync(() -> {
                try (Jedis jedis = jedisPool.getResource()) {
                    List<Map<String, Object>> records = getAllRecords(jedis);
                    records = applyFilters(records);
                    records = applyOrder(records);

                    List<Map<String, Object>> results = records.stream()
                            .map(record -> {
                                record.remove("_key");
                                return selectColumns(record);
                            })
                            .collect(Collectors.toList());

                    return new QueryResult(results, null);
                } catch (Exception e) {
                    return new QueryResult(new ArrayList<>(), e.getMessage());
                }
            }, executor);
        }
    }

    public static class QueryResult {
        public final List<Map<String, Object>> data;
        public final String error;

        public QueryResult(List<Map<String, Object>> data, String error) {
            this.data = data;
            this.error = error;
        }

        public boolean hasError() {
            return error != null;
        }

        public boolean isEmpty() {
            return data == null || data.isEmpty();
        }

        public Map<String, Object> first() {
            return isEmpty() ? null : data.get(0);
        }
    }

    private static class FilterClause {
        final String column;
        final Object value;

        FilterClause(String column, Object value) {
            this.column = column;
            this.value = value;
        }
    }
}