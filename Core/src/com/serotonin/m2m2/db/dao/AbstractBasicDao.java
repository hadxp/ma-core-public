/**
 * Copyright (C) 2016  Infinite Automation Software. All rights reserved.
 */
package com.serotonin.m2m2.db.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.infiniteautomation.mango.db.query.ConditionSortLimit;
import com.infiniteautomation.mango.db.query.RQLSubSelectCondition;
import com.infiniteautomation.mango.db.query.RQLToCondition;
import com.infiniteautomation.mango.monitor.AtomicIntegerMonitor;
import com.infiniteautomation.mango.spring.db.AbstractBasicTableDefinition;
import com.infiniteautomation.mango.spring.events.DaoEvent;
import com.infiniteautomation.mango.spring.events.DaoEventType;
import com.infiniteautomation.mango.util.RQLUtils;
import com.serotonin.ModuleNotLoadedException;
import com.serotonin.ShouldNeverHappenException;
import com.serotonin.db.MappedRowCallback;
import com.serotonin.log.LogStopWatch;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.db.DatabaseProxy.DatabaseType;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.vo.AbstractBasicVO;
import com.serotonin.m2m2.vo.permission.PermissionHolder;
import net.jazdw.rql.parser.ASTNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Provides an API to retrieve, update and save VO objects from and to the
 * database.
 *
 * @author Jared Wiltshire
 * @author Terry Packer
 */
public abstract class AbstractBasicDao<T extends AbstractBasicVO, TABLE extends AbstractBasicTableDefinition> extends BaseDao implements AbstractBasicVOAccess<T, TABLE> {
    protected Log LOG = LogFactory.getLog(AbstractBasicDao.class);

    //Retry transactions that deadlock
    //TODO Mango 4.0 make the retry criteria more accurate
    protected final int transactionRetries = Common.envProps.getInt("db.transaction.retries", 5);

    protected final TABLE table;
    protected final ObjectMapper mapper;
    protected final ApplicationEventPublisher eventPublisher;

    //Monitor for count of table
    protected final AtomicIntegerMonitor countMonitor;

    protected final Map<String, Field<?>> aliasMap;
    protected final Map<String, RQLSubSelectCondition> subSelectMap;
    protected final Map<String, Function<Object, Object>> valueConverterMap;

    public AbstractBasicDao(TABLE table, ObjectMapper mapper, ApplicationEventPublisher publisher) {
        this(table, null, mapper, publisher);
    }

    /**
     * @param table - table definition
     * @param countMonitorName - If not null create a monitor to track table row count
     * @param mapper
     * @param publisher
     */
    public AbstractBasicDao(TABLE table,
            TranslatableMessage countMonitorName,
            ObjectMapper mapper, ApplicationEventPublisher publisher) {

        this.table = table;
        this.mapper = mapper;
        this.eventPublisher = publisher;

        // Map of potential property names to db field aliases
        this.aliasMap = this.createAliasMap();

        // Map of potential property names to sub select conditions
        this.subSelectMap = this.createSubSelectMap();

        // Map of properties to their QueryAttribute
        this.valueConverterMap = this.createValueConverterMap();

        //Setup Monitors
        if(countMonitorName != null) {
            this.countMonitor = Common.MONITORED_VALUES.create(this.getClass().getCanonicalName() + ".COUNT")
                    .name(countMonitorName)
                    .value(this.count())
                    .uploadToStore(true)
                    .buildAtomic();
        }else{
            this.countMonitor = null;
        }
    }

    /**
     * Converts a VO object into an array of objects for insertion or updating
     * of database
     *
     * @param vo
     *            to convert
     * @return object array
     */
    protected abstract Object[] voToObjectArray(T vo);

    /**
     * Condition required for user to have read permission.  Override as required, note
     *  that when overriding the user can and will sometimes be null
     * @param user - reading user (can be null)
     * @return
     */
    protected Condition hasReadPermission(PermissionHolder user) {
        return DSL.trueCondition();
    }

