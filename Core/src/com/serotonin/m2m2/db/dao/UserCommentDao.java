/**
 * Copyright (C) 2015 Infinite Automation Software. All rights reserved.
 * @author Terry Packer
 */
package com.serotonin.m2m2.db.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infiniteautomation.mango.spring.MangoRuntimeContextConfiguration;
import com.infiniteautomation.mango.util.LazyInitSupplier;
import com.serotonin.ShouldNeverHappenException;
import com.serotonin.db.MappedRowCallback;
import com.serotonin.db.pair.IntStringPair;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.rt.event.type.AuditEventType;
import com.serotonin.m2m2.vo.comment.UserCommentVO;

/**
 * We don't use XIDs for comments yet.
 * 
 * @author Terry Packer
 *
 */
@Repository()
public class UserCommentDao  extends AbstractDao<UserCommentVO>{

    public static final Name ALIAS = DSL.name("uc");
    public static final Table<? extends Record> TABLE = DSL.table(SchemaDefinition.USER_COMMENTS_TABLE);
    
    private static final LazyInitSupplier<UserCommentDao> springInstance = new LazyInitSupplier<>(() -> {
        Object o = Common.getRuntimeContext().getBean(UserCommentDao.class);
        if(o == null)
            throw new ShouldNeverHappenException("DAO not initialized in Spring Runtime Context");
        return (UserCommentDao)o;
    });

    @Autowired
	private UserCommentDao(@Qualifier(MangoRuntimeContextConfiguration.DAO_OBJECT_MAPPER_NAME)ObjectMapper mapper,
            ApplicationEventPublisher publisher){
		super(AuditEventType.TYPE_USER_COMMENT,
		        TABLE, ALIAS,
				new Field<?>[]{ DSL.field(UserDao.ALIAS.append("username")) }, null,
				mapper, publisher);
	}

    /**
     * Get cached instance from Spring Context
     * @return
     */
    public static UserCommentDao getInstance() {
        return springInstance.get();
    }
    
	public static final String USER_COMMENT_SELECT = "select uc.id, uc.xid, uc.userId, uc.ts, uc.commentText, uc.commentType, uc.typeKey, u.username "
            + "from userComments uc left join users u on uc.userId = u.id ";

    private static final String POINT_COMMENT_SELECT = USER_COMMENT_SELECT
            + "where uc.commentType= " + UserCommentVO.TYPE_POINT + " and uc.typeKey=? " + "order by uc.ts";
    
    private static final String EVENT_COMMENT_SELECT = USER_COMMENT_SELECT //
            + "where uc.commentType= " + UserCommentVO.TYPE_EVENT //
            + " and uc.typeKey=? " //
            + "order by uc.ts";

    private static final String JSON_DATA_COMMENT_SELECT = USER_COMMENT_SELECT
    		+ "where uc.commentType=" + UserCommentVO.TYPE_JSON_DATA 
    		+ " and uc.typeKey=?"
    		+ "order by uc.ts";
    /**
     * Return all comments for a given event
     * @param id
     * @return
     */
    public void getEventComments(int id, MappedRowCallback<UserCommentVO> callback) {
    	query(EVENT_COMMENT_SELECT, new Object[] { id }, new UserCommentVORowMapper(), callback);
    }
    
    /**
     * Return all comments for a given point
     * @param dpId
     * @return
     */
    public void getPointComments(int dpId, MappedRowCallback<UserCommentVO> callback){
    	query(POINT_COMMENT_SELECT, new Object[] { dpId }, new UserCommentVORowMapper(), callback);
    }
    
    /**
     * Return all comments for a given JsonData Store Entry
     * @param jsonDataId
     * @param callback
     */
    public void getJsonDataComments(int jsonDataId, MappedRowCallback<UserCommentVO> callback){
    	query(JSON_DATA_COMMENT_SELECT, new Object[] { jsonDataId }, new UserCommentVORowMapper(), callback);
    }
    
    public  class UserCommentVORowMapper implements RowMapper<UserCommentVO> {

        public UserCommentVO mapRow(ResultSet rs, int rowNum) throws SQLException {
            UserCommentVO c = new UserCommentVO();
            int i=0;
            c.setId(rs.getInt(++i));
            c.setXid(rs.getString(++i));
            c.setUserId(rs.getInt(++i));
            c.setTs(rs.getLong(++i));
            c.setComment(rs.getString(++i));
            c.setCommentType(rs.getInt(++i));
            c.setReferenceId(rs.getInt(++i));
            c.setUsername(rs.getString(++i));
            return c;
        }
    }

	@Override
	protected String getXidPrefix() {
		return "UC_";
	}

	@Override
	protected Object[] voToObjectArray(UserCommentVO vo) {
		return new Object[]{
				vo.getXid(),
				vo.getUserId(),
				vo.getTs(),
				vo.getComment(),
				vo.getCommentType(),
				vo.getReferenceId()
		};
	}

	@Override
	protected Map<String, IntStringPair> getPropertiesMap() {
		Map<String,IntStringPair> map = new HashMap<String,IntStringPair>();
		map.put("username", new IntStringPair(Types.VARCHAR, "u.username"));
		map.put("referenceId", new IntStringPair(Types.INTEGER, "typeKey"));
		map.put("timestamp", new IntStringPair(Types.BIGINT, "ts"));
		return map;
	}

	@Override
	public RowMapper<UserCommentVO> getRowMapper() {
		return new UserCommentVORowMapper();
	}
	
	@Override
	public <R extends Record> SelectJoinStep<R> joinTables(SelectJoinStep<R> select) {
	    return select.join(UserDao.TABLE.as(UserDao.ALIAS)).on(DSL.field(UserDao.ALIAS.append("id")).eq(this.propertyToField.get("userId")));
	}
	

	@Override
	protected LinkedHashMap<String, Integer> getPropertyTypeMap() {
		LinkedHashMap<String, Integer> map = new LinkedHashMap<String, Integer>();
		map.put("id", Types.INTEGER);
		map.put("xid", Types.VARCHAR);
		map.put("userId", Types.INTEGER);
		map.put("ts", Types.BIGINT);
		map.put("commentText", Types.VARCHAR);
		map.put("commentType", Types.INTEGER);
		map.put("typeKey", Types.INTEGER);
		return map;
	}
}
