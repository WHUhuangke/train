// MessageLogMapper.java
package com.example.mapper;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface MessageLogMapper {

    @Insert("INSERT INTO t_message_log (message_id, content, status, next_retry) VALUES (#{id}, #{content}, #{status}, #{nextRetry})")
    void insert(@Param("id") String id, @Param("content") String content, @Param("status") int status, @Param("nextRetry") LocalDateTime nextRetry);

    @Update("UPDATE t_message_log SET status = #{status} WHERE message_id = #{id}")
    void updateStatus(@Param("id") String id, @Param("status") int status);

    @Select("SELECT * FROM t_message_log WHERE status = 0 AND next_retry < NOW() LIMIT 100")
    List<Map<String, Object>> selectPendingMessages();

    @Select("SELECT status FROM t_message_log WHERE message_id = #{id}")
    Integer getStatus(@Param("id") String id);
}