    /**
     * Gets the row mapper for converting the retrieved database values into a
     * VO object
     *
     * @return row mapper
     */
    public abstract RowMapper<T> getRowMapper();

    @Override
    public boolean delete(int id) {
        return delete(get(id));
    }

    @Override
    public boolean delete(T vo) {
        if (vo != null) {
            int deleted = 0;
            int tries = transactionRetries;
            while(tries > 0) {
                try {
                    deleted = withLockedRow(vo.getId(), (txStatus) -> {
                        deleteRelationalData(vo);
                        int result = this.create.deleteFrom(this.table.getTable()).where(this.table.getIdField().eq(vo.getId())).execute();
                        if(result > 0) {
                            deletePostRelationalData(vo);
                        }
                        return result;
                    });
                    break;
                }catch(org.jooq.exception.DataAccessException | ConcurrencyFailureException e) {
                    if(tries == 1) {
                        throw e;
                    }
                }
                tries--;
            }

            if(this.countMonitor != null) {
                this.countMonitor.addValue(-deleted);
            }

            if(deleted > 0) {
                this.publishEvent(createDaoEvent(DaoEventType.DELETE, vo, null));
            }

            return deleted > 0;
        }
        return false;
    }

    @Override
    public void deleteRelationalData(T vo) { }

    @Override
    public void deletePostRelationalData(T vo) { }

    @Override
    public void insert(T vo) {
        int tries = transactionRetries;
        while(tries > 0) {
            try {
                doInTransaction(status -> {
                    savePreRelationalData(null, vo);
                    int id = -1;
                    InsertValuesStepN<?> insert = this.create.insertInto(this.table.getTable()).columns(this.table.getInsertFields()).values(voToObjectArray(vo));
                    String sql = insert.getSQL();
                    List<Object> args = insert.getBindValues();
                    id = ejt.doInsert(sql, args.toArray(new Object[args.size()]));
                    vo.setId(id);
                    saveRelationalData(null, vo);
                    return null;
                });
                break;
            }catch(org.jooq.exception.DataAccessException | ConcurrencyFailureException e) {
                if(tries == 1) {
                    throw e;
                }
            }
            tries--;
        }

        this.publishEvent(createDaoEvent(DaoEventType.CREATE, vo, null));

        if (this.countMonitor != null)
            this.countMonitor.increment();
    }

    @Override
    public void savePreRelationalData(T existing, T vo) { }

    @Override
    public void saveRelationalData(T existing, T vo) { }

    @Override
    public void update(int id, T vo) {
        update(get(id), vo);
    }

    @Override
    public void update(T existing, T vo) {
        int tries = transactionRetries;
        while(tries > 0) {
            try {
                doInTransaction(status -> {
                    savePreRelationalData(existing, vo);
                    List<Object> list = new ArrayList<>();
                    list.addAll(Arrays.asList(voToObjectArray(vo)));
                    Map<Field<?>, Object> values = new LinkedHashMap<>();
                    int i = 0;
                    for(Field<?> f : this.table.getUpdateFields()) {
                        values.put(f, list.get(i));
                        i++;
                    }
                    UpdateConditionStep<?> update = this.create.update(this.table.getTable()).set(values).where(this.table.getIdField().eq(vo.getId()));
                    String sql = update.getSQL();
                    List<Object> args = update.getBindValues();
                    ejt.update(sql, args.toArray(new Object[args.size()]));
                    saveRelationalData(existing, vo);
                    return null;
                });
                break;
            }catch(org.jooq.exception.DataAccessException | ConcurrencyFailureException e) {
                if(tries == 1) {
                    throw e;
                }
            }
            tries--;
        }

        this.publishEvent(createDaoEvent(DaoEventType.UPDATE, vo, existing));
    }

    @Override
    public T get(int id) {
        Select<Record> query = this.getJoinedSelectQuery()
                .where(this.table.getIdAlias().eq(id))
                .limit(1);
        String sql = query.getSQL();
        List<Object> args = query.getBindValues();
        T item = ejt.query(sql, args.toArray(new Object[args.size()]), getObjectResultSetExtractor());
        if (item != null) {
            loadRelationalData(item);
        }
        return item;
    }

