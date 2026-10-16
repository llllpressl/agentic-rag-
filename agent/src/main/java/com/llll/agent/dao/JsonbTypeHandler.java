package com.llll.agent.dao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** 只处理 JSON 树；AgentState 的多态序列化仍交给 AgentScope。 */
public class JsonbTypeHandler extends BaseTypeHandler<JsonNode> {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index,
                                    JsonNode value, JdbcType jdbcType) throws SQLException {
        statement.setObject(index, value.toString(), Types.OTHER);
    }

    @Override
    public JsonNode getNullableResult(ResultSet result, String name) throws SQLException {
        return parse(result.getString(name));
    }

    @Override
    public JsonNode getNullableResult(ResultSet result, int index) throws SQLException {
        return parse(result.getString(index));
    }

    @Override
    public JsonNode getNullableResult(CallableStatement statement, int index) throws SQLException {
        return parse(statement.getString(index));
    }

    private JsonNode parse(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            return JSON.readTree(value);
        } catch (Exception exception) {
            throw new SQLException("Agent JSONB 格式不合法", exception);
        }
    }
}