    @Override
    public void getAll(MappedRowCallback<T> callback) {
        Select<Record> query = this.getJoinedSelectQuery();
        String sql = query.getSQL();
        List<Object> args = query.getBindValues();
        query(sql, args.toArray(), getCallbackResultSetExtractor((item, index) -> {
            loadRelationalData(item);
            callback.row(item, index);
        }));
    }

    @Override
    public List<T> getAll() {
        List<T> items = new ArrayList<>();
        getAll((item, index) -> {
            items.add(item);
        });
        return items;
    }

    @Override
    public SelectJoinStep<Record> getSelectQuery(List<Field<?>> fields) {
        return this.create.selectDistinct(fields)
                .from(this.table.getTableAsAlias());
    }

    @Override
    public SelectJoinStep<Record> getJoinedSelectQuery() {
        SelectJoinStep<Record> query = getSelectQuery(getSelectFields());
        return joinTables(query, null);
    }

    @Override
    public void loadRelationalData(T vo) { }

    @Override
    public int count() {
        return getCountQuery().from(this.table.getTableAsAlias()).fetchOneInto(Integer.class);
    }

    /**
     * Get the base Count query
     * @return
     */
    @Override
    public SelectSelectStep<Record1<Integer>> getCountQuery() {
        if (this.table.getIdAlias() == null) {
            return this.create.selectCount();
        } else {
            return this.create.select(DSL.countDistinct(this.table.getIdAlias()));
        }
    }

    /**
     * Helper to prepare a statement
     *
     * @param sql
     * @param args
     * @return
     * @throws SQLException
     */
    public PreparedStatement createPreparedStatement(String sql, List<Object> args, boolean stream) throws SQLException {

        PreparedStatement stmt;
        if(stream){
            if(this.databaseType == DatabaseType.MYSQL){
                stmt = this.dataSource.getConnection().prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                stmt.setFetchSize(Integer.MIN_VALUE);
            }else{
                //TODO Choose settings for other types to stream
                stmt = this.dataSource.getConnection().prepareStatement(sql);
            }
        }else{
            stmt = this.dataSource.getConnection().prepareStatement(sql);
            int fetchSize = Common.envProps.getInt("db.fetchSize", -1);
            if(fetchSize > 0)
                stmt.setFetchSize(fetchSize);
        }

        int index = 1;
        for (Object o : args) {
            stmt.setObject(index, o);
            index++;
        }

        return stmt;
    }

    public AtomicIntegerMonitor getCountMonitor(){
        return this.countMonitor;
    }

    /**
     * Get the select columns, override as necessary
     * @return
     */
    public List<Field<?>> getSelectFields() {
        return this.table.getSelectFields();
    }

    @Override
    public TABLE getTable() {
        return table;
    }

    /**
     * Add any joins including those in the conditions
     *  Override as necessary
     * @param select
     * @return
     */
    @Override
    public <R extends Record> SelectJoinStep<R> joinTables(SelectJoinStep<R> select, ConditionSortLimit conditions) {
        return select;
    }

    /**
     * Join on permission read conditions to limit results to what the user can 'see'
     */
    @Override
    public <R extends Record> SelectJoinStep<R> joinPermissions(SelectJoinStep<R> select, ConditionSortLimit conditions, PermissionHolder user) {
        return select;
    }

    @Override
    public int customizedCount(ConditionSortLimit conditions, PermissionHolder user) {
        SelectSelectStep<Record1<Integer>> count = getCountQuery();

        SelectJoinStep<Record1<Integer>> select = count.from(this.table.getTableAsAlias());
        select = joinTables(select, conditions);
        select = joinPermissions(select, conditions, user);
        return customizedCount(select, conditions.getCondition());
    }

    @Override
    public int customizedCount(SelectJoinStep<Record1<Integer>> input, Condition condition) {
        Select<Record1<Integer>> select = input;
        if (condition != null) {
            select = input.where(condition);
        }

        String sql = select.getSQL();
        List<Object> arguments = select.getBindValues();
        Object[] argumentsArray = arguments.toArray(new Object[arguments.size()]);

        LogStopWatch stopWatch = null;
        if (useMetrics) {
            stopWatch = new LogStopWatch();
        }

        int count = this.ejt.queryForInt(sql, argumentsArray, 0);

        if (stopWatch != null) {
            stopWatch.stop("customizedCount(): " + this.create.renderInlined(select), metricsThreshold);
        }

        return count;
    }

    @Override
    public void customizedQuery(ConditionSortLimit conditions, PermissionHolder user, MappedRowCallback<T> callback) {
        SelectJoinStep<Record> select = getSelectQuery(getSelectFields());
        select = joinTables(select, conditions);
        select = joinPermissions(select, conditions, user);
        customizedQuery(select, conditions.getCondition(), conditions.getSort(), conditions.getLimit(), conditions.getOffset(), callback);
    }

    @Override
    public void customizedQuery(SelectJoinStep<Record> select, Condition condition, List<SortField<Object>> sort, Integer limit, Integer offset,
            MappedRowCallback<T> callback) {
        SelectConnectByStep<Record> afterWhere = condition == null ? select : select.where(condition);

        SelectLimitStep<Record> afterSort = sort == null ? afterWhere : afterWhere.orderBy(sort);

        Select<Record> offsetStep = afterSort;
        if (limit != null) {
            if (offset != null) {
                offsetStep = afterSort.limit(offset, limit);
            } else {
                offsetStep = afterSort.limit(limit);
            }
        }

        customizedQuery(offsetStep, getCallbackResultSetExtractor(callback));
    }

    @Override
    public void customizedQuery(Select<Record> select, MappedRowCallback<T> callback) {
        customizedQuery(select, getCallbackResultSetExtractor(callback));
    }

    @Override
    public <TYPE> TYPE customizedQuery(Select<Record> select,
            ResultSetExtractor<TYPE> callback) {

        String sql = select.getSQL();
        List<Object> arguments = select.getBindValues();
        Object[] argumentsArray = arguments.toArray(new Object[arguments.size()]);

        LogStopWatch stopWatch = null;
        if (useMetrics) {
            stopWatch = new LogStopWatch();
        }
        try {
            return this.query(sql, argumentsArray, callback);
        }finally {
            if (stopWatch != null) {
                stopWatch.stop("customizedQuery(): " + this.create.renderInlined(select), metricsThreshold);
            }
        }
    }

    protected Map<String, Field<?>> createAliasMap() {
        return this.table.getAliasMap();
    }

    protected Map<String, RQLSubSelectCondition> createSubSelectMap() {
        return this.table.getSubSelectMap();
    }

    protected Map<String, Function<Object, Object>> createValueConverterMap() {
        return this.table.getFieldMap().entrySet().stream()
                .filter(e -> e.getValue().getDataType().getSQLDataType() == SQLDataType.CHAR)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> RQLToCondition.BOOLEAN_VALUE_CONVERTER));
    }

    @Override
    public ConditionSortLimit rqlToCondition(ASTNode rql, Map<String, RQLSubSelectCondition> subSelectMapping, Map<String, Field<?>> fieldMap, Map<String, Function<Object, Object>> valueConverters) {
        RQLToCondition rqlToCondition = createRqlToCondition(combine(this.subSelectMap, subSelectMapping), combine(this.aliasMap, fieldMap), combine(this.valueConverterMap, valueConverters));
        ConditionSortLimit conditions = rqlToCondition.visit(rql);
        return conditions;
    }

    protected <X, Y> Map<X,Y> combine(Map<X,Y> a, Map<X,Y> b) {
        if (b == null || b.isEmpty()) {
            return Collections.unmodifiableMap(a);
        }
        HashMap<X,Y> result = new HashMap<>(a);
        result.putAll(b);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Create a stateful rql to condition (Override as necessary)
     * @param subSelectMapping not null
     * @param fieldMap not null
     * @param converterMap not null
     * @return
     */
    protected RQLToCondition createRqlToCondition(Map<String, RQLSubSelectCondition> subSelectMapping, Map<String, Field<?>> fieldMap, Map<String, Function<Object, Object>> converterMap) {
        return new RQLToCondition(subSelectMapping, fieldMap, converterMap);
    }

    protected DaoEvent<T> createDaoEvent(DaoEventType type, T vo, T existing) {
        if (type == DaoEventType.UPDATE) {
            return new DaoEvent<T>(this, type, vo, existing);
        } else {
            return new DaoEvent<T>(this, type, vo);
        }
    }

    protected void publishEvent(DaoEvent<T> event) {
        if (this.eventPublisher != null) {
            this.eventPublisher.publishEvent(event);
        }
    }

    /**
     * Get a writer for serializing JSON
     * @return
     */
    public ObjectWriter getObjectWriter(Class<?> type) {
        return mapper.writerFor(type);
    }

    /**
     * Get a reader for use de-serializing JSON
     * @return
     */
    public ObjectReader getObjectReader(Class<?> type) {
        return mapper.readerFor(type);
    }

    /**
     * Available to overload the result set extractor for list queries
     * @param callback
     * @return
     */
    protected ResultSetExtractor<T> getObjectResultSetExtractor() {
        return getObjectResultSetExtractor((e,rs) -> {
            if(e.getCause() instanceof ModuleNotLoadedException) {
                //We will log and continue as to not prevent someone from loading module based VOs for
                // which the modules are actually installed.
                LOG.error(e.getCause().getMessage(), e.getCause());
            }else {
                LOG.error(e.getMessage(), e);
                //TODO Mango 4.0 What shall we do here? most likely this caused by a bug in the code and we
                // want to see the 500 error in the API etc.
                throw new ShouldNeverHappenException(e);
            }
        });
    }

    /**
     *
     * @param error
     * @return
     */
    protected ResultSetExtractor<T> getObjectResultSetExtractor(BiConsumer<Exception, ResultSet> error) {
        return new ResultSetExtractor<T>() {

            @Override
            public T extractData(ResultSet rs) throws SQLException, DataAccessException {
                RowMapper<T> rowMapper = getRowMapper();
                List<T> results = new ArrayList<>();
                int rowNum = 0;
                while (rs.next()) {
                    try {
                        T row = rowMapper.mapRow(rs, rowNum);
                        loadRelationalData(row);
                        results.add(row);
                    }catch (Exception e) {
                        error.accept(e, rs);
                    }finally {
                        rowNum++;
                    }
                    return DataAccessUtils.uniqueResult(results);
                }
                return null;
            }
        };
    }

    /**
     * Available to overload the result set extractor for list queries
     * @param callback
     * @return
     */
    protected ResultSetExtractor<List<T>> getListResultSetExtractor() {
        return getListResultSetExtractor((e,rs) -> {
            if(e.getCause() instanceof ModuleNotLoadedException) {
                LOG.error(e.getCause().getMessage(), e.getCause());
            }else {
                LOG.error(e.getMessage(), e);
                //TODO Mango 4.0 What shall we do here? most likely this caused by a bug in the code and we
                // want to see the 500 error in the API etc.
                throw new ShouldNeverHappenException(e);
            }
        });
    }

    /**
     *
     * @param error
     * @return
     */
    protected ResultSetExtractor<List<T>> getListResultSetExtractor(BiConsumer<Exception, ResultSet> error) {
        return new ResultSetExtractor<List<T>>() {

            @Override
            public List<T> extractData(ResultSet rs) throws SQLException, DataAccessException {
                RowMapper<T> rowMapper = getRowMapper();
                List<T> results = new ArrayList<>();
                int rowNum = 0;
                while (rs.next()) {
                    try {
                        T row = rowMapper.mapRow(rs, rowNum);
                        loadRelationalData(row);
                        results.add(row);
                    }catch (Exception e) {
                        error.accept(e, rs);
                    }finally {
                        rowNum++;
                    }
                }
                return results;
            }
        };
    }

    /**
     * Available to overload the result set extractor for callback queries
     *  to customize error handling
     * @param callback
     * @return
     */
    protected ResultSetExtractor<Void> getCallbackResultSetExtractor(MappedRowCallback<T> callback) {
        return getCallbackResultSetExtractor(callback, (e, rs) -> {
            if(e.getCause() instanceof ModuleNotLoadedException) {
                LOG.error(e.getCause().getMessage(), e.getCause());
            }else {
                LOG.error(e.getMessage(), e);
                //TODO Mango 4.0 What shall we do here? most likely this caused by a bug in the code and we
                // want to see the 500 error in the API etc.
                throw new ShouldNeverHappenException(e);
            }
        });
    }

    /**
     *
     * @param callback
     * @param error
     * @return
     */
    protected ResultSetExtractor<Void> getCallbackResultSetExtractor(MappedRowCallback<T> callback, BiConsumer<Exception, ResultSet> error) {
        return new ResultSetExtractor<Void>() {

            @Override
            public Void extractData(ResultSet rs) throws SQLException, DataAccessException {
                RowMapper<T> rowMapper = getRowMapper();
                int rowNum = 0;
                while (rs.next()) {
                    try {
                        T row = rowMapper.mapRow(rs, rowNum);
                        loadRelationalData(row);
                        callback.row(row, rowNum);
                    }catch (Exception e) {
                        error.accept(e, rs);
                    }finally {
                        rowNum++;
                    }
                }
                return null;
            }
        };
    }

    @Override
    public void lockRow(int id) {
        this.create.select().from(this.table.getTableAsAlias())
        .where(this.table.getIdAlias().eq(id))
        .forUpdate()
        .fetch();
    }

    /**
     * Helper to convert JSON Node for db
     * @param data
     * @return
     */
    protected String convertData(JsonNode data) {
        try {
            if(data == null) {
                return null;
            }else {
                return getObjectWriter(JsonNode.class).writeValueAsString(data);
            }
        }catch(JsonProcessingException e) {
            LOG.error(e.getMessage(), e);
        }
        return null;
    }

    protected JsonNode extractData(Clob c) throws SQLException {
        try {
            if(c != null) {
                return getObjectReader(JsonNode.class).readValue(c.getCharacterStream());
            }else {
                return null;
            }
        }catch(Exception e) {
            throw new SQLException(e);
        }
    }

    @Override
    public int count(PermissionHolder user) {
        return customizedCount(new ConditionSortLimit(null, null, null, null), user);
    }

    @Override
    public int count(PermissionHolder user, String rql) {
        ConditionSortLimit csl = rqlToCondition(RQLUtils.parseRQLtoAST(rql), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        return customizedCount(csl, user);
    }

    @Override
    public List<T> list(PermissionHolder user) {
        List<T> list = new ArrayList<>();
        list(user, list::add);
        return list;
    }

    @Override
    public void list(PermissionHolder user, Consumer<T> consumer) {
        customizedQuery(new ConditionSortLimit(null, null, null, null), user, consumer);
    }

    @Override
    public List<T> query(PermissionHolder user, String rql) {
        List<T> list = new ArrayList<>();
        query(user, rql, list::add);
        return list;
    }

    @Override
    public void query(PermissionHolder user, String rql, Consumer<T> consumer) {
        ConditionSortLimit csl = rqlToCondition(RQLUtils.parseRQLtoAST(rql), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        customizedQuery(csl, user, consumer);
    }

    @Override
    public QueryBuilder<T> buildQuery(PermissionHolder user) {
        return new QueryBuilder<T>(aliasMap, valueConverterMap, csl -> customizedCount(csl, user), (csl, consumer) -> customizedQuery(csl, user, consumer));
    }

}
